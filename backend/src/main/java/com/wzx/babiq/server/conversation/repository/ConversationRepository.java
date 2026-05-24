package com.wzx.babiq.server.conversation.repository;

import com.wzx.babiq.server.persistence.entity.ThreadEntity;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * 对话持久化的领域仓库接口。
 *
 * <p>运行层只依赖这个接口，而不是直接依赖 MyBatis Mapper。这样 P2-1 可以先落 SQLite，
 * 后续如果切换存储实现或增加缓存，也不需要改 JSON-RPC handler 和 AgentLoop。</p>
 */
public interface ConversationRepository {

    /**
     * 创建一个新的会话线程。
     *
     * @return 已保存的 thread 实体，供调用方继续读取数据库生成的内部字段
     */
    ThreadEntity createThread(
            String threadId,
            String title,
            String cwd,
            String providerId,
            String model,
            String sandboxMode,
            String approvalPolicy,
            Instant now);

    /**
     * 按协议层 threadId 查询会话。
     *
     * @param threadId 协议层会话标识
     * @return 找到时返回 thread，否则为空
     */
    Optional<ThreadEntity> findThread(String threadId);

    /**
     * 查询最近会话。
     *
     * @param cwd 工作目录过滤；为空时表示不过滤目录
     * @param includeArchived 是否包含软归档会话
     * @param limit 最大返回数量
     * @return 按 updatedAt 倒序排列的会话列表
     */
    List<ThreadEntity> listRecentThreads(String cwd, boolean includeArchived, int limit);

    /**
     * 软归档会话，让默认最近列表不再显示它。
     *
     * @param threadId 要归档的会话标识
     * @param archivedAt 归档时间
     */
    void archiveThread(String threadId, Instant archivedAt);

    /**
     * 保存或更新一个协议 item。
     *
     * @param record item 领域记录
     */
    void saveItem(ItemRecord record);

    /**
     * 按会话读取 item。
     *
     * @param threadId 会话标识
     * @param limit 最大返回数量
     * @return 按 sequenceNo 正序排列的 item 记录
     */
    List<ItemRecord> listItems(String threadId, int limit);

    /**
     * 按会话读取 item，并支持向更早历史分页。
     *
     * @param threadId 会话标识
     * @param limit 最大返回数量
     * @param beforeItemId 可选游标；非空时只返回该 item 之前的记录
     * @return 按 sequenceNo 正序排列的 item 记录
     */
    List<ItemRecord> listItems(String threadId, int limit, String beforeItemId);

    /**
     * 统计会话内已经保存的 item 数量。
     *
     * @param threadId 会话标识
     * @return item 数量
     */
    long countItems(String threadId);

    /**
     * 查询会话最近一轮 turn 的状态。
     *
     * @param threadId 会话标识
     * @return 找到 turn 时返回状态，否则为空
     */
    Optional<String> findLatestTurnStatus(String threadId);

    /**
     * 保存或更新 turn 摘要。
     *
     * @param record 摘要记录
     */
    void saveTurnSummary(TurnSummaryRecord record);

    /**
     * 按 turnId 查询摘要。
     *
     * @param turnId 运行回合标识
     * @return 找到时返回摘要，否则为空
     */
    Optional<TurnSummaryRecord> findTurnSummary(String turnId);
}
