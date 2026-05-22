package com.wzx.babiq.server.conversation;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.wzx.babiq.server.agent.ApprovalRequestPayload;
import com.wzx.babiq.server.api.JsonRpcMessage;
import com.wzx.babiq.server.conversation.items.ThreadItem;
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

    private final WebSocketSession session;
    private final ObjectMapper objectMapper;
    private final String threadId;
    private final String turnId;

    /**
     * 创建绑定当前 WebSocket session 的发射器。
     *
     * @param session 当前 WebSocket 连接
     * @param objectMapper JSON 序列化器
     * @param threadId 当前 Thread 标识
     * @param turnId 当前 Turn 标识
     */
    public ItemEmitter(WebSocketSession session, ObjectMapper objectMapper, String threadId, String turnId) {
        this.session = session;
        this.objectMapper = objectMapper;
        this.threadId = threadId;
        this.turnId = turnId;
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
        Map<String, Object> params = paramsWithItem(item);
        sendNotification("item/completed", params);
    }

    /**
     * 发射 turn/completed 通知。
     *
     * @param status 协议层完成状态,例如 completed / interrupted / canceled
     * @throws IOException WebSocket 写入失败时抛出
     */
    public void emitTurnCompleted(String status) throws IOException {
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
        sendNotification("approval/request", payload);
    }

    private Map<String, Object> paramsWithItem(ThreadItem item) {
        Map<String, Object> params = baseParams();
        params.put("item", item);
        return params;
    }

    private Map<String, Object> baseParams() {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("threadId", threadId);
        params.put("turnId", turnId);
        return params;
    }

    private void sendNotification(String method, Object params) throws IOException {
        JsonRpcMessage.Notification notification = JsonRpcMessage.Notification.of(method, params);
        String payload = objectMapper.writeValueAsString(notification);

        // Spring WebSocket 的 sendMessage 不是并发写安全的;异步 item 流和同步响应会共享同一 session。
        synchronized (session) {
            session.sendMessage(new TextMessage(payload));
        }
    }
}
