package com.wzx.babiq.server.workunit;

/**
 * 创建或复用工作容器的请求。
 *
 * <p>它来自 `turn/start.executionIntent`，属于用户显式意图元数据，不是模型生成内容。</p>
 *
 * @param kind 工作容器类型，当前支持 orchestration 或 team
 * @param name 用户给容器取的名称，同一会话内运行中容器要求唯一
 * @param goal 本次追加到容器里的目标
 * @param goalId 可选目标 id；为空时由后端生成
 */
public record WorkUnitCreateRequest(
        String kind,
        String name,
        String goal,
        String goalId
) {
}
