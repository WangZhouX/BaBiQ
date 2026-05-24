package com.wzx.babiq.server.conversation;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.wzx.babiq.server.agent.ApprovalRequestPayload;
import com.wzx.babiq.server.api.JsonRpcLogSupport;
import com.wzx.babiq.server.api.JsonRpcMessage;
import com.wzx.babiq.server.conversation.items.ThreadItem;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 单个 WebSocket 会话的 item/turn 通知发射器。
 *
 * <p>P1-1 的协议流只面向当前连接发送 notification。该类把 turnId/threadId
 * 基础参数统一注入,并封装 session 写入同步,让 handler 只关心业务事件顺序。</p>
 */
public class ItemEmitter {

    private static final Logger log = LoggerFactory.getLogger(ItemEmitter.class);

    /** 当前前端连接的 WebSocket 会话，所有 item/turn 通知都会通过它发回桌面端。 */
    private final WebSocketSession session;
    /** 通知 payload 的 JSON 序列化器，保证后端事件和桌面端协议模型字段一致。 */
    private final ObjectMapper objectMapper;
    /** 当前对话线程 id，事件 params 必须带上它，前端 reducer 才能归属到正确会话。 */
    private final String threadId;
    /** 当前执行轮次 id，item/summary/approval 都需要用它关联到同一轮请求。 */
    private final String turnId;
    /** 可选持久化记录器；生产环境先写 SQLite，再发 WebSocket，单元测试可为空。 */
    private final ConversationEventRecorder recorder;

    /**
     * 创建绑定当前 WebSocket session 的发射器。
     *
     * @param session 当前 WebSocket 连接
     * @param objectMapper JSON 序列化器
     * @param threadId 当前 Thread 标识
     * @param turnId 当前 Turn 标识
     */
    public ItemEmitter(WebSocketSession session, ObjectMapper objectMapper, String threadId, String turnId) {
        this(session, objectMapper, threadId, turnId, null);
    }

    /**
     * 创建绑定当前 WebSocket session 且带持久化记录器的发射器。
     *
     * @param session 当前 WebSocket 连接
     * @param objectMapper JSON 序列化器
     * @param threadId 当前 Thread 标识
     * @param turnId 当前 Turn 标识
     * @param recorder 运行事件记录器；为空时只发送 WebSocket
     */
    public ItemEmitter(WebSocketSession session,
                       ObjectMapper objectMapper,
                       String threadId,
                       String turnId,
                       ConversationEventRecorder recorder) {
        this.session = session;
        this.objectMapper = objectMapper;
        this.threadId = threadId;
        this.turnId = turnId;
        this.recorder = recorder;
    }

    /**
     * 发射 turn/started 通知。
     *
     * @throws IOException WebSocket 写入失败时抛出
     */
    public void emitTurnStarted() throws IOException {
        sendNotification("turn/started", baseParams());
    }

    /**
     * 发射 item/added 通知。
     *
     * @param item 新增的 ThreadItem
     * @throws IOException WebSocket 写入失败时抛出
     */
    public void emitItemAdded(ThreadItem item) throws IOException {
        if (recorder != null) {
            if (item instanceof com.wzx.babiq.server.conversation.items.TurnSummaryItem summaryItem) {
                recorder.recordTurnSummary(threadId, turnId, summaryItem);
            } else {
                recorder.recordItemAdded(threadId, turnId, item);
            }
        }
        Map<String, Object> params = paramsWithItem(item);
        sendNotification("item/added", params);
    }

    /**
     * 发射 item/updated 通知。
     *
     * @param item 被更新的 ThreadItem
     * @throws IOException WebSocket 写入失败时抛出
     */
    public void emitItemUpdated(ThreadItem item) throws IOException {
        if (recorder != null) {
            recorder.recordItemUpdated(threadId, turnId, item);
        }
        Map<String, Object> params = paramsWithItem(item);
        sendNotification("item/updated", params);
    }

    /**
     * 发射 item/completed 通知。
     *
     * @param item 已完成的 ThreadItem
     * @throws IOException WebSocket 写入失败时抛出
     */
    public void emitItemCompleted(ThreadItem item) throws IOException {
        if (recorder != null) {
            recorder.recordItemCompleted(threadId, turnId, item);
        }
        Map<String, Object> params = paramsWithItem(item);
        sendNotification("item/completed", params);
    }

