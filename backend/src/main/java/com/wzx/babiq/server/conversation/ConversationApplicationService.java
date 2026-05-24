package com.wzx.babiq.server.conversation;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wzx.babiq.server.api.dto.ThreadArchiveResult;
import com.wzx.babiq.server.api.dto.ThreadListResult;
import com.wzx.babiq.server.api.dto.ThreadLoadResult;
import com.wzx.babiq.server.api.dto.ThreadMetaDto;
import com.wzx.babiq.server.api.dto.ThreadSummaryDto;
import com.wzx.babiq.server.api.error.JsonRpcErrorCode;
import com.wzx.babiq.server.api.error.JsonRpcException;
import com.wzx.babiq.server.conversation.repository.ConversationRepository;
import com.wzx.babiq.server.conversation.repository.ItemRecord;
import com.wzx.babiq.server.persistence.entity.ThreadEntity;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * 会话历史应用服务。
 *
 * <p>JSON-RPC handler 只负责参数解析，本服务负责把“最近会话、加载历史、软归档”这些业务用例组合起来。
 * 它只依赖 ConversationRepository 这样的领域边界，不直接依赖 Mapper。</p>
 */
@Service
public class ConversationApplicationService {

    /** 单次加载历史 item 的硬上限，避免 UI 一次性拉取过大的 SQLite payload。 */
    private static final int MAX_LOAD_LIMIT = 500;

    /** 对话持久化仓库，是读取历史和归档的唯一入口。 */
    private final ConversationRepository repository;
    /** 运行期会话注册表，用来判断当前 thread 是否仍有非终态 turn。 */
    private final ConversationService conversationService;
    /** 协议 item payload 解析器，负责把数据库中的 JSON 字符串还原成 JsonNode。 */
    private final ObjectMapper objectMapper;

    /**
     * 创建会话历史应用服务。
     *
     * @param repository 对话持久化仓库
     * @param conversationService 运行期会话服务
     */
    @Autowired
    public ConversationApplicationService(ConversationRepository repository, ConversationService conversationService) {
        this(repository, conversationService, new ObjectMapper());
    }

    /**
     * 测试可注入 ObjectMapper 的构造器。
     *
     * @param repository 对话持久化仓库
     * @param conversationService 运行期会话服务
     * @param objectMapper JSON 解析器
     */
    ConversationApplicationService(
            ConversationRepository repository,
            ConversationService conversationService,
            ObjectMapper objectMapper) {
        this.repository = repository;
        this.conversationService = conversationService;
        this.objectMapper = objectMapper;
    }

    /**
     * 查询最近会话列表。
     *
     * @param cwd 工作目录过滤；为空时返回所有工作区最近会话
     * @param includeArchived 是否包含软归档会话
     * @param limit 客户端请求条数，调用方应先做上限裁剪
     * @param cursor 下一页游标；P2-2 预留但暂不启用
     * @return 最近会话列表响应
     */
    public ThreadListResult listThreads(String cwd, boolean includeArchived, int limit, String cursor) {
        int safeLimit = sanitizeLimit(limit, 1, 100);
        List<ThreadSummaryDto> threads = repository.listRecentThreads(cwd, includeArchived, safeLimit).stream()
                .map(this::toSummary)
                .toList();
        return new ThreadListResult(threads, null);
    }

    /**
     * 加载一个会话的历史 item。
     *
     * @param threadId 会话 id
     * @param limit 本次最多返回多少条 item
     * @param beforeItemId 可选游标，表示只读取该 item 之前的更早历史
     * @return 会话元信息和 item 列表
     */
    public ThreadLoadResult loadThread(String threadId, int limit, String beforeItemId) {
        ThreadEntity thread = repository.findThread(threadId)
                .orElseThrow(() -> new JsonRpcException(JsonRpcErrorCode.INVALID_PARAMS, "thread 不存在: " + threadId));
        int safeLimit = sanitizeLimit(limit, 1, MAX_LOAD_LIMIT);
        List<ItemRecord> records = new ArrayList<>(repository.listItems(threadId, safeLimit + 1, beforeItemId));
        String nextBeforeItemId = null;
        if (records.size() > safeLimit) {
            // repository 返回的是“多取 1 条”的正序列表；第一条就是更早页的边界，当前页要丢弃它。
            records.remove(0);
            nextBeforeItemId = records.isEmpty() ? null : records.get(0).itemId();
        }

        List<JsonNode> items = records.stream()
                .map(this::parsePayload)
                .toList();
        JsonNode latestSummary = latestSummary(items);
        return new ThreadLoadResult(
                new ThreadMetaDto(thread.getThreadId(), thread.getTitle(), thread.getCwd(), thread.getStatus()),
                items,
                latestSummary,
                nextBeforeItemId);
    }

    /**
     * 软归档会话。
     *
     * @param threadId 要归档的会话 id
     * @return 归档结果
     */
    public ThreadArchiveResult archiveThread(String threadId) {
        repository.findThread(threadId)
                .orElseThrow(() -> new JsonRpcException(JsonRpcErrorCode.INVALID_PARAMS, "thread 不存在: " + threadId));
        if (conversationService.hasActiveTurn(threadId)) {
            throw new JsonRpcException(JsonRpcErrorCode.SERVER_ERROR, "当前 turn 仍在运行，不能归档");
        }
        repository.archiveThread(threadId, java.time.Instant.now());
        conversationService.removeThread(threadId);
        return new ThreadArchiveResult(true, threadId, true);
    }

    private ThreadSummaryDto toSummary(ThreadEntity entity) {
        return new ThreadSummaryDto(
                entity.getThreadId(),
                entity.getTitle(),
                entity.getCwd(),
                entity.getProviderId(),
                entity.getModel(),
                entity.getStatus(),
                repository.findLatestTurnStatus(entity.getThreadId()).orElse(null),
                entity.getUpdatedAt(),
                repository.countItems(entity.getThreadId()));
    }

    private JsonNode parsePayload(ItemRecord record) {
        try {
            return objectMapper.readTree(record.payloadJson());
        } catch (JsonProcessingException exception) {
            throw new JsonRpcException(JsonRpcErrorCode.SERVER_ERROR,
                    "历史 item JSON 无法解析: " + record.itemId(), exception.getOriginalMessage());
        }
    }

    private JsonNode latestSummary(List<JsonNode> items) {
        JsonNode latest = null;
        for (JsonNode item : items) {
            if ("turnSummary".equals(item.path("type").asText())) {
                latest = item;
            }
        }
        return latest;
    }

    private static int sanitizeLimit(int limit, int minimum, int maximum) {
        return Math.max(minimum, Math.min(limit, maximum));
    }
}
