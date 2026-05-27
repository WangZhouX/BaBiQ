package com.wzx.babiq.server.skill;

/**
 * 本地 Skill metadata。
 *
 * @param id 稳定 skill id
 * @param namespace skill 所属命名空间
 * @param name skill 名称
 * @param description front matter 中的说明
 * @param sourceDirectory 来源目录
 * @param skillFile SKILL.md 绝对路径
 * @param contentHash SKILL.md 内容 hash
 */
public record SkillDescriptor(
        String id,
        String namespace,
        String name,
        String description,
        String sourceDirectory,
        String skillFile,
        String contentHash
) {
}
