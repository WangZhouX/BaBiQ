package com.wzx.babiq.server.interceptor;

import com.alibaba.cloud.ai.graph.agent.interceptor.ToolCallHandler;
import com.alibaba.cloud.ai.graph.agent.interceptor.ToolCallRequest;
import com.alibaba.cloud.ai.graph.agent.interceptor.ToolCallResponse;
import com.alibaba.cloud.ai.graph.agent.interceptor.ToolInterceptor;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wzx.babiq.server.agent.AgentLoopProperties;
import com.wzx.babiq.server.conversation.ConversationService;
import com.wzx.babiq.server.conversation.ItemEmitter;
import com.wzx.babiq.server.conversation.items.FileChangeItem;
import com.wzx.babiq.server.sandbox.PathGuard;
import com.wzx.babiq.server.sandbox.SandboxMode;
import com.wzx.babiq.server.sandbox.SandboxViolationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * BaBiQ 工具沙箱拦截器。
 *
 * <p>这是 D31 的统一入口：工具自身保持纯 IO，所有写类工具进入执行器前先经过本拦截器。
 * 它存在的原因是把沙箱职责固定在 SAA ToolInterceptor 层，避免 read/write/exec 工具各自复制
 * 路径校验逻辑。ReActStrategy 将 thread.cwd 放进 toolContext，本类据此按 turn 生成 PathGuard。</p>
 */
@Component
public final class BaBiQSandboxInterceptor extends ToolInterceptor {

    /** ReActStrategy 放入 toolContext 的本轮 cwd key。 */
    public static final String CONTEXT_CWD = "babiq.cwd";

    /** ReActStrategy 放入 toolContext 的额外 writable roots key。 */
    public static final String CONTEXT_WRITABLE_ROOTS = "babiq.writableRoots";

    /** ReActStrategy 放入 toolContext 的当前 ItemEmitter key。 */
    public static final String CONTEXT_ITEM_EMITTER = "babiq.itemEmitter";

    private static final Logger log = LoggerFactory.getLogger(BaBiQSandboxInterceptor.class);
    /** 解析工具调用入参，提取 path/cwd 等需要做沙箱校验的字段。 */
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    /** 会改变本地文件系统或执行命令的工具集合，只有这些工具需要写权限审批/沙箱。 */
    private static final Set<String> WRITE_TOOLS = Set.of("write_file", "exec_shell", "apply_patch");

    /** agent-loop.sandbox 配置，决定当前是 workspace-only 还是更宽的本地访问模式。 */
    private final AgentLoopProperties properties;
    /** 用于查询 thread 的 cwd，防止工具调用伪造一个不属于当前会话的工作目录。 */
    private final ConversationService conversationService;

    /**
     * 创建沙箱拦截器。
     *
     * @param properties Agent Loop 配置，提供沙箱模式和额外可写根
     * @param conversationService 负责构造协议 item 的内存服务
     */
    public BaBiQSandboxInterceptor(AgentLoopProperties properties, ConversationService conversationService) {
        this.properties = properties;
        this.conversationService = conversationService;
    }

    /**
     * 返回拦截器名称，供 SAA 日志和调试定位。
     *
     * @return 拦截器名称
     */
    public String getName() {
        return "babiq_sandbox";
    }

    /**
     * 拦截工具调用并执行 D31 沙箱校验。
     *
     * @param request SAA 工具调用请求
     * @param handler 下一个工具处理器
     * @return 工具响应；沙箱拒绝时直接返回 error 响应
     */
    @Override
    public ToolCallResponse interceptToolCall(ToolCallRequest request, ToolCallHandler handler) {
        String rejection = checkOrReject(request.getToolName(), request.getArguments(), request.getContext());
        if (rejection != null) {
            emitDeniedFileChangeIfNeeded(request, rejection);
            // 2026-05-25 修复记录：SAA 的静态工厂方法参数顺序是 toolCallId、toolName、错误内容。
            // 如果按“工具名、调用 id”的直觉顺序传参，最终生成的 ToolResponseMessage 会把
            // tool_call_id 写成工具名，DeepSeek/OpenAI 兼容接口会认为 assistant.tool_calls
            // 没有匹配的 tool 响应，从而在审批恢复后返回 400 Bad Request。
            return ToolCallResponse.error(request.getToolCallId(), request.getToolName(), rejection);
        }
        return handler.call(request);
    }

