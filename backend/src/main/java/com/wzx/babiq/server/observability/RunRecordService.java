package com.wzx.babiq.server.observability;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.wzx.babiq.server.api.dto.RunApprovalDto;
import com.wzx.babiq.server.api.dto.RunToolCallDto;
import com.wzx.babiq.server.api.dto.RunTurnDetailResult;
import com.wzx.babiq.server.api.dto.RunTurnListResult;
import com.wzx.babiq.server.api.dto.RunTurnSummaryDto;
import com.wzx.babiq.server.conversation.repository.ConversationRepository;
import com.wzx.babiq.server.conversation.repository.ToolCallRecord;
import com.wzx.babiq.server.conversation.repository.TurnSummaryRecord;
import com.wzx.babiq.server.persistence.entity.ApprovalEntity;
import com.wzx.babiq.server.persistence.entity.ItemEntity;
import com.wzx.babiq.server.persistence.entity.TurnEntity;
import com.wzx.babiq.server.persistence.mapper.ItemMapper;
import com.wzx.babiq.server.persistence.service.ApprovalPersistenceService;
import com.wzx.babiq.server.persistence.service.ToolCallPersistenceService;
import com.wzx.babiq.server.persistence.service.TurnPersistenceService;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 运行记录聚合服务。
 *
 * <p>运行详情不是单表查询：turn 快照在 `bq_turns`，聊天协议 item 在 `bq_items`，
 * 运行摘要在 `bq_turn_summaries`，审批和工具调用也各自有表。本服务把这些数据聚合成
 * run/* JSON-RPC 可以直接返回的 DTO，避免 handler 或 UI 理解数据库结构。</p>
 */
@Service
public class RunRecordService {

    /** turn 表服务，提供 turn 列表和单个 turn 查询。 */
    private final TurnPersistenceService turnPersistenceService;
    /** 对话仓库，负责读取 turnSummary。 */
    private final ConversationRepository conversationRepository;
    /** item mapper，按 turnId 回放协议 item。 */
    private final ItemMapper itemMapper;
    /** 审批服务，按 turnId 读取审批历史。 */
    private final ApprovalPersistenceService approvalPersistenceService;
    /** 工具调用服务，按 turnId 读取工具轨迹。 */
    private final ToolCallPersistenceService toolCallPersistenceService;
    /** 解析 bq_items.payload_json，并合成 turnSummary JSON。 */
    private final ObjectMapper objectMapper;

    /**
     * 创建运行记录服务。
     *
     * @param turnPersistenceService turn 持久化服务
     * @param conversationRepository 对话仓库
     * @param itemMapper item mapper
     * @param approvalPersistenceService 审批持久化服务
     * @param toolCallPersistenceService 工具调用持久化服务
     * @param objectMapper JSON 序列化器
     */
    public RunRecordService(
            TurnPersistenceService turnPersistenceService,
            ConversationRepository conversationRepository,
            ItemMapper itemMapper,
            ApprovalPersistenceService approvalPersistenceService,
            ToolCallPersistenceService toolCallPersistenceService,
            ObjectMapper objectMapper) {
        this.turnPersistenceService = turnPersistenceService;
        this.conversationRepository = conversationRepository;
        this.itemMapper = itemMapper;
        this.approvalPersistenceService = approvalPersistenceService;
        this.toolCallPersistenceService = toolCallPersistenceService;
        this.objectMapper = objectMapper;
    }

    /**
     * 查询某个 thread 下的 turn 列表。
     *
     * @param threadId 会话 id
     * @param limit 最大返回数量
     * @param cursor 可选游标 turnId
     * @return turn 列表结果
     */
    public RunTurnListResult listTurns(String threadId, int limit, String cursor) {
        List<RunTurnSummaryDto> turns = turnPersistenceService.listTurns(threadId, limit, cursor)
                .stream()
                .map(this::toTurnSummary)
                .toList();
        return new RunTurnListResult(turns, null);
    }

    /**
     * 查询单个 turn 的完整运行详情。
     *
     * @param turnId turn id
     * @return turn 详情
     */
    public RunTurnDetailResult getTurn(String turnId) {
        TurnEntity turn = turnPersistenceService.findTurn(turnId)
                .orElseThrow(() -> new IllegalArgumentException("turn 不存在: " + turnId));
        return new RunTurnDetailResult(
                toTurnSummary(turn),
                listItemPayloads(turnId),
                summaryJson(turn),
                approvalPersistenceService.listByTurnId(turnId).stream().map(this::toApprovalDto).toList(),
                toolCallPersistenceService.listByTurnId(turnId).stream().map(this::toToolCallDto).toList());
    }

    private List<JsonNode> listItemPayloads(String turnId) {
        return itemMapper.selectList(Wrappers.<ItemEntity>lambdaQuery()
                        .eq(ItemEntity::getTurnId, turnId)
                        .orderByAsc(ItemEntity::getSequenceNo))
                .stream()
                .map(this::parsePayload)
                .toList();
    }

    private JsonNode parsePayload(ItemEntity item) {
        try {
            return objectMapper.readTree(item.getPayloadJson());
        } catch (Exception exception) {
            throw new IllegalStateException("运行记录 item JSON 无法解析: " + item.getItemId(), exception);
        }
    }

    private JsonNode summaryJson(TurnEntity turn) {
        return conversationRepository.findTurnSummary(turn.getTurnId())
                .map(summary -> toSummaryJson(turn, summary))
                .orElse(null);
    }

    private ObjectNode toSummaryJson(TurnEntity turn, TurnSummaryRecord summary) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("id", "summary_" + turn.getTurnId());
        node.put("type", "turnSummary");
        node.put("status", turn.getStatus() == null ? "unknown" : turn.getStatus().toLowerCase());
        node.put("model", turn.getModel());
        node.put("promptTokens", summary.promptTokens());
        node.put("completionTokens", summary.completionTokens());
        node.put("totalTokens", summary.totalTokens());
        node.put("toolCalls", summary.toolCount());
        node.put("durationMs", summary.durationMs());
        return node;
    }

    private RunTurnSummaryDto toTurnSummary(TurnEntity entity) {
        return new RunTurnSummaryDto(
                entity.getTurnId(),
                entity.getThreadId(),
                entity.getStatus(),
                entity.getInputText(),
                entity.getCwd(),
                entity.getProviderId(),
                entity.getModel(),
                entity.getStartedAt(),
                entity.getCompletedAt(),
                entity.getRecoveryReason(),
                entity.getRecoveredAt());
    }

    private RunApprovalDto toApprovalDto(ApprovalEntity approval) {
        return new RunApprovalDto(
                approval.getApprovalId(),
                approval.getToolName(),
                approval.getArgsJson(),
                approval.getEditedArgsJson(),
                approval.getDecision(),
                approval.getScope(),
                approval.getStatus(),
                approval.getCreatedAt(),
                approval.getResolvedAt());
    }

    private RunToolCallDto toToolCallDto(ToolCallRecord record) {
        return new RunToolCallDto(
                record.toolCallId(),
                record.toolName(),
                record.argsJson(),
                record.status(),
                record.resultPreview(),
                record.errorMessage(),
                record.startedAt() == null ? null : record.startedAt().toString(),
                record.completedAt() == null ? null : record.completedAt().toString());
    }

}
