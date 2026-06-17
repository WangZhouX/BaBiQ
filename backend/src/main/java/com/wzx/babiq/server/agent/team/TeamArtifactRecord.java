package com.wzx.babiq.server.agent.team;

import java.time.Instant;

/**
 * 团队记忆工作区产物记录。
 *
 * <p>SQLite 的 `bq_team_artifacts` 是事实源，Markdown 文件只是面向调试和成员按需读取的镜像。
 * 成员全文产物必须追加式保留，不能被 digest 压缩覆盖。</p>
 *
 * @param teamId 所属团队 id
 * @param artifactId 协议层产物 id
 * @param artifactType 产物类型：TEAM_INDEX、MEMBER_OUTPUT、DIGEST、RESULT
 * @param relativePath 团队目录下的相对 Markdown 路径
 * @param sha256 文件内容 SHA-256
 * @param tokenEstimate 文本 token 粗估值
 * @param round 产物所属调度轮；非轮次产物为 0
 * @param memberName 成员名；非成员产物为空
 * @param content Markdown 正文副本
 * @param createdAt 创建时间
 * @param updatedAt 更新时间
 */
public record TeamArtifactRecord(
        String teamId,
        String artifactId,
        String artifactType,
        String relativePath,
        String sha256,
        int tokenEstimate,
        int round,
        String memberName,
        String content,
        Instant createdAt,
        Instant updatedAt
) {
}
