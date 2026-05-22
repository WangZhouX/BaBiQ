package com.wzx.babiq.server.conversation;

import org.springframework.stereotype.Service;

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

    private static final int ID_RANDOM_LENGTH = 12;

    private final Map<String, Thread> threads = new ConcurrentHashMap<>();
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

    private String newId(String prefix) {
        String randomPart = UUID.randomUUID().toString().replace("-", "").substring(0, ID_RANDOM_LENGTH);
        return prefix + randomPart;
    }
}
