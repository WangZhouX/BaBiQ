package com.wzx.babiq.server.api.method;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.wzx.babiq.server.agent.team.TeamDirectMessageService;
import com.wzx.babiq.server.conversation.items.TeamMessageItem;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * team/message/send JSON-RPC 入口测试。
 *
 * <p>用户在右侧团队面板里给某个 teammate 发消息时，不应该伪造成普通聊天输入；
 * 后端需要保留 teamId/toAgent/content 的结构化边界。</p>
 */
class TeamMessageSendHandlerTest {

    @Test
    void handler_should_send_direct_message_to_named_teammate() throws Exception {
        TeamDirectMessageService service = mock(TeamDirectMessageService.class);
        TeamMessageSendHandler handler = new TeamMessageSendHandler(new ObjectMapper(), service);
        TeamMessageItem item = new TeamMessageItem(
                "it_msg",
                "teamMessage",
                "msg_1",
                "team_1",
                "user",
                "explorer",
                "direct_user",
                "请重点看 README",
                2,
                "2026-06-01T10:00:00Z");
        when(service.send("team_1", "explorer", "请重点看 README")).thenReturn(item);

        Object result = handler.handle(new ObjectMapper().readTree("""
                {"teamId":"team_1","toAgent":"explorer","content":"请重点看 README"}
                """), null);

        assertThat(result).isInstanceOf(TeamMessageSendResult.class);
        TeamMessageSendResult response = (TeamMessageSendResult) result;
        assertThat(response.item().toAgent()).isEqualTo("explorer");
        verify(service).send("team_1", "explorer", "请重点看 README");
    }
}
