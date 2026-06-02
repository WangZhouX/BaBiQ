package com.wzx.babiq.server.skill;

import java.util.List;

/**
 * 本地 Skill metadata。
 *
 * @param id 稳定 Skill id
 * @param namespace Skill 所属命名空间
 * @param name Skill 名称
 * @param description Skill front matter 中的说明
 * @param sourceDirectory 来源根目录
 * @param skillFile SKILL.md 绝对路径
 * @param contentHash SKILL.md 内容 hash
 * @param allowedTools Skill front matter 声明的允许工具，仅作为元数据展示和审计参考
 */
public record SkillDescriptor(
        String id,
        String namespace,
        String name,
        String description,
        String sourceDirectory,
        String skillFile,
        String contentHash,
        List<String> allowedTools
) {

    public SkillDescriptor {
        allowedTools = allowedTools == null ? List.of() : List.copyOf(allowedTools);
    }

    public SkillDescriptor(String id,
                           String namespace,
                           String name,
                           String description,
                           String sourceDirectory,
                           String skillFile,
                           String contentHash) {
        this(id, namespace, name, description, sourceDirectory, skillFile, contentHash, List.of());
    }
}