    /**
     * 判断工具是否需要沙箱校验。
     *
     * @param toolName 工具名称
     * @return 写类工具返回 true，读类工具返回 false
     */
    public boolean shouldEnforceSandbox(String toolName) {
        return WRITE_TOOLS.contains(toolName);
    }

    /**
     * 执行可测试的沙箱判断。
     *
     * @param toolName 工具名称
     * @param arguments 工具入参 JSON
     * @param context SAA toolContext，必须携带本轮 cwd
     * @return null 表示放行；非 null 表示拒绝原因
     */
    public String checkOrReject(String toolName, String arguments, java.util.Map<String, Object> context) {
        if (!shouldEnforceSandbox(toolName)) {
            return null;
        }
        if (properties.sandboxMode() == SandboxMode.READ_ONLY) {
            return "Sandbox is read-only, " + toolName + " rejected";
        }
        if (properties.sandboxMode() == SandboxMode.DANGER_FULL_ACCESS) {
            return null;
        }
        String path = pathToCheck(toolName, arguments, context);
        if (path == null || path.isBlank()) {
            return toolName + " missing path or cwd for sandbox check";
        }
        try {
            // D31：PathGuard 内部用 toRealPath + Path.startsWith，不做裸字符串前缀比较。
            new PathGuard(writableRoots(context)).checkWrite(path);
            return null;
        } catch (SandboxViolationException exception) {
            return "Sandbox violation: " + exception.getMessage();
        }
    }

    private String pathToCheck(String toolName, String arguments, java.util.Map<String, Object> context) {
        if ("exec_shell".equals(toolName)) {
            Object cwd = context == null ? null : context.get(CONTEXT_CWD);
            return cwd == null ? null : cwd.toString();
        }
        return extractPathArgument(arguments);
    }

    @SuppressWarnings("unchecked")
    private List<Path> writableRoots(java.util.Map<String, Object> context) {
        List<Path> roots = new ArrayList<>();
        Object cwd = context == null ? null : context.get(CONTEXT_CWD);
        if (cwd != null && !cwd.toString().isBlank()) {
            roots.add(Path.of(cwd.toString()));
        }
        Object configured = context == null ? null : context.get(CONTEXT_WRITABLE_ROOTS);
        if (configured instanceof List<?> list) {
            for (Object root : list) {
                if (root != null && !root.toString().isBlank()) {
                    roots.add(Path.of(root.toString()));
                }
            }
        }
        roots.addAll(properties.writableRoots());
        return List.copyOf(roots);
    }

    /**
     * 当写类工具被沙箱拒绝时，同步构造一个 fileChange denied item，供协议层回写。
     *
     * <p>这条回写是为了满足 P1-3a 的可见性要求：拒绝不是只回一个 ToolCallResponse.error，
     * 还要让前端能在 item 流里看到具体拒绝结果。</p>
     */
    private void emitDeniedFileChangeIfNeeded(ToolCallRequest request, String rejection) {
        if (!"write_file".equals(request.getToolName())) {
            return;
        }
        ItemEmitter emitter = itemEmitter(request.getContext());
        if (emitter == null) {
            return;
        }
        String path = extractPathArgument(request.getArguments());
        if (path == null || path.isBlank()) {
            return;
        }
        FileChangeItem denied = conversationService.emitFileChange("write", path, "denied", rejection);
        try {
            emitter.emitFileChange(denied);
        } catch (Exception exception) {
            log.warn("发送 denied fileChange 失败,toolCallId={},path={}", request.getToolCallId(), path, exception);
        }
    }

    /**
     * 从工具上下文中取出当前 ItemEmitter。
     *
     * @param context SAA toolContext
     * @return 当前会话发射器,取不到时返回 null
     */
    private ItemEmitter itemEmitter(java.util.Map<String, Object> context) {
        Object candidate = context == null ? null : context.get(CONTEXT_ITEM_EMITTER);
        if (candidate instanceof ItemEmitter emitter) {
            return emitter;
        }
        return null;
    }

    /**
     * 解析写类工具的 path 参数。
     *
     * @param arguments 工具 JSON 参数
     * @return path 字段;缺失或 JSON 非法时返回 null
     */
    private String extractPathArgument(String arguments) {
        if (arguments == null || arguments.isBlank()) {
            return null;
        }
        try {
            JsonNode pathNode = OBJECT_MAPPER.readTree(arguments).get("path");
            return pathNode == null || pathNode.isNull() ? null : pathNode.asText();
        } catch (Exception ignored) {
            return null;
        }
    }
}
