package com.wzx.babiq.server.agent.team;

import java.util.List;
import java.util.Optional;

/**
 * 团队协作持久化端口。
 *
 * <p>Agent/工具层只依赖该端口，不直接依赖 MyBatis-Plus Mapper。SQLite 只是当前
 * 实现细节，后续如要换存储或增加索引，不影响团队运行服务。</p>
 */
public interface TeamRepository {

    /**
     * 幂等保存团队整体记录和完整成员列表。
     */
    void save(TeamRecord record, List<TeamMemberRecord> members);

    /**
     * 更新单个成员聚合状态。
     */
    void updateMember(String teamId, String memberId, String status,
                      int toolCallCount, int tokenEstimate, String summary);

    /**
     * 追加一条团队消息时间线记录。
     */
    void saveMessage(TeamMessageRecord message);

    /**
     * 追加一条团队记忆产物记录。
     */
    void saveArtifact(TeamArtifactRecord artifact);

    /**
     * 按团队 id 查询整体记录。
     */
    Optional<TeamRecord> findByTeamId(String teamId);

    /**
     * 按团队 id 查询成员列表。
     */
    List<TeamMemberRecord> listMembers(String teamId);

    /**
     * 按团队 id 查询消息时间线。
     */
    List<TeamMessageRecord> listMessages(String teamId);

    /**
     * 按团队 id 查询团队记忆产物。
     */
    List<TeamArtifactRecord> listArtifacts(String teamId);
}
