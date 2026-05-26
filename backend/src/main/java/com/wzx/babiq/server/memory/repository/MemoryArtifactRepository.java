package com.wzx.babiq.server.memory.repository;

import java.util.List;
import java.util.Optional;

/**
 * 长期记忆产物仓库端口。
 */
public interface MemoryArtifactRepository {

    /** 保存或更新产物记录。 */
    void save(MemoryArtifactRecord record);

    /** 查询某类产物的最新版本。 */
    Optional<MemoryArtifactRecord> findLatestByType(String artifactType);

    /** 最近产物列表。 */
    List<MemoryArtifactRecord> listLatest(int limit);
}
