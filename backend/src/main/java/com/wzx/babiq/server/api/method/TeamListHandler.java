package com.wzx.babiq.server.api.method;

import com.fasterxml.jackson.databind.JsonNode;
import com.wzx.babiq.server.agent.team.TeamMemberRecord;
import com.wzx.babiq.server.agent.team.TeamMessageRecord;
import com.wzx.babiq.server.agent.team.TeamRecord;
import com.wzx.babiq.server.agent.team.TeamRepository;
import com.wzx.babiq.server.api.JsonRpcMethodHandler;
import com.wzx.babiq.server.api.dto.TeamInfo;
import com.wzx.babiq.server.api.dto.TeamListResult;
import com.wzx.babiq.server.api.dto.TeamMemberInfo;
import com.wzx.babiq.server.api.dto.TeamMessageInfo;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.WebSocketSession;

import java.util.List;

/**
 * team/list JSON-RPC handler.
 */
@Component
public class TeamListHandler implements JsonRpcMethodHandler {

    /** 团队持久化端口。 */
    private final TeamRepository repository;

    public TeamListHandler(TeamRepository repository) {
        this.repository = repository;
    }

    @Override
    public String method() {
        return "team/list";
    }

    @Override
    public Object handle(JsonNode params, WebSocketSession session) {
        String threadId = ContextStatusHandler.requiredText(params, "threadId");
        List<TeamInfo> teams = repository.listByThreadId(threadId).stream()
                .map(team -> toInfo(team, repository.listMembers(team.teamId()).size()))
                .toList();
        return new TeamListResult(teams);
    }

    static TeamInfo toInfo(TeamRecord team, int memberCount) {
        return new TeamInfo(
                team.teamId(),
                team.threadId(),
                team.turnId(),
                team.title(),
                team.goal(),
                team.status(),
                team.cwd(),
                team.sandboxMode(),
                team.approved(),
                team.frozen(),
                team.maxRounds(),
                team.currentRound(),
                team.currentAgent(),
                team.summary(),
                team.errorMessage(),
                memberCount);
    }

    static TeamMemberInfo toMemberInfo(TeamMemberRecord member) {
        return new TeamMemberInfo(
                member.teamId(),
                member.memberId(),
                member.name(),
                member.displayName(),
                member.role(),
                member.mode(),
                member.toolNames(),
                member.status(),
                member.memberOrder(),
                member.toolCallCount(),
                member.tokenEstimate(),
                member.summary());
    }

    static TeamMessageInfo toMessageInfo(TeamMessageRecord message) {
        return new TeamMessageInfo(
                message.teamId(),
                message.messageId(),
                message.threadId(),
                message.turnId(),
                message.fromAgent(),
                message.toAgent(),
                message.messageType(),
                message.content(),
                message.routeDecisionJson(),
                message.round());
    }
}
