package com.wzx.babiq.server.conversation;

import com.wzx.babiq.server.application.scope.BusinessIdentityScope;

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

    /** turn 全局唯一 id，前后端所有 turn/start、item、summary 事件都会引用它。 */
    private final String id;
    /** turn 所属 thread id，用来把多轮对话归到同一个工作目录和会话上下文。 */
    private final String threadId;
    /** 从所属 Thread 复制的不可变业务身份，worker 只能读这份快照。 */
    private final BusinessIdentityScope businessIdentityScope;
    /** turn 创建时间，后续可以用于历史排序和超时诊断。 */
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
        this(id, threadId, BusinessIdentityScope.UNSCOPED);
    }

    /** 从 Thread 创建带不可变身份快照的 Turn。 */
    public Turn(String id, String threadId, BusinessIdentityScope businessIdentityScope) {
        this.id = id;
        this.threadId = threadId;
        this.businessIdentityScope = businessIdentityScope == null
                ? BusinessIdentityScope.UNSCOPED
                : businessIdentityScope;
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

    public BusinessIdentityScope businessIdentityScope() {
        return businessIdentityScope;
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
     * 把运行中的 turn 标记为被中断。
     *
     * @param reason 中断原因，通常来自用户主动中断或服务端恢复诊断
     */
    public void interrupt(String reason) {
        transitionTo(TurnStatus.INTERRUPTED);
        this.failureReason = reason;
    }

    /**
     * 把等待审批的 turn 标记为过期。
     *
     * @param reason 过期原因，通常来自服务端重启后无法恢复内存检查点
     */
    public void expire(String reason) {
        transitionTo(TurnStatus.EXPIRED);
        this.failureReason = reason;
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
