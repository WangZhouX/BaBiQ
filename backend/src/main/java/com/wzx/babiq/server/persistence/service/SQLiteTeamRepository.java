package com.wzx.babiq.server.persistence.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.wzx.babiq.server.agent.team.TeamMemberRecord;
import com.wzx.babiq.server.agent.team.TeamMessageRecord;
import com.wzx.babiq.server.agent.team.TeamArtifactRecord;
import com.wzx.babiq.server.agent.team.TeamRecord;
import com.wzx.babiq.server.agent.team.TeamRepository;
import com.wzx.babiq.server.persistence.entity.TeamArtifactEntity;
import com.wzx.babiq.server.persistence.entity.TeamEntity;
import com.wzx.babiq.server.persistence.entity.TeamMemberEntity;
import com.wzx.babiq.server.persistence.entity.TeamMessageEntity;
import com.wzx.babiq.server.persistence.mapper.TeamArtifactMapper;
import com.wzx.babiq.server.persistence.mapper.TeamMapper;
import com.wzx.babiq.server.persistence.mapper.TeamMemberMapper;
import com.wzx.babiq.server.persistence.mapper.TeamMessageMapper;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * SQLite 版团队协作仓储。
 *
 * <p>它把团队领域 record 映射到 MyBatis-Plus Entity，并负责成员状态和消息时间线
 * 的幂等写入。Agent/工具层只依赖 {@link TeamRepository} 端口。</p>
 */
@Repository
public class SQLiteTeamRepository implements TeamRepository {

    /** 团队整体运行表 mapper。 */
    private final TeamMapper teamMapper;
    /** 团队成员表 mapper。 */
    private final TeamMemberMapper memberMapper;
    /** 团队消息表 mapper。 */
    private final TeamMessageMapper messageMapper;
    /** 团队记忆产物表 mapper。 */
    private final TeamArtifactMapper artifactMapper;

    /**
     * 创建 SQLite 团队仓储。
     */
    public SQLiteTeamRepository(TeamMapper teamMapper,
                                TeamMemberMapper memberMapper,
                                TeamMessageMapper messageMapper,
                                TeamArtifactMapper artifactMapper) {
        this.teamMapper = teamMapper;
        this.memberMapper = memberMapper;
        this.messageMapper = messageMapper;
        this.artifactMapper = artifactMapper;
    }

    @Override
    @Transactional
    public void save(TeamRecord record, List<TeamMemberRecord> members) {
        String now = PersistenceTime.write(Instant.now());
        TeamEntity existing = findEntity(record.teamId());
        TeamEntity entity = toEntity(record);
        entity.setCreatedAt(existing == null ? now : existing.getCreatedAt());
        entity.setUpdatedAt(now);
        if (existing == null) {
            teamMapper.insert(entity);
        } else {
            entity.setId(existing.getId());
            teamMapper.updateById(entity);
        }
        memberMapper.delete(Wrappers.<TeamMemberEntity>lambdaQuery()
                .eq(TeamMemberEntity::getTeamId, record.teamId()));
        for (TeamMemberRecord member : members == null ? List.<TeamMemberRecord>of() : members) {
            TeamMemberEntity memberEntity = toEntity(member);
            memberEntity.setCreatedAt(now);
            memberEntity.setUpdatedAt(now);
            memberMapper.insert(memberEntity);
        }
    }

    @Override
    @Transactional
    public void updateMember(String teamId, String memberId, String status,
                             int toolCallCount, int tokenEstimate, String summary) {
        TeamMemberEntity entity = memberMapper.selectOne(Wrappers.<TeamMemberEntity>lambdaQuery()
                .eq(TeamMemberEntity::getTeamId, teamId)
                .eq(TeamMemberEntity::getMemberId, memberId));
        if (entity == null) {
            return;
        }
        entity.setStatus(status);
        entity.setToolCallCount(toolCallCount);
        entity.setTokenEstimate(tokenEstimate);
        entity.setSummary(summary);
        entity.setUpdatedAt(PersistenceTime.write(Instant.now()));
        memberMapper.updateById(entity);
    }

