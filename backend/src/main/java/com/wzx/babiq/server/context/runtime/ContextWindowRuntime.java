package com.wzx.babiq.server.context.runtime;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.wzx.babiq.server.context.CapabilityCatalogAssembler;
import com.wzx.babiq.server.context.ContextAssembler;
import com.wzx.babiq.server.context.compaction.ContextBudget;
import com.wzx.babiq.server.context.compaction.ContextBudgetPolicy;
import com.wzx.babiq.server.context.compaction.ContextCompactionOutcome;
import com.wzx.babiq.server.context.compaction.ContextCompactionRequest;
import com.wzx.babiq.server.context.compaction.ContextCompactionService;
import com.wzx.babiq.server.context.model.CapabilityCatalog;
import com.wzx.babiq.server.context.model.ContextAssemblyInput;
import com.wzx.babiq.server.context.model.ContextAssemblyResult;
import com.wzx.babiq.server.context.model.ShortTermSummary;
import com.wzx.babiq.server.context.repository.ContextSummaryRecord;
import com.wzx.babiq.server.context.repository.ContextSummaryRepository;
import com.wzx.babiq.server.context.repository.ContextSnapshotRecord;
import com.wzx.babiq.server.context.repository.ContextSnapshotRepository;
import com.wzx.babiq.server.context.repository.ContextWindowRecord;
import com.wzx.babiq.server.context.repository.ContextWindowRepository;
import com.wzx.babiq.server.conversation.items.ContextCompactionItem;
import com.wzx.babiq.server.conversation.items.ThreadItem;
import com.wzx.babiq.server.conversation.repository.ConversationRepository;
import com.wzx.babiq.server.conversation.repository.ItemRecord;
import com.wzx.babiq.server.observability.TurnObservationContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.Message;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * P3-2 上下文窗口运行时。
 *
 * <p>它在普通 turn 调用模型前读取已持久化历史，调用 P3-1 ContextAssembler 生成分层上下文，
 * 再把快照写入 SQLite。生成的 modelInputText 是本轮临时视图，不会写回聊天历史 item。</p>
 */
@Component
public class ContextWindowRuntime {

    private static final Logger log = LoggerFactory.getLogger(ContextWindowRuntime.class);
    private static final int HISTORY_LIMIT = 200;

    /** 对话仓库，用于读取已经落库的历史 item。 */
    private final ConversationRepository conversationRepository;
    /** P3-1 上下文装配器。 */
    private final ContextAssembler contextAssembler;
    /** 能力目录装配器，从 Spring AI ToolCallback 中提取摘要。 */
    private final CapabilityCatalogAssembler capabilityCatalogAssembler;
    /** 把分层消息渲染成 ReactAgent 临时输入。 */
    private final ContextualPromptRenderer promptRenderer;
    /** thread 级窗口状态仓库。 */
    private final ContextWindowRepository windowRepository;
    /** 单轮上下文快照仓库。 */
    private final ContextSnapshotRepository snapshotRepository;
    /** 自动压缩服务；为空时保留 P3-2 只装配快照的行为。 */
    private final ContextCompactionService compactionService;
    /** 摘要仓库，用于根据 activeSummaryId 还原当前窗口摘要。 */
    private final ContextSummaryRepository summaryRepository;
    /** 默认预算策略，主要服务测试或未启用压缩服务的场景。 */
    private final ContextBudgetPolicy fallbackBudgetPolicy;
    /** snake_case JSON mapper，保证 envelope/snapshot JSON 和 P3-1 结构一致。 */
    private final ObjectMapper objectMapper;

    /**
     * 创建上下文窗口运行时。
     */
    public ContextWindowRuntime(ConversationRepository conversationRepository,
                                ContextAssembler contextAssembler,
                                CapabilityCatalogAssembler capabilityCatalogAssembler,
                                ContextualPromptRenderer promptRenderer,
                                ContextWindowRepository windowRepository,
                                ContextSnapshotRepository snapshotRepository,
                                ObjectMapper objectMapper) {
        this(conversationRepository, contextAssembler, capabilityCatalogAssembler, promptRenderer,
                windowRepository, snapshotRepository, objectMapper, null, null);
    }

