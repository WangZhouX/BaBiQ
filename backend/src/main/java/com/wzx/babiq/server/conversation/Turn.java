package com.wzx.babiq.server.conversation;

import java.time.Instant;

/**
 * 一轮对话的可变生命周期对象。
 *
 * <p>每个 Turn 对应用户一次输入以及 Agent 一次完整响应。它必须是可变对象,
 * 因为 WebSocket 流式执行期间状态会从 CREATED 迁移到 RUNNING,再进入完成、
 * 失败或取消。状态迁移统一走 {@link TurnStatus#canTransitionTo(TurnStatus)},
 * 防止 handler 随意改状态。</p>
 */
public final class Turn {

    private final String id;
    private final String threadId;
    private final Instant createdAt;
    private TurnStatus status;
    private String failureReason;

    /**
     * 创建一个处于 CREATED 状态的新 Turn。
     *
     * @param id 协议层 Turn 标识,固定以 turn_ 开头
     * @param threadId 所属 Thread 标识
     */
    public Turn(String id, String threadId) {
        this.id = id;
        this.threadId = threadId;
        this.createdAt = Instant.now();
        this.status = TurnStatus.CREATED;
    }

    /**
     * 返回 Turn 标识。
     *
     * @return 当前 Turn 的协议 id
     */
    public String id() {
        return id;
    }

    /**
     * 返回所属 Thread 标识。
     *
     * @return 创建该 Turn 的 threadId
     */
    public String threadId() {
        return threadId;
    }

    /**
     * 返回当前状态。
     *
     * @return 当前 TurnStatus
     */
    public TurnStatus status() {
        return status;
    }

    /**
     * 返回失败原因。
     *
     * @return Turn 失败时的原因;未失败时为 null
     */
    public String failureReason() {
        return failureReason;
    }

    /**
     * 返回创建时间。
     *
     * @return Turn 创建时间
     */
    public Instant createdAt() {
        return createdAt;
    }

    /**
     * 将 Turn 从 CREATED 切换到 RUNNING。
     *
     * @throws IllegalStateException 当前状态不允许开始时抛出
     */
    public void start() {
        transitionTo(TurnStatus.RUNNING);
    }

    /**
     * 将 Turn 从 RUNNING 切换到 WAITING_APPROVAL。
     *
     * @throws IllegalStateException 当前状态不允许等待审批时抛出
     */
    public void waitApproval() {
        transitionTo(TurnStatus.WAITING_APPROVAL);
    }

    /**
     * 将 Turn 从 WAITING_APPROVAL 恢复到 RUNNING。
     *
     * @throws IllegalStateException 当前状态不允许恢复执行时抛出
     */
    public void resume() {
        transitionTo(TurnStatus.RUNNING);
    }

    /**
     * 将 Turn 标记为正常完成。
     *
     * @throws IllegalStateException 当前状态不允许完成时抛出
     */
    public void complete() {
        transitionTo(TurnStatus.COMPLETED);
    }

    /**
     * 将 Turn 标记为取消。
     *
     * @throws IllegalStateException 当前状态不允许取消时抛出
     */
    public void cancel() {
        transitionTo(TurnStatus.CANCELED);
    }

    /**
     * 将 Turn 标记为失败。
     *
     * @param reason 失败原因,会写入 failureReason 供日志和协议错误使用
     * @throws IllegalStateException 当前状态不允许失败时抛出
     */
    public void fail(String reason) {
        transitionTo(TurnStatus.FAILED);
        this.failureReason = reason;
    }

    /**
     * 将 Turn 标记为失败,但不附加原因。
     *
     * @throws IllegalStateException 当前状态不允许失败时抛出
     */
    public void fail() {
        fail(null);
    }

    private void transitionTo(TurnStatus nextStatus) {
        if (!status.canTransitionTo(nextStatus)) {
            throw new IllegalStateException(
                    "非法 Turn 状态迁移: turnId=" + id + ", current=" + status + ", next=" + nextStatus);
        }

        // 只有通过状态机校验后才落状态,避免异常路径留下半更新对象。
        this.status = nextStatus;
    }
}
