package com.wzx.babiq.server.agent.team;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.nio.file.Path;

/**
 * 团队协作运行配置。
 *
 * <p>团队记忆目录独立于长期记忆目录，避免把单次任务草稿混入用户级长期事实。</p>
 *
 * @param enabled 是否启用团队记忆工作区
 * @param rootDir 团队记忆根目录，默认 `~/.babiq/teams`
 * @param supervisorContextBudgetTokens supervisor 可见摘要时间线 token 预算
 * @param discussionDigestBudgetTokens 成员共享讨论概要 token 预算
 * @param memberSummaryMaxChars 单条成员摘要卡最大字符数
 * @param maxRoundsCeiling 团队最大轮数硬上限
 */
@ConfigurationProperties(prefix = "babiq.team")
public record TeamMemoryProperties(
        boolean enabled,
        Path rootDir,
        int supervisorContextBudgetTokens,
        int discussionDigestBudgetTokens,
        int memberSummaryMaxChars,
        int maxRoundsCeiling
) {

    /**
     * Spring 配置绑定默认值。
     */
    public TeamMemoryProperties {
        rootDir = rootDir == null
                ? Path.of(System.getProperty("user.home"), ".babiq", "teams")
                : rootDir;
        supervisorContextBudgetTokens = supervisorContextBudgetTokens <= 0 ? 3000 : supervisorContextBudgetTokens;
        discussionDigestBudgetTokens = discussionDigestBudgetTokens <= 0 ? 2000 : discussionDigestBudgetTokens;
        memberSummaryMaxChars = memberSummaryMaxChars <= 0 ? 600 : memberSummaryMaxChars;
        maxRoundsCeiling = maxRoundsCeiling <= 0 ? 12 : maxRoundsCeiling;
    }
}
