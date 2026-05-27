package com.wzx.babiq.server.capability;

import java.util.List;
import java.util.Optional;

/**
 * 能力目录仓库端口。
 *
 * <p>Agent 运行层只依赖该端口，不直接碰 MyBatis Mapper 或 JDBC。这样 P3-5 后续如果把
 * 搜索实现换成 Lucene/VectorStore，也不会破坏能力事实源。</p>
 */
public interface CapabilityRepository {

    /** 保存或更新一个能力元数据。 */
    void upsert(CapabilityDescriptor descriptor);

    /** 批量保存能力元数据。 */
    default void upsertAll(List<CapabilityDescriptor> descriptors) {
        if (descriptors == null) {
            return;
        }
        for (CapabilityDescriptor descriptor : descriptors) {
            upsert(descriptor);
        }
    }

    /** 查询所有能力。 */
    List<CapabilityDescriptor> listAll();

    /** 查询启用能力。 */
    List<CapabilityDescriptor> listEnabled();

    /** 按 id 查询能力。 */
    Optional<CapabilityDescriptor> findById(String capabilityId);

    /** 局部更新用户开关和暴露模式。 */
    void updateSettings(String capabilityId, Boolean enabled, CapabilityExposureMode exposureMode);

    /** 保存一次搜索审计。 */
    void recordSearchEvent(CapabilitySearchEventRecord record);

    /** 查询某 thread 最近搜索命中过的能力 id，用于下一轮 conservative 暴露。 */
    List<String> recentSelectedCapabilityIds(String threadId, int limit);
}
