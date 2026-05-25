package com.wzx.babiq.server.hook;

import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.RunnableConfig;
import com.alibaba.cloud.ai.graph.agent.hook.JumpTo;
import com.alibaba.cloud.ai.graph.agent.hook.ModelHook;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * 清理 HITL 审批恢复时使用的一次性跳转标记。
 *
 * <p>Spring AI Alibaba ReactAgent 的 {@code jump_to} 是普通 Graph 状态，写入后会一直留在
 * {@link OverAllState} 里。BaBiQ 在审批通过后会临时写入 {@link JumpTo#tool}，让 Graph
 * 从暂停点先执行被审批的工具；工具执行完以后，下一步应该回到模型节点生成最终回答。
 * 如果不在这里移除 {@code jump_to}，后续每次模型输出都会继续被路由回工具节点，最终触发
 * model-call-limit，真实 DeepSeek 链路上也会表现成审批后继续失败。</p>
 */
@Component
public class ResumeJumpCleanupHook extends ModelHook {

    /** ReactAgent 内部用于保存强制跳转目标的状态 key。 */
    private static final String JUMP_TO_KEY = "jump_to";

    /** ReactAgent 内部保存消息列表的状态 key。 */
    private static final String MESSAGES_KEY = "messages";

    /**
     * 返回 Hook 名称，方便 SAA Graph 在日志和节点名里标识这个清理节点。
     */
    @Override
    public String getName() {
        return "babiq_resume_jump_cleanup";
    }

    /**
     * 在模型调用前检查是否已经执行过工具；如果已经拿到工具响应，就移除一次性跳转标记。
     *
     * @param state 当前 Graph 状态，包含消息历史和 jump_to 标记
     * @param config 当前运行配置，本 Hook 不读取配置，只保持签名与 SAA 一致
     * @return 需要合并回 Graph 状态的补丁；返回 MARK_FOR_REMOVAL 表示删除该 key
     */
    @Override
    public CompletableFuture<Map<String, Object>> beforeModel(OverAllState state, RunnableConfig config) {
        Object jumpTo = state.value(JUMP_TO_KEY).orElse(null);
        if (!isToolJump(jumpTo) || !lastMessageIsToolResponse(state)) {
            return CompletableFuture.completedFuture(Map.of());
        }
        return CompletableFuture.completedFuture(Map.of(JUMP_TO_KEY, OverAllState.MARK_FOR_REMOVAL));
    }

    /**
     * 判断当前跳转值是否表示“审批恢复后先进入工具节点”。
     */
    private boolean isToolJump(Object jumpTo) {
        return jumpTo == JumpTo.tool || JumpTo.tool.name().equals(jumpTo);
    }

    /**
     * 判断消息历史最后一条是否已经是工具响应。
     *
     * <p>这个条件保证清理动作发生在“工具已经执行完、准备回到模型”这个时间点，而不是刚恢复时
     * 提前清掉跳转标记。</p>
     */
    private boolean lastMessageIsToolResponse(OverAllState state) {
        List<?> messages = state.value(MESSAGES_KEY, List.of());
        return !messages.isEmpty() && messages.get(messages.size() - 1) instanceof ToolResponseMessage;
    }
}
