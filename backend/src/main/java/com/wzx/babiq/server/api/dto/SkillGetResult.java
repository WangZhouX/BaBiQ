package com.wzx.babiq.server.api.dto;

/**
 * skills/get 响应。
 *
 * @param skill Skill metadata
 * @param content 截断后的 SKILL.md 正文
 * @param truncated 是否因为字符预算被截断
 */
public record SkillGetResult(
        SkillInfo skill,
        String content,
        boolean truncated
) {
}
