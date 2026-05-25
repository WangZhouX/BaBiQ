package com.wzx.babiq.server.agent;

import com.alibaba.cloud.ai.graph.action.InterruptionMetadata;
import com.alibaba.cloud.ai.graph.agent.ReactAgent;
import com.alibaba.cloud.ai.graph.agent.hook.JumpTo;
import com.wzx.babiq.server.conversation.ItemEmitter;
import com.wzx.babiq.server.conversation.Turn;
import com.wzx.babiq.server.observability.TurnObservationContext;

import java.util.Map;

/**
 * AgentLoop 的 HITL 恢复调用助手。
 *
 * <p>它只封装“审批通过后先执行工具节点”的 SAA Graph 调用细节；完成态、失败态和协议 item
 * 仍由 AgentLoop 主流程统一收口。</p>
 */
final class AgentLoopResumeSupport {

    private AgentLoopResumeSupport() {
    }

    /**
     * 从 HITL 暂停点继续执行，并强制下一跳进入工具节点。
     *
     * <p>2026-05-25 Bug 修复记录：SAA 的 HumanInTheLoopHook 在 APPROVED/EDITED 后只会把
     * 审批结果写回 assistant.tool_calls，不会自动生成 ToolResponseMessage。这里显式写入
     * {@link JumpTo#tool}，让 Graph 先执行工具，再回到模型节点生成最终回答。</p>
     *
     * @param turn 当前业务 turn，用于取 threadId 构造 RunnableConfig
     * @param feedback 用户审批后的真实 HITL 反馈元数据
     * @param emitter 当前 WebSocket item 发射器，工具执行期间可能继续发 fileChange/tool item
     * @param context 本轮观测上下文，用于 token 和工具调用统计
     * @param agent 被暂停的同一个 ReactAgent 实例
     * @param strategy ReAct 装配策略，用于生成恢复配置
     * @return 恢复执行后的流式消费结果
     * @throws Exception SAA Graph 或 WebSocket 流式消费失败时向上抛出，由 AgentLoopOutputHandler 统一收口
     */
    static AgentStreamConsumer.StreamResult resumeFromApproval(Turn turn,
                                                               InterruptionMetadata feedback,
                                                               String cwd,
                                                               ItemEmitter emitter,
                                                               TurnObservationContext context,
                                                               ReactAgent agent,
                                                               ReActStrategy strategy) throws Exception {
        return AgentStreamConsumer.consume(
                agent.stream(Map.of("jump_to", JumpTo.tool), strategy.buildResumeConfig(turn.threadId(), feedback, cwd, emitter, context)),
                emitter);
    }
}