    /**
     * 创建带自动压缩服务的测试运行时。
     */
    public ContextWindowRuntime(ConversationRepository conversationRepository,
                                ContextAssembler contextAssembler,
                                CapabilityCatalogAssembler capabilityCatalogAssembler,
                                ContextualPromptRenderer promptRenderer,
                                ContextWindowRepository windowRepository,
                                ContextSnapshotRepository snapshotRepository,
                                ObjectMapper objectMapper,
                                ContextCompactionService compactionService) {
        this(conversationRepository, contextAssembler, capabilityCatalogAssembler, promptRenderer,
                windowRepository, snapshotRepository, objectMapper, compactionService, null);
    }

    /**
     * 创建生产运行时，接入短期压缩和摘要恢复。
     */
    @Autowired
    public ContextWindowRuntime(ConversationRepository conversationRepository,
                                ContextAssembler contextAssembler,
                                CapabilityCatalogAssembler capabilityCatalogAssembler,
                                ContextualPromptRenderer promptRenderer,
                                ContextWindowRepository windowRepository,
                                ContextSnapshotRepository snapshotRepository,
                                ObjectMapper objectMapper,
                                ContextCompactionService compactionService,
                                ContextSummaryRepository summaryRepository) {
        this.conversationRepository = conversationRepository;
        this.contextAssembler = contextAssembler;
        this.capabilityCatalogAssembler = capabilityCatalogAssembler;
        this.promptRenderer = promptRenderer;
        this.windowRepository = windowRepository;
        this.snapshotRepository = snapshotRepository;
        this.compactionService = compactionService;
        this.summaryRepository = summaryRepository;
        this.fallbackBudgetPolicy = new ContextBudgetPolicy();
        ObjectMapper base = objectMapper == null ? new ObjectMapper() : objectMapper;
        this.objectMapper = base.copy().setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE);
    }

    /**
     * 准备本轮模型输入并保存快照。
     *
     * @param input 本轮上下文运行输入
     * @return 临时模型输入和快照 id
     */
    public ContextWindowRuntimeResult prepare(ContextWindowRuntimeInput input) {
        Instant now = Instant.now();
        ContextWindowRecord existingWindow = windowRepository.findByThreadId(input.threadId()).orElse(null);
        int modelWindow = effectiveModelWindow(input, existingWindow);
        ContextBudget budget = budget(modelWindow);
        int threshold = budget.autoCompactThresholdTokens();
        CapabilityCatalog capabilityCatalog = capabilityCatalogAssembler.assemble(input.toolCallbacks());
        List<ThreadItem> historyItems = historyItems(input);
        ShortTermSummary activeSummary = activeSummary(existingWindow).orElse(null);
        ContextAssemblyResult assemblyResult = assemble(input, historyItems, activeSummary, capabilityCatalog);
        ContextCompactionOutcome compactionOutcome = compactIfNeeded(input, historyItems, activeSummary,
                assemblyResult, modelWindow);
        int windowOrdinal = existingWindow == null ? 0 : existingWindow.windowOrdinal();
        if (compactionOutcome.compacted()) {
            windowOrdinal = windowOrdinal + 1;
            activeSummary = toShortTermSummary(compactionOutcome.summaryRecord());
            emitCompactionItem(input, compactionOutcome, windowOrdinal);
            assemblyResult = assemble(input, historyItems, activeSummary, capabilityCatalog);
        }
        String snapshotId = newSnapshotId();
        ContextSnapshotRecord snapshot = snapshotRecord(input, assemblyResult, capabilityCatalog,
                snapshotId, windowOrdinal, modelWindow, threshold, now);
        String modelInputText = promptRenderer.render(assemblyResult);
        try {
            snapshotRepository.save(snapshot);
            windowRepository.upsert(windowRecord(input, existingWindow, snapshotId, modelWindow, threshold,
                    windowOrdinal, activeSummary == null ? null : activeSummary.summaryId(), now));
            return new ContextWindowRuntimeResult(snapshotId, input.userText(), modelInputText, assemblyResult);
        } catch (RuntimeException exception) {
            // 上下文快照是观测和回放用途的侧车数据，不能因为落库竞态或历史测试缺少父级记录而中断模型主流程。
            log.warn("上下文快照落库失败，本轮继续使用临时上下文: threadId={}, turnId={}, snapshotId={}, reason={}: {}",
                    input.threadId(), input.turnId(), snapshotId,
                    exception.getClass().getSimpleName(), exception.getMessage());
            return new ContextWindowRuntimeResult(null, input.userText(), modelInputText, assemblyResult);
        }
    }

    /**
     * 回填真实 prompt token。
     *
     * @param snapshotId 快照 id
     * @param context 当前 turn 的观测上下文
     */
    public void recordUsage(String snapshotId, TurnObservationContext context) {
        if (snapshotId == null || snapshotId.isBlank() || context == null || context.promptTokens() <= 0) {
            return;
        }
        snapshotRepository.updateActualPromptTokens(snapshotId, context.promptTokens());
    }

    private List<ThreadItem> historyItems(ContextWindowRuntimeInput input) {
        return conversationRepository.listItems(input.threadId(), HISTORY_LIMIT).stream()
                .filter(record -> !input.turnId().equals(record.turnId()))
                .flatMap(record -> parseThreadItem(record).stream())
                .toList();
    }

    private Optional<ThreadItem> parseThreadItem(ItemRecord record) {
        try {
            return Optional.of(objectMapper.readValue(record.payloadJson(), ThreadItem.class));
        } catch (Exception ignored) {
            return Optional.empty();
        }
    }

    private ContextSnapshotRecord snapshotRecord(ContextWindowRuntimeInput input,
                                                 ContextAssemblyResult assemblyResult,
                                                 CapabilityCatalog capabilityCatalog,
                                                 String snapshotId,
                                                 int windowOrdinal,
                                                 int modelWindow,
                                                 int threshold,
                                                 Instant now) {
        long included = assemblyResult.snapshot().items().stream().filter(item -> item.included()).count();
        return new ContextSnapshotRecord(
                snapshotId,
                input.threadId(),
                input.turnId(),
                "pre_model_call",
                input.providerId(),
                input.model(),
                input.cwd(),
                windowOrdinal,
                modelWindow,
                threshold,
                assemblyResult.snapshot().estimatedTokens(),
                null,
                Math.toIntExact(included),
                Math.toIntExact(assemblyResult.snapshot().items().size() - included),
                envelopeJson(assemblyResult),
                writeJson(assemblyResult.snapshot().items()),
                writeJson(capabilityCatalog),
                preview(input.userText()),
                now);
    }

    private ContextWindowRecord windowRecord(ContextWindowRuntimeInput input,
                                             ContextWindowRecord existingWindow,
                                             String snapshotId,
                                             int modelWindow,
                                             int threshold,
                                             int windowOrdinal,
                                             String activeSummaryId,
                                             Instant now) {
        return new ContextWindowRecord(
                input.threadId(),
                windowOrdinal,
                activeSummaryId,
                modelWindow,
                threshold,
                snapshotId,
                existingWindow == null ? now : existingWindow.createdAt(),
                now);
    }

    private int effectiveModelWindow(ContextWindowRuntimeInput input, ContextWindowRecord existingWindow) {
        if (input.modelContextWindow() > 0) {
            return input.modelContextWindow();
        }
        if (existingWindow != null && existingWindow.modelContextWindow() > 0) {
            return existingWindow.modelContextWindow();
        }
        return 32_768;
    }

    private String envelopeJson(ContextAssemblyResult assemblyResult) {
        List<Message> messages = assemblyResult.messages();
        if (messages.size() >= 2) {
            return messages.get(1).getText();
        }
        return writeJson(assemblyResult.envelope());
    }

    private String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception exception) {
            throw new IllegalStateException("上下文快照 JSON 序列化失败", exception);
        }
    }

    private static List<String> workspaceFacts(String cwd) {
        return cwd == null || cwd.isBlank() ? List.of() : List.of("当前工作目录: " + cwd);
    }

    private static String preview(String text) {
        if (text == null) {
            return null;
        }
        return text.length() <= 160 ? text : text.substring(0, 160);
    }

    private static String newSnapshotId() {
        return "ctxsnap_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
    }

    private ContextAssemblyResult assemble(ContextWindowRuntimeInput input,
                                           List<ThreadItem> historyItems,
                                           ShortTermSummary activeSummary,
                                           CapabilityCatalog capabilityCatalog) {
        return contextAssembler.assemble(new ContextAssemblyInput(
                input.threadId(),
                input.turnId(),
                input.userText(),
                input.cwd(),
                input.projectId(),
                input.runPolicy().sandboxMode().name(),
                input.runPolicy().approvalPolicy().name(),
                historyItems,
                activeSummary,
                List.of(),
                workspaceFacts(input.cwd()),
                capabilityCatalog));
    }

    private ContextBudget budget(int modelWindow) {
        if (compactionService != null) {
            return compactionService.budgetFor(modelWindow);
        }
        return fallbackBudgetPolicy.calculate(modelWindow);
    }

    private ContextCompactionOutcome compactIfNeeded(ContextWindowRuntimeInput input,
                                                     List<ThreadItem> historyItems,
                                                     ShortTermSummary activeSummary,
                                                     ContextAssemblyResult assemblyResult,
                                                     int modelWindow) {
        if (compactionService == null) {
            return ContextCompactionOutcome.notNeeded();
        }
        return compactionService.compactIfNeeded(new ContextCompactionRequest(
                input.threadId(),
                input.turnId(),
                input.providerId(),
                input.model(),
                historyItems,
                activeSummary,
                assemblyResult.snapshot().estimatedTokens(),
                modelWindow,
                input.userText()));
    }

    private Optional<ShortTermSummary> activeSummary(ContextWindowRecord existingWindow) {
        if (existingWindow == null || existingWindow.activeSummaryId() == null || summaryRepository == null) {
            return Optional.empty();
        }
        return summaryRepository.findBySummaryId(existingWindow.activeSummaryId())
                .map(this::toShortTermSummary);
    }

    private ShortTermSummary toShortTermSummary(ContextSummaryRecord record) {
        return new ShortTermSummary(
                record.summaryId(),
                record.sourceItemRange(),
                record.summary(),
                record.sourceStartItemId(),
                record.sourceEndItemId());
    }

    private void emitCompactionItem(ContextWindowRuntimeInput input,
                                    ContextCompactionOutcome outcome,
                                    int windowOrdinal) {
        if (input.emitter() == null || outcome.compactionRecord() == null) {
            return;
        }
        try {
            input.emitter().emitItemAdded(new ContextCompactionItem(
                    AgentLoopSafeIds.newItemId(),
                    "contextCompaction",
                    outcome.compactionRecord().compactionId(),
                    outcome.status(),
                    outcome.summaryRecord().summaryId(),
                    windowOrdinal,
                    outcome.compactionRecord().estimatedTokensBefore(),
                    outcome.compactionRecord().estimatedTokensAfter(),
                    "上下文已自动压缩为短期摘要"));
        } catch (Exception exception) {
            log.warn("上下文压缩事件推送失败，压缩审计仍已落库: threadId={}, turnId={}, reason={}: {}",
                    input.threadId(), input.turnId(), exception.getClass().getSimpleName(), exception.getMessage());
        }
    }

    /**
     * ContextWindowRuntime 位于 context 包内，不能直接依赖 AgentLoopSupport 的包私有方法。
     */
    private static final class AgentLoopSafeIds {
        private AgentLoopSafeIds() {
        }

        private static String newItemId() {
            return "it_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        }
    }
}
