package com.wzx.babiq.server.skill;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * P3-6 Skill 路径配置测试。
 */
class SkillPropertiesTest {

    @Test
    @DisplayName("默认只使用工具中立的 .agents/skills 路径，不再隐式扫描 .codex")
    void defaults_should_point_to_agents_skills_without_codex_legacy_paths() {
        SkillProperties properties = new SkillProperties(null, null, null, null, null, 0);

        assertThat(properties.userSkillsDirectory())
                .isEqualTo(Path.of(System.getProperty("user.home"), ".agents", "skills"));
        assertThat(properties.projectSkillsDirectory())
                .isEqualTo(Path.of("").toAbsolutePath().normalize().resolve(".agents").resolve("skills"));
        assertThat(properties.additionalDirectories()).isEmpty();
        assertThat(properties.directories()).isEmpty();
        assertThat(properties.allConfiguredDirectories())
                .noneSatisfy(path -> assertThat(path.toString()).contains(".codex"));
        assertThat(properties.maxContentChars()).isEqualTo(16_000);
    }

    @Test
    @DisplayName("旧 .codex 技能目录必须显式放进 additionalDirectories 才会参与扫描")
    void legacy_codex_directory_should_be_opt_in_additional_directory() {
        Path legacy = Path.of(System.getProperty("user.home"), ".codex", "skills");
        SkillProperties properties = new SkillProperties(true, null, null, java.util.List.of(legacy), null, 8_000);

        assertThat(properties.additionalDirectories()).containsExactly(legacy);
        assertThat(properties.allConfiguredDirectories()).contains(legacy);
    }

    @Test
    @DisplayName("旧 directories 字段也必须显式配置才会恢复旧目录")
    void legacy_directories_field_should_remain_explicit_opt_in() {
        Path legacy = Path.of(System.getProperty("user.home"), ".codex", "skills");
        SkillProperties properties = new SkillProperties(true, null, null, null, java.util.List.of(legacy), 8_000);

        assertThat(properties.directories()).containsExactly(legacy);
        assertThat(properties.allConfiguredDirectories()).contains(legacy);
    }
}
