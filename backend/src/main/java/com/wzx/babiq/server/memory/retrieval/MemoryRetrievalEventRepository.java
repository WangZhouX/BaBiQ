package com.wzx.babiq.server.memory.retrieval;

/**
 * 长期记忆检索事件仓库端口。
 */
public interface MemoryRetrievalEventRepository {

    /** 保存一次检索审计。 */
    void save(MemoryRetrievalEventRecord record);
}
