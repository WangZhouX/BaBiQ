package com.wzx.babiq.server.agent.team;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 团队直发消息消费水位测试。
 */
class TeamDirectMessageServiceTest {

    @Test
    void drainForMember_should_return_each_direct_message_once() {
        CapturingTeamRepository repository = new CapturingTeamRepository();
        repository.record = new TeamRecord(
                "team_direct",
                "thread_direct",
                "turn_direct",
                "团队直发",
                "验证直发消费",
                "running",
                "H:\\aaa",
                "READ_ONLY",
                true,
                true,
                4,
                1,
                "writer",
                null,
                null);
        repository.members.add(new TeamMemberRecord(
                "team_direct", "member_writer", "writer", "Writer", "writer",
                "READ_ONLY_TOOL", "read_file", "running", 1, 0, 0, null));
        TeamDirectMessageService service = new TeamDirectMessageService(repository);
        service.send("team_direct", "writer", "请重点检查 HTML 路径。");

        List<TeamMessageRecord> firstDrain = service.drainForMember("team_direct", "writer");
        List<TeamMessageRecord> secondDrain = service.drainForMember("team_direct", "writer");

        assertThat(firstDrain)
                .singleElement()
                .satisfies(message -> assertThat(message.content()).contains("HTML 路径"));
        assertThat(secondDrain).isEmpty();
    }

    private static final class CapturingTeamRepository implements TeamRepository {
        private TeamRecord record;
        private final List<TeamMemberRecord> members = new ArrayList<>();
        private final List<TeamMessageRecord> messages = new ArrayList<>();

        @Override
        public void save(TeamRecord record, List<TeamMemberRecord> members) {
            this.record = record;
            this.members.clear();
            this.members.addAll(members);
        }

        @Override
        public void updateMember(String teamId, String memberId, String status,
                                 int toolCallCount, int tokenEstimate, String summary) {
        }

        @Override
        public void saveMessage(TeamMessageRecord message) {
            messages.add(message);
        }

        @Override
        public void saveArtifact(TeamArtifactRecord artifact) {
        }

        @Override
        public Optional<TeamRecord> findByTeamId(String teamId) {
            return record == null || !record.teamId().equals(teamId) ? Optional.empty() : Optional.of(record);
        }

        @Override
        public List<TeamMemberRecord> listMembers(String teamId) {
            return members.stream()
                    .filter(member -> member.teamId().equals(teamId))
                    .toList();
        }

        @Override
        public List<TeamMessageRecord> listMessages(String teamId) {
            return messages.stream()
                    .filter(message -> message.teamId().equals(teamId))
                    .toList();
        }

        @Override
        public List<TeamArtifactRecord> listArtifacts(String teamId) {
            return List.of();
        }
    }
}
