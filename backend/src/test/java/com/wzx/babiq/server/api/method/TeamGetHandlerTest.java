package com.wzx.babiq.server.api.method;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.wzx.babiq.server.agent.team.TeamMemberRecord;
import com.wzx.babiq.server.agent.team.TeamMessageRecord;
import com.wzx.babiq.server.agent.team.TeamRecord;
import com.wzx.babiq.server.agent.team.TeamRepository;
import com.wzx.babiq.server.api.dto.TeamGetResult;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * team/get JSON-RPC read path tests.
 */
class TeamGetHandlerTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void get_should_return_selected_team_members_and_timeline() {
        TeamRepository repository = mock(TeamRepository.class);
        when(repository.findByTeamId("team_1")).thenReturn(Optional.of(new TeamRecord(
                "team_1", "thr_team", "turn_team", "Review Team", "Check files",
                "running", "H:/aaa", "WORKSPACE_WRITE", true, true,
                6, 3, "reviewer", "All members reported", null)));
        when(repository.listMembers("team_1")).thenReturn(List.of(new TeamMemberRecord(
                "team_1", "member_reviewer", "reviewer", "Reviewer", "reviewer",
                "READ_ONLY_TOOL", "read_file,list_dir", "completed", 1,
                2, 256, "Checked index.html")));
        when(repository.listMessages("team_1")).thenReturn(List.of(new TeamMessageRecord(
                "team_1", "msg_1", "thr_team", "turn_team", "supervisor", "reviewer",
                "route", "Please verify the output", "{\"next\":\"reviewer\"}", 3)));

        Object result = new TeamGetHandler(repository)
                .handle(objectMapper.valueToTree(Map.of("teamId", "team_1")), null);

        assertThat(result).isInstanceOf(TeamGetResult.class);
        TeamGetResult detail = (TeamGetResult) result;
        assertThat(detail.team().teamId()).isEqualTo("team_1");
        assertThat(detail.team().currentRound()).isEqualTo(3);
        assertThat(detail.members()).singleElement().satisfies(member -> {
            assertThat(member.name()).isEqualTo("reviewer");
            assertThat(member.summary()).contains("index.html");
        });
        assertThat(detail.messages()).singleElement().satisfies(message -> {
            assertThat(message.messageId()).isEqualTo("msg_1");
            assertThat(message.messageType()).isEqualTo("route");
            assertThat(message.routeDecisionJson()).contains("reviewer");
        });
    }
}
