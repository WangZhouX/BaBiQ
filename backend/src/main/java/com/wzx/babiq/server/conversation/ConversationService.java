package com.wzx.babiq.server.conversation;

import com.wzx.babiq.server.conversation.items.CommandExecutionItem;
import com.wzx.babiq.server.conversation.items.FileChangeItem;
import com.wzx.babiq.server.conversation.items.ReasoningItem;
import com.wzx.babiq.server.conversation.items.TurnSummaryItem;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
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

    /**
     * 创建新的对话线程。
     *
     * @param cwd 用户当前工作目录
     * @return 已写入内存 registry 的 Thread
     */
    public Thread createThread(String cwd) {
        String threadId = newId("thr_");
        Thread thread = Thread.newThread(threadId, cwd);
        threads.put(threadId, thread);
        return thread;
    }

    /**
     * 按 id 查找 Thread。
     *
     * @param threadId Thread 标识
     * @return 找到时返回 Thread,否则 Optional.empty
     */
    public Optional<Thread> findThread(String threadId) {
        return Optional.ofNullable(threads.get(threadId));
    }

    /**
     * 为已有 Thread 创建新的 Turn。
     *
     * @param threadId 所属 Thread 标识
     * @return 已写入内存 registry 的 Turn
     * @throws IllegalArgumentException threadId 不存在时抛出
     */
    public Turn startTurn(String threadId) {
        if (!threads.containsKey(threadId)) {
            throw new IllegalArgumentException("threadId=" + threadId + " 不存在,无法创建 Turn");
        }

        String turnId = newId("turn_");
        Turn turn = new Turn(turnId, threadId);
        turns.put(turnId, turn);
        return turn;
    }

    /**
     * 按 id 查找 Turn。
     *
     * @param turnId Turn 标识
     * @return 找到时返回 Turn,否则 Optional.empty
     */
    public Optional<Turn> findTurn(String turnId) {
        return Optional.ofNullable(turns.get(turnId));
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
     * @param estimatedCostUsd 估算美元成本
     * @param durationMs 本轮耗时毫秒
     * @return turnSummary item
     */
    public TurnSummaryItem emitTurnSummary(String status,
                                           String model,
                                           long promptTokens,
                                           long completionTokens,
                                           long totalTokens,
                                           int toolCalls,
                                           BigDecimal estimatedCostUsd,
                                           long durationMs) {
        return new TurnSummaryItem(newId("it_"), "turnSummary", status, model,
                promptTokens, completionTokens, totalTokens, toolCalls, estimatedCostUsd, durationMs);
    }

    private String newId(String prefix) {
        // UUID 去掉连字符后取前 12 位，P1 内存态足够用；未来持久化阶段可换成更严格的 id 生成器。
        String randomPart = UUID.randomUUID().toString().replace("-", "").substring(0, ID_RANDOM_LENGTH);
        return prefix + randomPart;
    }
}
