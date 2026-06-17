package com.wzx.babiq.server.api.method;

import com.fasterxml.jackson.databind.JsonNode;
import com.wzx.babiq.server.agent.team.TeamMemberRecord;
import com.wzx.babiq.server.agent.team.TeamMessageRecord;
import com.wzx.babiq.server.agent.team.TeamRecord;
import com.wzx.babiq.server.agent.team.TeamRepository;
import com.wzx.babiq.server.api.JsonRpcMethodHandler;
import com.wzx.babiq.server.api.dto.TeamGetResult;
import com.wzx.babiq.server.api.dto.TeamMemberInfo;
import com.wzx.babiq.server.api.dto.TeamMessageInfo;
import com.wzx.babiq.server.api.error.JsonRpcErrorCode;
import com.wzx.babiq.server.api.error.JsonRpcException;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.WebSocketSession;

import java.util.List;

/**
 * team/get JSON-RPC handler.
 */
@Component
public class TeamGetHandler implements JsonRpcMethodHandler {

    /** 团队持久化端口。 */
    private final TeamRepository repository;

    public TeamGetHandler(TeamRepository repository) {
        this.repository = repository;
    }

    @Override
    public String method() {
        return "team/get";
    }

    @Override
    public Object handle(JsonNode params, WebSocketSession session) {
        String teamId = ContextStatusHandler.requiredText(params, "teamId");
        TeamRecord team = repository.findByTeamId(teamId)
                .orElseThrow(() -> new JsonRpcException(JsonRpcErrorCode.INVALID_PARAMS,
                        "团队不存在: " + teamId));
        List<TeamMemberRecord> members = repository.listMembers(teamId);
        List<TeamMessageRecord> messages = repository.listMessages(teamId);
        List<TeamMemberInfo> memberInfos = members.stream()
                .map(TeamListHandler::toMemberInfo)
                .toList();
        List<TeamMessageInfo> messageInfos = messages.stream()
                .map(TeamListHandler::toMessageInfo)
                .toList();
        return new TeamGetResult(TeamListHandler.toInfo(team, members.size()), memberInfos, messageInfos);
    }
}
