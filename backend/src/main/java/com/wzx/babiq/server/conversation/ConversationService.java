package com.wzx.babiq.server.conversation;

import com.wzx.babiq.server.application.scope.BusinessIdentityScope;
import com.wzx.babiq.server.conversation.items.CommandExecutionItem;
import com.wzx.babiq.server.conversation.items.FileChangeItem;
import com.wzx.babiq.server.conversation.items.ReasoningItem;
import com.wzx.babiq.server.conversation.items.TurnSummaryItem;
import com.wzx.babiq.server.conversation.repository.ConversationRepository;
import com.wzx.babiq.server.conversation.repository.TurnRecord;
import com.wzx.babiq.server.model.ModelProviderConfig;
import com.wzx.babiq.server.model.ModelProviderRegistry;
import com.wzx.babiq.server.persistence.service.TurnPersistenceService;
import com.wzx.babiq.server.agent.AgentLoopProperties;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 对话生命周期内存注册表。
 *
 * <p>P1-1 阶段不接数据库,因此 Thread 和 Turn 都保存在内存中。该服务集中负责
 * id 生成、thread 查找和 turn 创建,让 JSON-RPC handler 不直接操作 Map,避免
 * 协议处理逻辑和生命周期存储耦合。</p>
 */
@Service
public class ConversationService {

    /** id 随机后缀长度；够短便于日志阅读，同时降低本地开发时的碰撞概率。 */
    private static final int ID_RANDOM_LENGTH = 12;

    /** threadId -> Thread，会话级上下文，例如 cwd 和创建时间都保存在这里。 */
    private final Map<String, Thread> threads = new ConcurrentHashMap<>();
    /** turnId -> Turn，记录每一轮请求的状态机，供取消、审批恢复和摘要使用。 */
    private final Map<String, Turn> turns = new ConcurrentHashMap<>();
    /** 可选持久化仓库；生产环境存在，纯单元测试用无参构造时为空。 */
    private final ConversationRepository conversationRepository;
    /** 可选 turn 持久化服务；生产环境保存 turn 快照，纯单元测试为空。 */
    private final TurnPersistenceService turnPersistenceService;
    /** 可选模型注册表；创建 thread 时保存 active provider/model 快照。 */
    private final ModelProviderRegistry providerRegistry;
    /** 可选 Agent 配置；创建 thread/turn 时保存沙箱和审批策略快照。 */
    private final AgentLoopProperties agentLoopProperties;

    /**
     * 兼容旧单元测试的无参构造器。
     *
     * <p>真实 Spring 运行时会使用带依赖的构造器；无参构造只保留内存行为，方便测试状态机。</p>
     */
    public ConversationService() {
        this(null, null, null, null);
    }

    /**
     * 生产环境构造器，注入 P2 持久化和当前运行配置。
     *
     * @param conversationRepository 对话持久化仓库
     * @param turnPersistenceService turn 持久化服务
     * @param providerRegistry 模型 Provider 注册表
     * @param agentLoopProperties Agent Loop 配置
     */
    @Autowired
    public ConversationService(
            ConversationRepository conversationRepository,
            TurnPersistenceService turnPersistenceService,
            ModelProviderRegistry providerRegistry,
            AgentLoopProperties agentLoopProperties) {
        this.conversationRepository = conversationRepository;
        this.turnPersistenceService = turnPersistenceService;
        this.providerRegistry = providerRegistry;
        this.agentLoopProperties = agentLoopProperties;
    }

    /**
     * 创建新的对话线程。
     *
     * @param cwd 用户当前工作目录
     * @return 已写入内存 registry 的 Thread
     */
    public Thread createThread(String cwd) {
        return createThread(cwd, BusinessIdentityScope.UNSCOPED);
    }

