package com.wzx.babiq.server.agent.delegation;

import com.wzx.babiq.server.conversation.ItemEmitter;
import com.wzx.babiq.server.conversation.items.AgentDelegationItem;
import com.wzx.babiq.server.observability.TurnObservationContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * 一次子 Agent 委派的运行态上下文。
 *
 * <p>该对象通过 Spring AI ToolContext 和 RunnableConfig metadata 传到子 Agent 工具链，
 * 供观测拦截器聚合工具次数、写入归属字段，并把 UI 更新折叠到同一个 agentDelegation item。</p>
 */
public final class SubAgentDelegationContext {

    /** 写入 ToolContext/RunnableConfig metadata 的 key。 */
    public static final String METADATA_KEY = "babiq.subAgentDelegation";

    private static final Logger log = LoggerFactory.getLogger(SubAgentDelegationContext.class);

    /** 对应桌面端 item id，同一委派生命周期内保持不变。 */
    private final String itemId;
    /** 委派 id，用于工具调用运行记录归属。 */
    private final String delegationId;
    /** 发起委派的父 Agent 名称。 */
    private final String parentAgent;
    /** 被委派的子 Agent 名称。 */
    private final String childAgent;
    /** 委派模式，P6-1 固定为 READ_ONLY_TOOL。 */
    private final BabiqAgentMode mode;
    /** 当前 turn 的 item 发射器；测试或无 UI 场景允许为空。 */
    private final ItemEmitter emitter;
    /** 当前 turn 的观测上下文，用于估算委派期间新增 token。 */
    private final TurnObservationContext observationContext;
    /** 委派开始时的 turn token 快照。 */
    private final long tokenBaseline;
    /** 子 Agent 内部工具调用次数。 */
    private final AtomicInteger toolCallCount = new AtomicInteger();

    private SubAgentDelegationContext(String itemId,
                                      String delegationId,
                                      String parentAgent,
                                      String childAgent,
                                      BabiqAgentMode mode,
                                      ItemEmitter emitter,
                                      TurnObservationContext observationContext) {
        this.itemId = itemId;
        this.delegationId = delegationId;
        this.parentAgent = parentAgent;
        this.childAgent = childAgent;
        this.mode = mode;
        this.emitter = emitter;
        this.observationContext = observationContext;
        this.tokenBaseline = observationContext == null ? 0L : observationContext.totalTokens();
    }

    /**
     * 创建一次委派上下文。
     */
    public static SubAgentDelegationContext started(String itemId,
                                                    String delegationId,
                                                    String parentAgent,
                                                    String childAgent,
                                                    BabiqAgentMode mode,
                                                    ItemEmitter emitter,
                                                    TurnObservationContext observationContext) {
        return new SubAgentDelegationContext(itemId, delegationId, parentAgent, childAgent, mode, emitter, observationContext);
    }

    /**
     * 记录子 Agent 内部工具调用，并把 UI 聚合 item 更新为 running。
     */
    public void recordChildToolCall(String toolName) {
        int count = toolCallCount.incrementAndGet();
        emitUpdated("running", "正在调用只读工具 " + toolName, count);
    }

    /**
     * 发送委派开始事件。
     */
    public void emitStarted(String summary) {
        emitAdded("running", summary);
    }

    /**
     * 发送委派完成事件。
     */
    public void emitCompleted(String summary) {
        emitUpdated("completed", summary, toolCallCount());
    }

    /**
     * 发送委派失败事件。
     */
    public void emitFailed(String summary) {
        emitUpdated("failed", summary, toolCallCount());
    }

    public String itemId() {
        return itemId;
    }

    public String delegationId() {
        return delegationId;
    }

    public String parentAgent() {
        return parentAgent;
    }

    public String childAgent() {
        return childAgent;
    }

    public BabiqAgentMode mode() {
        return mode;
    }

    public int toolCallCount() {
        return toolCallCount.get();
    }

    /**
     * 返回委派期间新增 token 的粗估值，供 UI 显示，不用于账单。
     */
    public int tokenEstimate() {
        if (observationContext == null) {
            return 0;
        }
        long delta = Math.max(0L, observationContext.totalTokens() - tokenBaseline);
        return (int) Math.min(Integer.MAX_VALUE, delta);
    }

    private void emitAdded(String status, String summary) {
        if (emitter == null) {
            return;
        }
        try {
            emitter.emitItemAdded(item(status, summary, toolCallCount()));
        } catch (Exception exception) {
            log.warn("发送子 Agent 委派开始事件失败: delegationId={}, reason={}", delegationId, exception.getMessage());
            log.debug("发送子 Agent 委派开始事件失败详情", exception);
        }
    }

    private void emitUpdated(String status, String summary, int count) {
        if (emitter == null) {
            return;
        }
        try {
            emitter.emitItemUpdated(item(status, summary, count));
        } catch (Exception exception) {
            log.warn("发送子 Agent 委派更新事件失败: delegationId={}, reason={}", delegationId, exception.getMessage());
            log.debug("发送子 Agent 委派更新事件失败详情", exception);
        }
    }

    private AgentDelegationItem item(String status, String summary, int count) {
        return new AgentDelegationItem(
                itemId,
                "agentDelegation",
                delegationId,
                parentAgent,
                childAgent,
                status,
                mode.name(),
                summary,
                count,
                tokenEstimate());
    }
}
