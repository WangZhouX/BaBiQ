package com.wzx.babiq.server.agent;

import com.alibaba.cloud.ai.graph.agent.ReactAgent;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 保存被 HumanInTheLoopHook 暂停的 ReactAgent 实例。
 *
 * <p>2026-05-25 Bug 修复记录：Spring AI Alibaba 的 HITL 恢复需要沿用同一个
 * ReactAgent/CompiledGraph 运行现场。旧实现审批后重新 build Agent，导致 DeepSeek 收到
 * “assistant.tool_calls 后没有 tool response”的非法历史并返回 400。该注册表用 threadId
 * 暂存暂停中的 Agent，让 approval/respond 能继续原来的图执行现场。</p>
 */
final class PausedReactAgentRegistry {

    /** key 是业务 threadId，value 是正在等待人工审批的 ReactAgent 实例。 */
    private final Map<String, ReactAgent> pausedAgentsByThread = new ConcurrentHashMap<>();

    /**
     * 记录一个刚被 HITL 暂停的 Agent。
     *
     * @param threadId 当前会话 id，审批响应会用它找回暂停现场
     * @param agent 需要继续执行的 ReactAgent 实例
     */
    void remember(String threadId, ReactAgent agent) {
        pausedAgentsByThread.put(threadId, agent);
    }

    /**
     * 取出并移除暂停中的 Agent，避免同一次审批响应被重复消费。
     *
     * @param threadId 当前会话 id
     * @return 已暂停的 Agent；没有找到时返回 null，通常表示后端进程重启或审批已被消费
     */
    ReactAgent take(String threadId) {
        return pausedAgentsByThread.remove(threadId);
    }

    /**
     * 在 turn 失败或被取消时清理暂停现场，避免后续误恢复到旧图。
     *
     * @param threadId 当前会话 id
     */
    void forget(String threadId) {
        pausedAgentsByThread.remove(threadId);
    }
}
