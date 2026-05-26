package com.wzx.babiq.server.memory.extract;

import com.wzx.babiq.server.conversation.repository.ItemRecord;

import java.util.List;

/**
 * Phase1 抽取请求。
 *
 * @param threadId 来源会话 id，由 job.threadId 定位
 * @param cwd 会话绑定的工作目录，用于后续 candidate 审计和按项目检索
 * @param providerId 会话默认 Provider id，可为空；写入 candidate 后便于追踪抽取来源
 * @param model 会话默认模型名，可为空；写入 candidate 后便于排查模型差异
 * @param tokenBudget Phase1 输入预算，默认来自长期记忆配置，防止超长会话直接塞满模型窗口
 * @param items 按会话顺序读取的 SQLite item，是抽取器唯一可使用的事实来源
 */
public record MemoryStageOneRequest(
        String threadId,
        String cwd,
        String providerId,
        String model,
        int tokenBudget,
        List<ItemRecord> items
) {
}
