package com.wzx.babiq.server.skill;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * P3-6 Skill 正文按需读取测试。
 */
class SkillContentLoaderTest {

    @TempDir
    Path tempDir;

    @Test
    @DisplayName("读取正文时使用官方 registry 并按 BaBiQ 字符预算截断")
    void load_should_read_content_on_demand_and_clip_by_budget() throws Exception {
        Path root = tempDir.resolve("skills-root");
        Path skillFile = root.resolve("context").resolve("SKILL.md");
        Files.createDirectories(skillFile.getParent());
        Files.writeString(skillFile, """
                ---
                name: context
                description: 上下文治理
                ---
                # Context
                123456789012345678901234567890123456789012345678901234567890
                """);
        SkillProperties properties = isolatedProperties(root, 48);
        LocalSkillRegistry registry = new LocalSkillRegistry(properties);
        SkillContentLoader loader = new SkillContentLoader(registry, properties);

        SkillContent content = loader.load("local.context");

        assertThat(content.truncated()).isTrue();
        assertThat(content.content()).hasSize(48);
        assertThat(content.descriptor().allowedTools()).isEmpty();
    }

    @Test
    @DisplayName("修改 SKILL.md 后 list/get 立即看到新的 hash 和正文")
    void list_and_load_should_reflect_latest_disk_content() throws Exception {
        Path root = tempDir.resolve("skills-root");
        Path skillFile = root.resolve("context").resolve("SKILL.md");
        Files.createDirectories(skillFile.getParent());
        Files.writeString(skillFile, """
                ---
                name: context
                description: 上下文治理
                ---
                # Context
                旧内容
                """);
        SkillProperties properties = isolatedProperties(root, 1_000);
        LocalSkillRegistry registry = new LocalSkillRegistry(properties);
        SkillContentLoader loader = new SkillContentLoader(registry, properties);
        String oldHash = registry.findById("local.context").orElseThrow().contentHash();

        Files.writeString(skillFile, """
                ---
                name: context
                description: 上下文治理
                ---
                # Context
                新内容
                """);

        SkillDescriptor descriptor = registry.findById("local.context").orElseThrow();
        SkillContent content = loader.load("local.context");
        assertThat(descriptor.contentHash()).isNotEqualTo(oldHash);
        assertThat(content.content()).contains("新内容").doesNotContain("旧内容");
    }

    private SkillProperties isolatedProperties(Path root, int maxContentChars) {
        return new SkillProperties(
                true,
                tempDir.resolve("empty-user-skills"),
                tempDir.resolve("empty-project-skills"),
                List.of(root),
                List.of(),
                maxContentChars);
    }
}