    /** 在请求边界冻结身份后创建 Thread；后续 Turn 只从 Thread 复制。 */
    public Thread createThread(String cwd, BusinessIdentityScope businessIdentityScope) {
        String threadId = newId("thr_");
        Thread thread = Thread.newThread(threadId, cwd, businessIdentityScope);
        threads.put(threadId, thread);
        persistThreadIfEnabled(thread);
        return thread;
    }

    /**
     * 按 id 查找 Thread。
     *
     * @param threadId Thread 标识
     * @return 找到时返回 Thread,否则 Optional.empty
     */
    public Optional<Thread> findThread(String threadId) {
        Thread existing = threads.get(threadId);
        if (existing != null) {
            return existing.businessIdentityScope().scoped() ? Optional.empty() : Optional.of(existing);
        }
        if (conversationRepository == null) {
            return Optional.empty();
        }
        return conversationRepository.findThread(threadId)
                .map(entity -> {
                    Thread restored = new Thread(
                            entity.getThreadId(),
                            entity.getCwd(),
                            java.time.Instant.parse(entity.getCreatedAt()), scope(entity));
                    threads.put(restored.id(), restored);
                    return restored;
                });
    }

    public Optional<Thread> findThread(String threadId, BusinessIdentityScope scope) {
        Thread existing = threads.get(threadId);
        if (existing != null) {
            return existing.businessIdentityScope().equals(scope) ? Optional.of(existing) : Optional.empty();
        }
        if (conversationRepository == null) {
            return Optional.empty();
        }
        return conversationRepository.findThread(threadId, scope)
                .map(entity -> {
                    Thread restored = new Thread(
                            entity.getThreadId(), entity.getCwd(), java.time.Instant.parse(entity.getCreatedAt()), scope);
                    threads.putIfAbsent(restored.id(), restored);
                    Thread canonical = threads.get(restored.id());
                    return canonical.businessIdentityScope().equals(scope) ? canonical : null;
                });
    }

    /**
     * 为已有 Thread 创建新的 Turn。
     *
     * @param threadId 所属 Thread 标识
     * @return 已写入内存 registry 的 Turn
     * @throws IllegalArgumentException threadId 不存在时抛出
     */
    public Turn startTurn(String threadId) {
        Thread thread = findThread(threadId).orElse(null);
        if (thread == null) {
            throw new IllegalArgumentException("threadId=" + threadId + " 不存在,无法创建 Turn");
        }

        String turnId = newId("turn_");
        Turn turn = new Turn(turnId, threadId, thread.businessIdentityScope());
        turns.put(turnId, turn);
        return turn;
    }

    public Turn startTurn(String threadId, BusinessIdentityScope scope) {
        Thread thread = findThread(threadId, scope).orElse(null);
        if (thread == null) {
            throw new IllegalArgumentException("threadId=" + threadId + " 不存在,无法创建 Turn");
        }
        String turnId = newId("turn_");
        Turn turn = new Turn(turnId, threadId, thread.businessIdentityScope());
        turns.put(turnId, turn);
        return turn;
    }

    /**
     * 把已经进入 RUNNING 的 turn 快照写入数据库。
     *
     * @param turn 当前 turn
     * @param inputText 用户输入文本
     * @param providerId 本轮实际 provider id
     * @param model 本轮实际模型名
     * @param cwd 本轮工作目录
     * @param sandboxMode 本轮沙箱模式
     * @param approvalPolicy 本轮审批策略
     */
    public void persistTurnStarted(Turn turn,
                                   String inputText,
                                   String providerId,
                                   String model,
                                   String cwd,
                                   String sandboxMode,
                                   String approvalPolicy) {
        if (turnPersistenceService == null) {
            return;
        }
        turnPersistenceService.saveTurn(TurnRecord.started(
                turn.id(),
                turn.threadId(),
                turn.status().name(),
                inputText,
                cwd,
                providerId,
                model,
                sandboxMode,
                approvalPolicy,
                turn.createdAt(),
                turn.businessIdentityScope()));
    }