    /**
     * 发射命令执行 item。
     *
     * @param item 命令执行 item
     * @throws IOException WebSocket 写入失败时抛出
     */
    public void emitCommandExecution(ThreadItem item) throws IOException {
        emitItemAdded(item);
    }

    /**
     * 发射文件变更 item。
     *
     * @param item 文件变更 item
     * @throws IOException WebSocket 写入失败时抛出
     */
    public void emitFileChange(ThreadItem item) throws IOException {
        emitItemAdded(item);
    }

    /**
     * 发射推理摘要 item。
     *
     * @param item 推理摘要 item
     * @throws IOException WebSocket 写入失败时抛出
     */
    public void emitReasoning(ThreadItem item) throws IOException {
        emitItemAdded(item);
    }

    /**
     * 发射单轮执行摘要 item。
     *
     * @param item turnSummary item
     * @throws IOException WebSocket 写入失败时抛出
     */
    public void emitTurnSummary(ThreadItem item) throws IOException {
        emitItemAdded(item);
    }

    /**
     * 发射 turn/completed 通知。
     *
     * @param status 协议层完成状态,例如 completed / interrupted / canceled
     * @throws IOException WebSocket 写入失败时抛出
     */
    public void emitTurnCompleted(String status) throws IOException {
        if (recorder != null) {
            recorder.recordTurnFinished(turnId, databaseTurnStatus(status), null);
        }
        Map<String, Object> params = baseParams();
        params.put("status", status);
        sendNotification("turn/completed", params);
    }

    /**
     * 发射 turn/failed 通知。
     *
     * @param reason 失败原因
     * @throws IOException WebSocket 写入失败时抛出
     */
    public void emitTurnFailed(String reason) throws IOException {
        if (recorder != null) {
            recorder.recordTurnFinished(turnId, "FAILED", reason);
        }
        Map<String, Object> params = baseParams();
        params.put("reason", reason);
        sendNotification("turn/failed", params);
    }

    /**
     * 发射审批请求通知。
     *
     * @param payload 审批请求载荷
     * @throws IOException WebSocket 写入失败时抛出
     */
    public void emitApprovalRequest(ApprovalRequestPayload payload) throws IOException {
        if (recorder != null) {
            recorder.recordApprovalRequest(payload);
        }
        sendNotification("approval/request", payload);
    }

    /**
     * 在基础 thread/turn 参数上追加 item 字段。
     */
    private Map<String, Object> paramsWithItem(ThreadItem item) {
        Map<String, Object> params = baseParams();
        params.put("item", item);
        return params;
    }

    /**
     * 所有 turn/item notification 都共享的基础参数。
     */
    private Map<String, Object> baseParams() {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("threadId", threadId);
        params.put("turnId", turnId);
        return params;
    }

    /**
     * 序列化并发送 JSON-RPC notification。
     */
    private void sendNotification(String method, Object params) throws IOException {
        JsonRpcMessage.Notification notification = JsonRpcMessage.Notification.of(method, params);
        String payload = objectMapper.writeValueAsString(notification);

        // Spring WebSocket 的 sendMessage 不是并发写安全的;异步 item 流和同步响应会共享同一 session。
        synchronized (session) {
            session.sendMessage(new TextMessage(payload));
        }
        log.info("WebSocket 通知已发送: sessionId={}, method={}, threadId={}, turnId={}, params={}",
                session.getId(),
                method,
                threadId,
                turnId,
                JsonRpcLogSupport.paramsSummary(objectMapper.valueToTree(params)));
    }

    private String databaseTurnStatus(String protocolStatus) {
        String normalized = protocolStatus == null ? "completed" : protocolStatus.toLowerCase();
        return switch (normalized) {
            case "canceled", "cancelled" -> "CANCELED";
            case "interrupted" -> "INTERRUPTED";
            case "expired" -> "EXPIRED";
            case "failed" -> "FAILED";
            default -> "COMPLETED";
        };
    }
}
