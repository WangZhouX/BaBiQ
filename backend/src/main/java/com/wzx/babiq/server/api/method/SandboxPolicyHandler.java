package com.wzx.babiq.server.api.method;

import com.fasterxml.jackson.databind.JsonNode;
import com.wzx.babiq.server.agent.AgentLoopProperties;
import com.wzx.babiq.server.api.JsonRpcMethodHandler;
import com.wzx.babiq.server.sandbox.SandboxMode;
import com.wzx.babiq.server.settings.AppSettingsService;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.WebSocketSession;

import java.util.Map;

/**
 * sandbox/policy 方法处理器。
 *
 * <p>桌面端权限 chip 必须展示后端真实沙箱模式。P2-3 后优先读取 AppSettingsService，
 * 若单元测试只传入 AgentLoopProperties，则退回 yml 默认值。</p>
 */
@Component
public class SandboxPolicyHandler implements JsonRpcMethodHandler {

    /** Agent Loop 配置快照，沙箱模式的真实来源。 */
    private final AgentLoopProperties properties;
    /** 应用设置服务；存在时覆盖 yml 默认值。 */
    private final AppSettingsService appSettingsService;

    /**
     * 创建沙箱策略查询处理器。
     *
     * @param properties Agent Loop 配置
     */
    public SandboxPolicyHandler(AgentLoopProperties properties) {
        this(properties, null);
    }

    /**
     * 创建带设置服务的沙箱策略查询处理器。
     *
     * @param properties Agent Loop 配置
     * @param appSettingsService 应用设置服务
     */
    @org.springframework.beans.factory.annotation.Autowired
    public SandboxPolicyHandler(AgentLoopProperties properties, AppSettingsService appSettingsService) {
        this.properties = properties;
        this.appSettingsService = appSettingsService;
    }

    /**
     * 返回当前 handler 绑定的 JSON-RPC method。
     *
     * @return sandbox/policy
     */
    @Override
    public String method() {
        return "sandbox/policy";
    }

    /**
     * 返回当前沙箱模式和中文展示名。
     *
     * @param params 请求参数，本方法当前不需要参数
     * @param session 当前 WebSocket 会话，本方法不依赖 session 状态
     * @return 包含 mode/label 的响应对象
     */
    @Override
    public Object handle(JsonNode params, WebSocketSession session) {
        SandboxMode mode = appSettingsService == null
                ? properties.sandboxMode()
                : SandboxMode.valueOf(appSettingsService.get().sandboxMode());
        return Map.of(
                "mode", mode.name(),
                "label", labelOf(mode)
        );
    }

    /**
     * 把后端枚举转成用户能直接理解的权限文案。
     *
     * @param mode 后端沙箱模式
     * @return 中文展示名
     */
    private static String labelOf(SandboxMode mode) {
        return switch (mode) {
            case READ_ONLY -> "只读权限";
            case WORKSPACE_WRITE -> "工作区可写";
            case DANGER_FULL_ACCESS -> "完全访问权限";
        };
    }
}
