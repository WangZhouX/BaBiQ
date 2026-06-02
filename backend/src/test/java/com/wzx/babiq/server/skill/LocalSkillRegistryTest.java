package com.wzx.babiq.server.skill;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * P3-6 本地 Skill 官方注册表 adapter 测试。
 */
class LocalSkillRegistryTest {

    @TempDir
    Path tempDir;

    @Test
    @DisplayName("官方 YAML front matter 解析结果映射为 BaBiQ 稳定 id、namespace、hash 和 allowedTools")
    void list_skills_should_use_official_metadata_while_preserving_babiq_identity() throws Exception {
        Path root = tempDir.resolve("skills-root");
        writeSkill(root.resolve("agent").resolve("context"), """
                ---
                name: context
                description: >
                  上下文治理 Skill: 支持多行说明
                allowed_tools:
                  - read_file
                  - list_dir
                ---
                # Context
                使用分层上下文窗口。
                """);
        SkillProperties properties = isolatedProperties(root);
        LocalSkillRegistry registry = new LocalSkillRegistry(properties);

        SkillDescriptor descriptor = registry.listSkills().get(0);

        assertThat(descriptor.id()).isEqualTo("agent.context");
        assertThat(descriptor.namespace()).isEqualTo("agent");
        assertThat(descriptor.name()).isEqualTo("context");
        assertThat(descriptor.description()).contains("上下文治理 Skill: 支持多行说明");
        assertThat(descriptor.sourceDirectory()).isEqualTo(root.toAbsolutePath().normalize().toString());
        assertThat(descriptor.skillFile()).endsWith("SKILL.md");
        assertThat(descriptor.contentHash()).hasSize(64);
        assertThat(descriptor.allowedTools()).containsExactly("read_file", "list_dir");
    }

    @Test
    @DisplayName("缺少 front matter 或非法 YAML 的 Skill 不进入目录")
    void list_skills_should_ignore_invalid_skill_metadata() throws Exception {
        Path root = tempDir.resolve("skills-root");
        writeRaw(root.resolve("no-frontmatter").resolve("SKILL.md"), "# Missing");
        writeRaw(root.resolve("broken").resolve("SKILL.md"), """
                ---
                name: broken
                description: [非法
                ---
                # Broken
                """);
        SkillProperties properties = isolatedProperties(root);
        LocalSkillRegistry registry = new LocalSkillRegistry(properties);

        assertThat(registry.listSkills()).isEmpty();
    }

    @Test
    @DisplayName("项目级 Skill 目录跟随当前 cwd provider 变化")
    void project_skill_directory_should_follow_current_cwd_provider() throws Exception {
        Path firstCwd = tempDir.resolve("first");
        Path secondCwd = tempDir.resolve("second");
        writeSkill(firstCwd.resolve(".agents").resolve("skills").resolve("alpha"), """
                ---
                name: alpha
                description: 第一个项目技能
                ---
                # Alpha
                """);
        writeSkill(secondCwd.resolve(".agents").resolve("skills").resolve("beta"), """
                ---
                name: beta
                description: 第二个项目技能
                ---
                # Beta
                """);
        AtomicReference<Path> cwd = new AtomicReference<>(firstCwd);
        SkillProperties properties = new SkillProperties(true, tempDir.resolve("user"), null, List.of(), List.of(), 1_000);
        LocalSkillRegistry registry = new LocalSkillRegistry(properties,
                () -> cwd.get().resolve(".agents").resolve("skills"));

        assertThat(registry.listSkills()).extracting(SkillDescriptor::name).containsExactly("alpha");

        cwd.set(secondCwd);

        assertThat(registry.listSkills()).extracting(SkillDescriptor::name).containsExactly("beta");
    }

    private static void writeSkill(Path skillDirectory, String content) throws Exception {
        writeRaw(skillDirectory.resolve("SKILL.md"), content);
    }

    private static void writeRaw(Path file, String content) throws Exception {
        Files.createDirectories(file.getParent());
        Files.writeString(file, content);
    }

    private SkillProperties isolatedProperties(Path root) {
        return new SkillProperties(
                true,
                tempDir.resolve("empty-user-skills"),
                tempDir.resolve("empty-project-skills"),
                List.of(root),
                List.of(),
                1_000);
    }
}
