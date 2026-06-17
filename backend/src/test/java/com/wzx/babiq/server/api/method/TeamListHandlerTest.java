package com.wzx.babiq.server.api.method;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.wzx.babiq.server.agent.team.TeamMemberRecord;
import com.wzx.babiq.server.agent.team.TeamRecord;
import com.wzx.babiq.server.agent.team.TeamRepository;
import com.wzx.babiq.server.api.dto.TeamListResult;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * team/list JSON-RPC read path tests.
 */
class TeamListHandlerTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void list_should_return_thread_teams_with_status_round_and_member_count() {
        TeamRepository repository = mock(TeamRepository.class);
        when(repository.listByThreadId("thr_team")).thenReturn(List.of(
                team("team_1", "running", 2, "writer"),
                team("team_2", "completed", 5, null)));
        when(repository.listMembers("team_1")).thenReturn(List.of(
                member("team_1", "explorer", 1),
                member("team_1", "writer", 2)));
        when(repository.listMembers("team_2")).thenReturn(List.of(member("team_2", "reviewer", 1)));

        Object result = new TeamListHandler(repository)
                .handle(objectMapper.valueToTree(Map.of("threadId", "thr_team")), null);

        assertThat(result).isInstanceOf(TeamListResult.class);
        TeamListResult list = (TeamListResult) result;
        assertThat(list.teams()).hasSize(2);
        assertThat(list.teams()).extracting("teamId").containsExactly("team_1", "team_2");
        assertThat(list.teams().getFirst().status()).isEqualTo("running");
        assertThat(list.teams().getFirst().currentRound()).isEqualTo(2);
        assertThat(list.teams().getFirst().currentAgent()).isEqualTo("writer");
        assertThat(list.teams().getFirst().memberCount()).isEqualTo(2);
        assertThat(list.teams().get(1).status()).isEqualTo("completed");
        assertThat(list.teams().get(1).memberCount()).isEqualTo(1);
    }

    private static TeamRecord team(String teamId, String status, int round, String currentAgent) {
        return new TeamRecord(teamId, "thr_team", "turn_" + teamId, "Team " + teamId,
                "Inspect workspace", status, "H:/aaa", "WORKSPACE_WRITE",
                true, true, 6, round, currentAgent, "Summary " + teamId, null);
    }

    private static TeamMemberRecord member(String teamId, String name, int order) {
        return new TeamMemberRecord(teamId, "member_" + name, name, name,
                name, "READ_ONLY_TOOL", "read_file", "pending",
                order, 0, 0, null);
    }
}
