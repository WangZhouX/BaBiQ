package com.wzx.babiq.server.agent.team;

import com.wzx.babiq.server.conversation.items.TeamMessageItem;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 用户直发 teammate 消息服务。
 *
 * <p>直接消息不会伪造成普通用户聊天输入，而是写入团队消息时间线，并以
 * {@code teamMessage} 协议 item 返回给桌面端。后续如果要唤醒具体 teammate，
 * 也应从这里读取 team 记录和沙箱快照后再进入官方 Agent 图。</p>
 */
@Service
public class TeamDirectMessageService {

    /** 团队协作持久化端口。 */
    private final TeamRepository repository;
    /** 已被运行循环消费的直接消息 id。 */
    private final Set<String> consumedMessageIds = ConcurrentHashMap.newKeySet();

    /**
     * 创建直发消息服务。
     */
    public TeamDirectMessageService(TeamRepository repository) {
        this.repository = repository;
    }

    /**
     * 保存一条用户发给指定 teammate 的结构化消息。
     */
    public TeamMessageItem send(String teamId, String toAgent, String content) {
        TeamRecord team = repository.findByTeamId(teamId)
                .orElseThrow(() -> new IllegalArgumentException("团队不存在: " + teamId));
        boolean memberExists = repository.listMembers(teamId).stream()
                .anyMatch(member -> member.name().equals(toAgent) || member.memberId().equals(toAgent));
        if (!memberExists) {
            throw new IllegalArgumentException("团队成员不存在: " + toAgent);
        }
        String messageId = "msg_team_" + UUID.randomUUID().toString().replace("-", "").substring(0, 10);
        TeamMessageRecord record = new TeamMessageRecord(
                teamId,
                messageId,
                team.threadId(),
                team.turnId(),
                "user",
                toAgent,
                "direct_user",
                content == null ? "" : content,
                null,
                Math.max(team.currentRound(), 0));
        repository.saveMessage(record);
        return new TeamMessageItem(
                "it_" + messageId,
                "teamMessage",
                messageId,
                teamId,
                "user",
                toAgent,
                "direct_user",
                content == null ? "" : content,
                Math.max(team.currentRound(), 0),
                Instant.now().toString());
    }

    /**
     * 取出发给某成员且尚未注入过的用户消息。
     */
    public List<TeamMessageRecord> drainForMember(String teamId, String memberName) {
        return repository.listMessages(teamId).stream()
                .filter(message -> "direct_user".equals(message.messageType()))
                .filter(message -> memberName != null && memberName.equals(message.toAgent()))
                .filter(message -> consumedMessageIds.add(message.messageId()))
                .toList();
    }
}