    @Override
    public void saveMessage(TeamMessageRecord message) {
        TeamMessageEntity existing = messageMapper.selectOne(Wrappers.<TeamMessageEntity>lambdaQuery()
                .eq(TeamMessageEntity::getMessageId, message.messageId()));
        if (existing != null) {
            return;
        }
        TeamMessageEntity entity = toEntity(message);
        entity.setCreatedAt(PersistenceTime.write(Instant.now()));
        messageMapper.insert(entity);
    }

    @Override
    public void saveArtifact(TeamArtifactRecord artifact) {
        TeamArtifactEntity existing = artifactMapper.selectOne(Wrappers.<TeamArtifactEntity>lambdaQuery()
                .eq(TeamArtifactEntity::getArtifactId, artifact.artifactId()));
        if (existing != null) {
            return;
        }
        artifactMapper.insert(toEntity(artifact));
    }

    @Override
    public Optional<TeamRecord> findByTeamId(String teamId) {
        return Optional.ofNullable(findEntity(teamId)).map(this::toRecord);
    }

    @Override
    public List<TeamMemberRecord> listMembers(String teamId) {
        return memberMapper.selectList(Wrappers.<TeamMemberEntity>lambdaQuery()
                        .eq(TeamMemberEntity::getTeamId, teamId)
                        .orderByAsc(TeamMemberEntity::getMemberOrder))
                .stream()
                .map(this::toRecord)
                .toList();
    }

    @Override
    public List<TeamMessageRecord> listMessages(String teamId) {
        return messageMapper.selectList(Wrappers.<TeamMessageEntity>lambdaQuery()
                        .eq(TeamMessageEntity::getTeamId, teamId)
                        .orderByAsc(TeamMessageEntity::getRound)
                        .orderByAsc(TeamMessageEntity::getCreatedAt))
                .stream()
                .map(this::toRecord)
                .toList();
    }

    @Override
    public List<TeamArtifactRecord> listArtifacts(String teamId) {
        return artifactMapper.selectList(Wrappers.<TeamArtifactEntity>lambdaQuery()
                        .eq(TeamArtifactEntity::getTeamId, teamId)
                        .orderByAsc(TeamArtifactEntity::getCreatedAt))
                .stream()
                .map(this::toRecord)
                .toList();
    }

    private TeamEntity findEntity(String teamId) {
        return teamMapper.selectOne(Wrappers.<TeamEntity>lambdaQuery()
                .eq(TeamEntity::getTeamId, teamId));
    }

    private TeamEntity toEntity(TeamRecord record) {
        TeamEntity entity = new TeamEntity();
        entity.setTeamId(record.teamId());
        entity.setThreadId(record.threadId());
        entity.setTurnId(record.turnId());
        entity.setTitle(record.title());
        entity.setGoal(record.goal());
        entity.setStatus(record.status());
        entity.setCwd(record.cwd());
        entity.setSandboxMode(record.sandboxMode());
        entity.setApproved(record.approved() ? 1 : 0);
        entity.setFrozen(record.frozen() ? 1 : 0);
        entity.setMaxRounds(record.maxRounds());
        entity.setCurrentRound(record.currentRound());
        entity.setCurrentAgent(record.currentAgent());
        entity.setSummary(record.summary());
        entity.setErrorMessage(record.errorMessage());
        return entity;
    }

    private TeamMemberEntity toEntity(TeamMemberRecord record) {
        TeamMemberEntity entity = new TeamMemberEntity();
        entity.setTeamId(record.teamId());
        entity.setMemberId(record.memberId());
        entity.setName(record.name());
        entity.setDisplayName(record.displayName());
        entity.setRole(record.role());
        entity.setMode(record.mode());
        entity.setToolNames(record.toolNames());
        entity.setStatus(record.status());
        entity.setMemberOrder(record.memberOrder());
        entity.setToolCallCount(record.toolCallCount());
        entity.setTokenEstimate(record.tokenEstimate());
        entity.setSummary(record.summary());
        return entity;
    }

