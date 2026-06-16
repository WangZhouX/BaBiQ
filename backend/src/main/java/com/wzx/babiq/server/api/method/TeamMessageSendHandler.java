package com.wzx.babiq.server.api.method;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wzx.babiq.server.agent.team.TeamDirectMessageService;
import com.wzx.babiq.server.api.JsonRpcMethodHandler;
import com.wzx.babiq.server.conversation.items.TeamMessageItem;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.WebSocketSession;

/**
 * team/message/send JSON-RPC handler。
 *
 * <p>该接口供桌面端右侧团队面板向某个 teammate 发送结构化直达消息；它不会把消息混入
 * 主聊天流，从而保持“主 Agent 对话”和“团队内部时间线”的边界。</p>
 */
@Component
public class TeamMessageSendHandler implements JsonRpcMethodHandler {

    /** JSON mapper 作为显式依赖，保持 handler 测试和生产序列化一致。 */
    @SuppressWarnings("unused")
    private final ObjectMapper objectMapper;
    /** 直发消息服务。 */
    private final TeamDirectMessageService service;

    public TeamMessageSendHandler(ObjectMapper objectMapper, TeamDirectMessageService service) {
        this.objectMapper = objectMapper;
        this.service = service;
    }

    @Override
    public String method() {
        return "team/message/send";
    }

    @Override
    public Object handle(JsonNode params, WebSocketSession session) {
        String teamId = ContextStatusHandler.requiredText(params, "teamId");
        String toAgent = optionalText(params, "toAgent", "leader");
        String content = ContextStatusHandler.requiredText(params, "content");
        TeamMessageItem item = service.send(teamId, toAgent, content);
        return new TeamMessageSendResult(item);
    }

    private static String optionalText(JsonNode params, String fieldName, String defaultValue) {
        if (params == null || !params.hasNonNull(fieldName) || params.get(fieldName).asText().isBlank()) {
            return defaultValue;
        }
        return params.get(fieldName).asText();
    }
}