    /**
     * 创建并持久化一个已知输入快照的 turn。
     *
     * @param threadId 所属 thread id
     * @param inputText 用户输入文本
     * @param providerId 本轮 provider id
     * @param model 本轮模型名
     * @param cwd 工作目录
     * @param sandboxMode 沙箱模式
     * @param approvalPolicy 审批策略
     * @return 新建的 turn
     */
    public Turn startTurn(String threadId,
                          String inputText,
                          String providerId,
                          String model,
                          String cwd,
                          String sandboxMode,
                          String approvalPolicy) {
        Turn turn = startTurn(threadId);
        turn.start();
        persistTurnStarted(turn, inputText, providerId, model, cwd, sandboxMode, approvalPolicy);
        return turn;
    }

    /**
     * 按 id 查找 Turn。
     *
     * @param turnId Turn 标识
     * @return 找到时返回 Turn,否则 Optional.empty
     */
    public Optional<Turn> findTurn(String turnId) {
        Turn turn = turns.get(turnId);
        if (turn != null) return turn.businessIdentityScope().scoped() ? Optional.empty() : Optional.of(turn);
        if (turnPersistenceService == null) return Optional.empty();
        return turnPersistenceService.findTurn(turnId)
                .map(entity -> restoreTurn(entity, BusinessIdentityScope.UNSCOPED));
    }

    public Optional<Turn> findTurn(String turnId, BusinessIdentityScope scope) {
        Turn turn = turns.get(turnId);
        if (turn != null) return turn.businessIdentityScope().equals(scope) ? Optional.of(turn) : Optional.empty();
        if (turnPersistenceService == null) return Optional.empty();
        return turnPersistenceService.findTurn(turnId, scope)
                .map(entity -> restoreTurn(entity, scope));
    }

    private Turn restoreTurn(
            com.wzx.babiq.server.persistence.entity.TurnEntity entity,
            BusinessIdentityScope scope) {
        Turn restored = Turn.restore(
                entity.getTurnId(), entity.getThreadId(), scope,
                TurnStatus.valueOf(entity.getStatus()),
                entity.getStartedAt() == null ? null : java.time.Instant.parse(entity.getStartedAt()),
                entity.getCompletedAt() == null ? null : java.time.Instant.parse(entity.getCompletedAt()),
                entity.getFailureReason());
        turns.putIfAbsent(restored.id(), restored);
        return turns.get(restored.id());
    }

    /**
     * 判断某个 thread 是否还有非终态 turn。
     *
     * @param threadId 会话 id
     * @return true 表示仍有 CREATED/RUNNING/WAITING_APPROVAL turn，不能归档
     */
    public boolean hasActiveTurn(String threadId) {
        return turns.values().stream()
                .anyMatch(turn -> threadId.equals(turn.threadId())
                        && !turn.businessIdentityScope().scoped() && !turn.status().isTerminal());
    }

    public boolean hasActiveTurn(String threadId, BusinessIdentityScope scope) {
        return turns.values().stream().anyMatch(turn -> threadId.equals(turn.threadId())
                && turn.businessIdentityScope().equals(scope) && !turn.status().isTerminal());
    }

    /**
     * 从内存注册表中移除 thread 和已结束 turn。
     *
     * @param threadId 会话 id
     */
    public void removeThread(String threadId) {
        threads.remove(threadId);
        turns.entrySet().removeIf(entry ->
                threadId.equals(entry.getValue().threadId()) && entry.getValue().status().isTerminal());
    }

    /**
     * 根据工作目录生成默认会话标题。
     *
     * @param cwd 工作目录
     * @return 默认标题
     */
    public String defaultTitleFor(String cwd) {
        return defaultTitle(cwd);
    }

