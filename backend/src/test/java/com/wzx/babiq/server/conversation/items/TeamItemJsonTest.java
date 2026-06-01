package com.wzx.babiq.server.conversation.items;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 团队协作协议 item 多态序列化测试。
 *
 * <p>team/teamMessage 是右侧运行详情专用协议，不能退化成普通 agentMessage。
 * 这组测试固定桌面端和后端的 JSON 字段契约。</p>
 */
class TeamItemJsonTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void team_item_should_serialize_members_and_deserialize_by_type_tag() throws Exception {
        ThreadItem item = new TeamItem(
                "it_team_1",
                "team",
                "team_1",
                "团队协作",
                "running",
                "正在协调成员",
                true,
                true,
                "explorer",
                1,
                5,
                List.of(new TeamItem.MemberStatus(
                        "member_explorer",
                        "explorer",
                        "探索成员",
                        "running",
                        "READ_ONLY_TOOL",
                        "读取目录",
                        2,
                        128,
                        "正在读取")));

        String json = objectMapper.writeValueAsString(item);
        ThreadItem restored = objectMapper.readValue(json, ThreadItem.class);

        assertThat(json)
                .contains("\"type\":\"team\"")
                .contains("\"teamId\":\"team_1\"")
                .contains("\"currentAgent\":\"explorer\"")
                .contains("\"maxRounds\":5")
                .contains("\"memberId\":\"member_explorer\"");
        assertThat(restored).isInstanceOf(TeamItem.class);
        TeamItem team = (TeamItem) restored;
        assertThat(team.members()).hasSize(1);
        assertThat(team.members().getFirst().toolCallCount()).isEqualTo(2);
    }

    @Test
    void team_message_item_should_serialize_direct_message_and_route_reason() throws Exception {
        ThreadItem item = new TeamMessageItem(
                "it_team_msg_1",
                "teamMessage",
                "msg_1",
                "team_1",
                "user",
                "explorer",
                "direct_user",
                "请重点看 README",
                2,
                "2026-06-01T10:00:00Z");

        String json = objectMapper.writeValueAsString(item);
        ThreadItem restored = objectMapper.readValue(json, ThreadItem.class);

        assertThat(json)
                .contains("\"type\":\"teamMessage\"")
                .contains("\"messageId\":\"msg_1\"")
                .contains("\"fromAgent\":\"user\"")
                .contains("\"toAgent\":\"explorer\"")
                .contains("\"messageType\":\"direct_user\"");
        assertThat(restored).isInstanceOf(TeamMessageItem.class);
        TeamMessageItem message = (TeamMessageItem) restored;
        assertThat(message.content()).contains("README");
    }
}
