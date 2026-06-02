package com.wzx.babiq.server.skill;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * 本地 Skill 注册表配置。
 *
 * <p>P3-6 起默认使用工具中立的 {@code ~/.agents/skills} 和项目内
 * {@code .agents/skills}。旧版 {@code .codex/skills} 不再默认扫描，
 * 只通过 {@code additional-directories} 或旧字段 {@code directories}
 * 显式恢复。</p>
 *
 * @param enabled 是否启用 Skill metadata 扫描
 * @param userSkillsDirectory 用户级 Skill 根目录
 * @param projectSkillsDirectory 项目级 Skill 根目录
 * @param additionalDirectories 额外 Skill 根目录，主要用于兼容旧目录或用户显式配置
 * @param directories P3-5 旧版目录配置字段，读取后合并进额外目录
 * @param maxContentChars 单个 Skill 正文返回给 UI 或模型前的最大字符数
 */
@ConfigurationProperties(prefix = "babiq.skills")
public record SkillProperties(
        Boolean enabled,
        Path userSkillsDirectory,
        Path projectSkillsDirectory,
        List<Path> additionalDirectories,
        List<Path> directories,
        int maxContentChars
) {

    /**
     * 配置默认值。
     */
    public SkillProperties {
        enabled = enabled == null ? Boolean.TRUE : enabled;
        userSkillsDirectory = normalize(userSkillsDirectory == null
                ? Path.of(System.getProperty("user.home"), ".agents", "skills")
                : userSkillsDirectory);
        projectSkillsDirectory = normalize(projectSkillsDirectory == null
                ? Path.of("").toAbsolutePath().normalize().resolve(".agents").resolve("skills")
                : projectSkillsDirectory);
        additionalDirectories = normalizeAll(additionalDirectories);
        directories = normalizeAll(directories);
        maxContentChars = maxContentChars <= 0 ? 16_000 : maxContentChars;
    }

    /**
     * 返回实际参与扫描的 Skill 根目录，顺序保持用户级、项目级、额外目录。
     */
    public List<Path> allConfiguredDirectories() {
        List<Path> configuredDirectories = new ArrayList<>();
        configuredDirectories.add(userSkillsDirectory);
        configuredDirectories.add(projectSkillsDirectory);
        configuredDirectories.addAll(additionalDirectories);
        configuredDirectories.addAll(directories);
        return List.copyOf(configuredDirectories);
    }

    private static List<Path> normalizeAll(List<Path> paths) {
        if (paths == null) {
            return List.of();
        }
        return paths.stream().map(SkillProperties::normalize).toList();
    }

    private static Path normalize(Path path) {
        return path.toAbsolutePath().normalize();
    }
}
