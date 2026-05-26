package com.wzx.babiq.server.memory.repository;

/**
 * 长期记忆读取引用仓库端口。
 */
public interface MemoryReferenceRepository {

    /** 保存一次上下文注入引用记录。 */
    void save(MemoryReferenceRecord record);
}
