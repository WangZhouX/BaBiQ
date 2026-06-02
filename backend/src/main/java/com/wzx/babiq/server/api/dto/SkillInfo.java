package com.wzx.babiq.server.api.dto;

import java.util.List;

/**
 * 本地 Skill metadata 响应。
 *
 * @param id 稳定 Skill id
 * @param namespace Skill 所属命名空间
 * @param name Skill 名称
 * @param description Skill front matter 描述
 * @param sourceDirectory 来源根目录
 * @param skillFile SKILL.md 文件绝对路径
 * @param contentHash 正文 hash，用于判断目录是否变化
 * @param allowedTools Skill 声明的工具白名单元数据，不作为 BaBiQ 授权依据
 */
public record SkillInfo(
        String id,
        String namespace,
        String name,
        String description,
        String sourceDirectory,
        String skillFile,
        String contentHash,
        List<String> allowedTools
) {

    public SkillInfo {
        allowedTools = allowedTools == null ? List.of() : List.copyOf(allowedTools);
    }

    public SkillInfo(String id,
                     String namespace,
                     String name,
                     String description,
                     String sourceDirectory,
                     String skillFile,
                     String contentHash) {
        this(id, namespace, name, description, sourceDirectory, skillFile, contentHash, List.of());
    }
}
