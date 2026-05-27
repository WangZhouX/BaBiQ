package com.wzx.babiq.server.skill;

/**
 * 单个 Skill 正文读取结果。
 *
 * @param descriptor skill metadata
 * @param content 截断后的 SKILL.md 正文
 * @param truncated 是否因为预算被截断
 */
public record SkillContent(
        SkillDescriptor descriptor,
        String content,
        boolean truncated
) {
}
