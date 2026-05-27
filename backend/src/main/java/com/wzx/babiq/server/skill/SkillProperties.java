package com.wzx.babiq.server.skill;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.nio.file.Path;
import java.util.List;

/**
 * 本地 Skill 注册表配置。
 *
 * <p>P3-5 只读取用户显式允许的本机目录，不做远程安装或插件市场，避免后端随意扫描整个磁盘。</p>
 *
 * @param enabled 是否启用 Skill metadata 扫描
 * @param directories 允许扫描的 Skill 根目录
 * @param maxContentChars 单个 SKILL.md 返回给 UI 或模型前的最大字符数
 */
@ConfigurationProperties(prefix = "babiq.skills")
public record SkillProperties(
        Boolean enabled,
        List<Path> directories,
        int maxContentChars
) {

    /**
     * 配置默认值。
     */
    public SkillProperties {
        enabled = enabled == null ? Boolean.TRUE : enabled;
        directories = directories == null ? List.of(
                Path.of(System.getProperty("user.home"), ".codex", "skills"),
                Path.of(System.getProperty("user.home"), ".codex", "superpowers", "skills")) : List.copyOf(directories);
        maxContentChars = maxContentChars <= 0 ? 16_000 : maxContentChars;
    }
}