    /**
     * 构造命令执行 item。
     *
     * <p>本方法只负责统一 item id 和 type，不直接写 WebSocket；真正发包仍由
     * {@link ItemEmitter#emitItemAdded(com.wzx.babiq.server.conversation.items.ThreadItem)}
     * 完成，避免生命周期服务和协议输出耦合。</p>
     *
     * @param command 命令文本
     * @param status 命令状态
     * @param exitCode 退出码，可为 null
     * @param stdout 标准输出，可为 null
     * @param stderr 标准错误，可为 null
     * @param durationMs 执行耗时，可为 null
     * @return commandExecution item
     */
    public CommandExecutionItem emitCommandExecution(
            String command, String status, Integer exitCode, String stdout, String stderr, Long durationMs) {
        return new CommandExecutionItem(newId("it_"), "commandExecution",
                command, status, exitCode, stdout, stderr, durationMs);
    }

    /**
     * 构造文件变更 item。
     *
     * @param action 文件动作，例如 read/write/patch
     * @param path 文件路径
     * @param status 动作状态，例如 completed/denied
     * @param contentPreview 内容预览或拒绝原因
     * @return fileChange item
     */
    public FileChangeItem emitFileChange(String action, String path, String status, String contentPreview) {
        return new FileChangeItem(newId("it_"), "fileChange", action, path, status, contentPreview);
    }

    /**
     * 构造可展示推理摘要 item。
     *
     * @param text 推理摘要文本
     * @return reasoning item
     */
    public ReasoningItem emitReasoning(String text) {
        return new ReasoningItem(newId("it_"), "reasoning", text);
    }

    /**
     * 构造 turn 摘要 item。
     *
     * @param status 本轮结束状态
     * @param model 实际使用的模型名
     * @param promptTokens prompt token 数
     * @param completionTokens completion token 数
     * @param totalTokens 总 token 数
     * @param toolCalls 工具调用次数
     * @param durationMs 本轮耗时毫秒
     * @return turnSummary item
     */
    public TurnSummaryItem emitTurnSummary(String status,
                                           String model,
                                           long promptTokens,
                                           long completionTokens,
                                           long totalTokens,
                                           int toolCalls,
                                           long durationMs) {
        return new TurnSummaryItem(newId("it_"), "turnSummary", status, model,
                promptTokens, completionTokens, totalTokens, toolCalls, durationMs);
    }

    private String newId(String prefix) {
        // UUID 去掉连字符后取前 12 位，P1 内存态足够用；未来持久化阶段可换成更严格的 id 生成器。
        String randomPart = UUID.randomUUID().toString().replace("-", "").substring(0, ID_RANDOM_LENGTH);
        return prefix + randomPart;
    }

    private void persistThreadIfEnabled(Thread thread) {
        if (conversationRepository == null) {
            return;
        }
        ModelProviderConfig provider = providerRegistry == null ? null : providerRegistry.active();
        conversationRepository.createThread(
                thread.id(),
                defaultTitleFor(thread.cwd()),
                thread.cwd(),
                provider == null ? null : provider.id(),
                provider == null ? null : provider.model(),
                agentLoopProperties == null ? null : agentLoopProperties.sandboxMode().name(),
                agentLoopProperties == null ? null : agentLoopProperties.approvalPolicy().name(),
                thread.createdAt(),
                thread.businessIdentityScope());
    }

    private static BusinessIdentityScope scope(com.wzx.babiq.server.persistence.entity.ThreadEntity entity) {
        if (entity.getDesktopInstanceId() == null) return BusinessIdentityScope.UNSCOPED;
        return BusinessIdentityScope.scoped(entity.getDesktopInstanceId(), entity.getDesktopSessionId(),
                entity.getAuthSessionId(), entity.getIdentityEpoch(), entity.getUserId(),
                entity.getTenantId(), entity.getPlatformId());
    }

    private String defaultTitle(String cwd) {
        try {
            Path fileName = Path.of(cwd).getFileName();
            String projectName = fileName == null ? cwd : fileName.toString();
            return projectName + " 新对话";
        } catch (InvalidPathException exception) {
            return "新对话";
        }
    }
}
