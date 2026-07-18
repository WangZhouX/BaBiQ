package com.wzx.babiq.server.conversation;

/**
 * Turn 生命周期状态枚举。
 *
 * <p>Turn 表示用户一次输入到 Agent 一次完整响应的执行过程。协议层需要明确
 * 知道当前 Turn 能否继续输出、能否等待审批、能否取消,因此状态集合必须小而
 * 稳定。P2-4 增加了重启恢复语义：服务端启动时发现历史 RUNNING 会转为 INTERRUPTED，
 * 发现历史 WAITING_APPROVAL 会转为 EXPIRED，避免 UI 一直显示不可恢复的运行中状态。</p>
 */
public enum TurnStatus {

    /** 刚创建,还没有进入 Agent 执行流程。 */
    CREATED,

    /** Agent 正在执行,可能正在生成文本或准备工具调用。 */
    RUNNING,

    /** Agent 请求高风险动作审批,等待 approval/respond。 */
    WAITING_APPROVAL,

    /** Turn 正常完成,不会再产生新的 item。 */
    COMPLETED,

    /** Turn 执行失败,失败原因记录在 Turn.failureReason。 */
    FAILED,

    /** 用户主动取消或中断后结束。 */
    CANCELED,

    /** 进程重启或用户中断导致 turn 没有自然完成；属于可解释终态。 */
    INTERRUPTED,

    /** 等待审批的暂停点已经失效；P2 不恢复 SAA 内存检查点，因此审批也必须过期。 */
    EXPIRED;

    /**
     * 判断当前状态是否为终态。
     *
     * @return true 表示状态已经结束,不能再迁移到其他状态
     */
    public boolean isTerminal() {
        return this == COMPLETED || this == FAILED || this == CANCELED
                || this == INTERRUPTED || this == EXPIRED;
    }

    /**
     * 判断从当前状态迁移到目标状态是否合法。
     *
     * @param target 目标状态
     * @return true 表示 ARCHITECTURE §5.2 允许该迁移
     */
    public boolean canTransitionTo(TurnStatus target) {
        if (target == null || isTerminal()) {
            return false;
        }

        // 状态机转移表集中写在 enum 中,避免 Turn 对象散落复杂 if-else。
        return switch (this) {
            case CREATED -> target == RUNNING || target == CANCELED || target == EXPIRED;
            case RUNNING -> target == WAITING_APPROVAL
                    || target == COMPLETED
                    || target == FAILED
                    || target == CANCELED
                    || target == INTERRUPTED;
            case WAITING_APPROVAL -> target == RUNNING
                    || target == FAILED
                    || target == CANCELED
                    || target == EXPIRED;
            case COMPLETED, FAILED, CANCELED, INTERRUPTED, EXPIRED -> false;
        };
    }
}
