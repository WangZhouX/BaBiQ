package com.wzx.babiq.server.agent;

import com.alibaba.cloud.ai.graph.action.InterruptionMetadata;
import com.wzx.babiq.server.conversation.ItemEmitter;
import com.wzx.babiq.server.conversation.Turn;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/**
 * Turn 异步执行器。
 *
 * <p>它存在于协议 handler 与 AgentLoop 之间：handler 必须快速返回 JSON-RPC response，
 * 而 ReactAgent 可能长期运行或等待 HITL 中断。因此本类负责提交 worker、记录 running future，
 * 并让 turn/interrupt 能取消正在运行的任务。</p>
 */
@Component
public class TurnExecutor {

    private static final Logger log = LoggerFactory.getLogger(TurnExecutor.class);

    /** 单个 turn 的实际业务执行器；本类只调度线程，不直接实现模型和工具逻辑。 */
    private final AgentLoop agentLoop;

    /** P1 使用 cachedThreadPool 简化本机异步执行；P2 可根据资源隔离需求换成受控线程池。 */
    private final ExecutorService executor = Executors.newCachedThreadPool();

    /** turnId -> Future，用于 turn/interrupt 找到正在运行的 worker。 */
    private final Map<String, Future<?>> running = new ConcurrentHashMap<>();

    /**
     * 创建执行器。
     *
     * @param agentLoop Agent 主流程
     */
    public TurnExecutor(AgentLoop agentLoop) {
        this.agentLoop = agentLoop;
    }

    /**
     * 异步提交普通 turn。
     *
     * @param turn 当前 turn
     * @param userText 用户文本
     * @param providerId provider id，可为 null
     * @param cwd 本轮工作目录
     * @param emitter 当前 WebSocket 发射器
     */
    public void submit(Turn turn, String userText, String providerId, String cwd, ItemEmitter emitter) {
        submit(turn, userText, providerId, cwd, emitter, null);
    }

    /**
     * 异步提交普通 turn，并携带 turn/start 的权限快照。
     *
     * @param turn 当前 turn
     * @param userText 用户文本
     * @param providerId provider id，可为 null
     * @param cwd 本轮工作目录
     * @param emitter 当前 WebSocket 发射器
     * @param runPolicy 本轮 Agent 运行时权限；为空时 AgentLoop 回退到 yml 默认值
     */
    public void submit(Turn turn, String userText, String providerId, String cwd,
                       ItemEmitter emitter, AgentRunPolicy runPolicy) {
        submit(turn, userText, providerId, cwd, emitter, runPolicy, null);
    }

    /**
     * 异步提交普通 turn，并可选绑定工作容器目标。
     *
     * @param turn 当前 turn
     * @param userText 用户文本
     * @param providerId provider id，可为 null
     * @param cwd 本轮工作目录
     * @param emitter 当前 WebSocket 发射器
     * @param runPolicy 本轮 Agent 运行时权限；为空时 AgentLoop 回退到 yml 默认值
     * @param workUnitGoalId 本轮要回写的工作容器目标 id，可为 null
     */
    public void submit(Turn turn, String userText, String providerId, String cwd,
                       ItemEmitter emitter, AgentRunPolicy runPolicy, String workUnitGoalId) {
        log.info("TurnExecutor 提交普通 turn: threadId={}, turnId={}, providerId={}, cwd={}",
                turn.threadId(), turn.id(), providerId == null ? "<active-provider>" : providerId, cwd);
        Future<?> future = executor.submit(() -> run(turn.id(),
                () -> agentLoop.invoke(turn, userText, providerId, cwd, emitter, runPolicy, workUnitGoalId)));
        // submit 之后再放入 running，interrupt 才能通过 turnId 找到后台任务。
        running.put(turn.id(), future);
        if (future.isDone()) {
            running.remove(turn.id(), future);
        }
    }

    /**
     * 异步提交 HITL resume。
     *
     * @param turn 当前 turn
     * @param feedback 用户审批反馈
     * @param cwd 本轮工作目录
     * @param emitter 当前 WebSocket 发射器
     */
    public void submitResume(Turn turn, InterruptionMetadata feedback, String cwd, ItemEmitter emitter) {
        submitResume(turn, feedback, cwd, emitter, null);
    }

    /**
     * 异步提交 HITL resume，并继续使用原 turn 的权限快照。
     *
     * @param turn 当前 turn
     * @param feedback 用户审批反馈
     * @param cwd 本轮工作目录
     * @param emitter 当前 WebSocket 发射器
     * @param runPolicy 原 turn 的沙箱和审批策略快照
     */
    public void submitResume(Turn turn, InterruptionMetadata feedback, String cwd,
                             ItemEmitter emitter, AgentRunPolicy runPolicy) {
        log.info("TurnExecutor 提交审批恢复 turn: threadId={}, turnId={}, cwd={}",
                turn.threadId(), turn.id(), cwd);
        Future<?> future = executor.submit(() -> run(turn.id(),
                () -> agentLoop.invokeResume(turn, feedback, cwd, emitter, runPolicy)));
        // resume 仍然属于同一个 turn，所以复用同一个 turnId 作为 running key。
        running.put(turn.id(), future);
        if (future.isDone()) {
            running.remove(turn.id(), future);
        }
    }

    /**
     * 取消正在运行的 turn。
     *
     * @param turnId turn id
     * @return 找到运行任务并发出取消时返回 true
     */
    public boolean interrupt(String turnId) {
        Future<?> future = running.get(turnId);
        if (future == null) {
            log.warn("turn/interrupt 未找到运行中的 turn: {}", turnId);
            return false;
        }
        boolean canceled = future.cancel(true);
        running.remove(turnId, future);
        log.info("turn/interrupt 已请求取消: turnId={}, canceled={}", turnId, canceled);
        return canceled;
    }

    /**
     * 包装 worker 生命周期日志和 running 清理。
     */
    private void run(String turnId, Runnable action) {
        log.info("TurnExecutor worker 开始: turnId={}", turnId);
        try {
            action.run();
        } finally {
            // finally 中清理，确保 AgentLoop 成功、失败或被取消后 running 都不会残留。
            running.remove(turnId);
            log.info("TurnExecutor worker 结束: turnId={}", turnId);
        }
    }
}
