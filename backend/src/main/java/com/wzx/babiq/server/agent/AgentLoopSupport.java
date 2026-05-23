package com.wzx.babiq.server.agent;

import com.wzx.babiq.server.conversation.ItemEmitter;
import com.wzx.babiq.server.conversation.Turn;
import com.wzx.babiq.server.observability.TurnObservationContext;
import com.wzx.babiq.server.observability.TurnObservationRegistry;
import com.wzx.babiq.server.observability.TurnSummaryEmitter;
import org.slf4j.Logger;

import java.util.UUID;
import java.util.concurrent.CancellationException;

/**
 * AgentLoop 的支撑工具。
 *
 * <p>把失败收尾、中断判断和 item id 生成从主流程中拆开，保证 AgentLoop 文件维持 D21
 * 的薄编排约束。它只给同包内的 AgentLoop 使用。</p>
 */
final class AgentLoopSupport {

    private AgentLoopSupport() {
    }

    /**
     * 生成 item id。
     *
     * @return 以 it_ 开头的随机 item id
     */
    static String newItemId() {
        return "it_" + UUID.randomUUID().toString().replace("-", "").substring(0, 10);
    }

    /**
     * 处理失败或中断的 turn 收尾。
     *
     * @param logger 日志对象
     * @param turn 当前 turn
     * @param emitter 当前 WebSocket 发射器
     * @param exception 失败异常
     * @param summaryEmitter turn 摘要发射器
     * @param context 当前 turn 观测上下文
     * @param observationRegistry 观测上下文缓存
     */
    static void fail(Logger logger,
                     Turn turn,
                     ItemEmitter emitter,
                     Exception exception,
                     TurnSummaryEmitter summaryEmitter,
                     TurnObservationContext context,
                     TurnObservationRegistry observationRegistry) {
        logger.error("AgentLoop 执行失败 turnId={}", turn.id(), exception);
        if (isInterrupted(exception) || Thread.currentThread().isInterrupted()) {
            try {
                if (!turn.status().isTerminal()) {
                    turn.cancel();
                }
                emitSummary(logger, turn, emitter, summaryEmitter, context, "interrupted");
                observationRegistry.remove(turn.id());
                emitter.emitTurnCompleted("interrupted");
            } catch (Exception sendException) {
                logger.error("发送 turn/completed(interrupted) 失败 turnId={}", turn.id(), sendException);
            }
            return;
        }
        if (!turn.status().isTerminal()) {
            turn.fail(exception.getMessage());
        }
        try {
            emitSummary(logger, turn, emitter, summaryEmitter, context, "failed");
            observationRegistry.remove(turn.id());
            emitter.emitTurnFailed(exception.getMessage());
        } catch (Exception sendException) {
            logger.error("发送 turn/failed 失败 turnId={}", turn.id(), sendException);
        }
    }

    private static void emitSummary(Logger logger,
                                    Turn turn,
                                    ItemEmitter emitter,
                                    TurnSummaryEmitter summaryEmitter,
                                    TurnObservationContext context,
                                    String status) {
        try {
            summaryEmitter.emit(context, emitter, status);
        } catch (Exception exception) {
            logger.warn("发送 turnSummary 失败 turnId={},status={}", turn.id(), status, exception);
        }
    }

    /**
     * 判断异常是否代表中断。
     *
     * @param exception 失败异常
     * @return true 表示中断/取消
     */
    static boolean isInterrupted(Exception exception) {
        Throwable current = exception;
        while (current != null) {
            if (current instanceof InterruptedException || current instanceof CancellationException) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }
}
