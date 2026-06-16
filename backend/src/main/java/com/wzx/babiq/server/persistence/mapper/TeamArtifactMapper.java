package com.wzx.babiq.server.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.wzx.babiq.server.persistence.entity.TeamArtifactEntity;

/**
 * `bq_team_artifacts` 的 MyBatis-Plus Mapper。
 *
 * <p>团队产物由 TeamMemoryWorkspace 机械写入，Mapper 只负责让 SQLite 事实源
 * 在 MyBatis-Plus 层可见。</p>
 */
public interface TeamArtifactMapper extends BaseMapper<TeamArtifactEntity> {
}