    private TeamMessageEntity toEntity(TeamMessageRecord record) {
        TeamMessageEntity entity = new TeamMessageEntity();
        entity.setTeamId(record.teamId());
        entity.setMessageId(record.messageId());
        entity.setThreadId(record.threadId());
        entity.setTurnId(record.turnId());
        entity.setFromAgent(record.fromAgent());
        entity.setToAgent(record.toAgent());
        entity.setMessageType(record.messageType());
        entity.setContent(record.content());
        entity.setRouteDecisionJson(record.routeDecisionJson());
        entity.setRound(record.round());
        return entity;
    }

    private TeamArtifactEntity toEntity(TeamArtifactRecord record) {
        TeamArtifactEntity entity = new TeamArtifactEntity();
        entity.setTeamId(record.teamId());
        entity.setArtifactId(record.artifactId());
        entity.setArtifactType(record.artifactType());
        entity.setRelativePath(record.relativePath());
        entity.setSha256(record.sha256());
        entity.setTokenEstimate(record.tokenEstimate());
        entity.setRound(record.round());
        entity.setMemberName(record.memberName());
        entity.setContent(record.content());
        entity.setCreatedAt(PersistenceTime.write(record.createdAt()));
        entity.setUpdatedAt(PersistenceTime.write(record.updatedAt()));
        return entity;
    }

    private TeamRecord toRecord(TeamEntity entity) {
        return new TeamRecord(
                entity.getTeamId(),
                entity.getThreadId(),
                entity.getTurnId(),
                entity.getTitle(),
                entity.getGoal(),
                entity.getStatus(),
                entity.getCwd(),
                entity.getSandboxMode(),
                entity.getApproved() != null && entity.getApproved() == 1,
                entity.getFrozen() != null && entity.getFrozen() == 1,
                entity.getMaxRounds() == null ? 0 : entity.getMaxRounds(),
                entity.getCurrentRound() == null ? 0 : entity.getCurrentRound(),
                entity.getCurrentAgent(),
                entity.getSummary(),
                entity.getErrorMessage());
    }

    private TeamMemberRecord toRecord(TeamMemberEntity entity) {
        return new TeamMemberRecord(
                entity.getTeamId(),
                entity.getMemberId(),
                entity.getName(),
                entity.getDisplayName(),
                entity.getRole(),
                entity.getMode(),
                entity.getToolNames(),
                entity.getStatus(),
                entity.getMemberOrder() == null ? 0 : entity.getMemberOrder(),
                entity.getToolCallCount() == null ? 0 : entity.getToolCallCount(),
                entity.getTokenEstimate() == null ? 0 : entity.getTokenEstimate(),
                entity.getSummary());
    }

    private TeamMessageRecord toRecord(TeamMessageEntity entity) {
        return new TeamMessageRecord(
                entity.getTeamId(),
                entity.getMessageId(),
                entity.getThreadId(),
                entity.getTurnId(),
                entity.getFromAgent(),
                entity.getToAgent(),
                entity.getMessageType(),
                entity.getContent(),
                entity.getRouteDecisionJson(),
                entity.getRound() == null ? 0 : entity.getRound());
    }

    private TeamArtifactRecord toRecord(TeamArtifactEntity entity) {
        return new TeamArtifactRecord(
                entity.getTeamId(),
                entity.getArtifactId(),
                entity.getArtifactType(),
                entity.getRelativePath(),
                entity.getSha256(),
                entity.getTokenEstimate() == null ? 0 : entity.getTokenEstimate(),
                entity.getRound() == null ? 0 : entity.getRound(),
                entity.getMemberName(),
                entity.getContent(),
                PersistenceTime.read(entity.getCreatedAt()),
                PersistenceTime.read(entity.getUpdatedAt()));
    }
}
