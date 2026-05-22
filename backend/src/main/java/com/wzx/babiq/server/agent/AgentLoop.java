package com.wzx.babiq.server.agent;

import com.alibaba.cloud.ai.graph.NodeOutput;
import com.alibaba.cloud.ai.graph.action.InterruptionMetadata;
import com.alibaba.cloud.ai.graph.agent.ReactAgent;
import com.wzx.babiq.server.conversation.ItemEmitter;
import com.wzx.babiq.server.conversation.Turn;
import com.wzx.babiq.server.conversation.items.AgentMessageItem;
import com.wzx.babiq.server.conversation.items.UserMessageItem;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.stereotype.Component;

import java.util.Optional;

/**
 * Agent Loop 主流程，负责发 user item、调用 ReactAgent、处理完成或 HITL 中断。
 */
@Component
public class AgentLoop {

    private final ReActStrategy strategy;
    private final PendingApprovals pendingApprovals;

    /**
     * 创建 AgentLoop。
     *
     * @param strategy ReAct 装配策略
     * @param pendingApprovals HITL 待审批缓存
     */
    public AgentLoop(ReActStrategy strategy, PendingApprovals pendingApprovals) {
        this.strategy = strategy;
        this.pendingApprovals = pendingApprovals;
    }

    public void invoke(Turn turn, String userText, String providerId, String cwd, ItemEmitter emitter) {
        try {
            emitter.emitItemAdded(UserMessageItem.of(AgentLoopSupport.newItemId(), userText));
            ReactAgent agent = strategy.buildAgent(providerId, cwd);
            Optional<NodeOutput> output = agent.invokeAndGetOutput(userText, strategy.buildConfig(turn.threadId()));
            handleOutput(turn, emitter, output);
        } catch (Exception exception) {
            AgentLoopSupport.fail(org.slf4j.LoggerFactory.getLogger(AgentLoop.class), turn, emitter, exception);
        }
    }

    public void invokeResume(Turn turn, InterruptionMetadata feedback, String cwd, ItemEmitter emitter) {
        try {
            ReactAgent agent = strategy.buildAgent(null, cwd);
            Optional<NodeOutput> output = agent.invokeAndGetOutput(java.util.Map.of(),
                    strategy.buildResumeConfig(turn.threadId(), feedback));
            handleOutput(turn, emitter, output);
        } catch (Exception exception) {
            AgentLoopSupport.fail(org.slf4j.LoggerFactory.getLogger(AgentLoop.class), turn, emitter, exception);
        }
    }

    private void handleOutput(Turn turn, ItemEmitter emitter, Optional<NodeOutput> output) throws Exception {
        NodeOutput node = output.orElseThrow(() -> new IllegalStateException("ReactAgent 返回空输出"));
        if (node instanceof InterruptionMetadata metadata) {
            pendingApprovals.put(turn.threadId(), metadata);
            turn.waitApproval();
            strategy.emitApprovalRequests(turn, emitter, metadata);
            return;
        }
        AssistantMessage assistantMessage = strategy.extractAssistantMessage(node);
        emitter.emitItemAdded(AgentMessageItem.full(AgentLoopSupport.newItemId(), assistantMessage.getText()));
        turn.complete();
        emitter.emitTurnCompleted("completed");
    }
}
