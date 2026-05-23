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

    private final AgentLoop agentLoop;
    private final ExecutorService executor = Executors.newCachedThreadPool();
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
        log.info("TurnExecutor 提交普通 turn: threadId={}, turnId={}, providerId={}, cwd={}",
                turn.threadId(), turn.id(), providerId == null ? "<active-provider>" : providerId, cwd);
        Future<?> future = executor.submit(() -> run(turn.id(),
                () -> agentLoop.invoke(turn, userText, providerId, cwd, emitter)));
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
        log.info("TurnExecutor 提交审批恢复 turn: threadId={}, turnId={}, cwd={}",
                turn.threadId(), turn.id(), cwd);
        Future<?> future = executor.submit(() -> run(turn.id(),
                () -> agentLoop.invokeResume(turn, feedback, cwd, emitter)));
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

    private void run(String turnId, Runnable action) {
        log.info("TurnExecutor worker 开始: turnId={}", turnId);
        try {
            action.run();
        } finally {
            running.remove(turnId);
            log.info("TurnExecutor worker 结束: turnId={}", turnId);
        }
    }
}
