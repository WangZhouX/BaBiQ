package com.wzx.babiq.server.api.method;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.wzx.babiq.server.api.dto.SkillGetResult;
import com.wzx.babiq.server.api.dto.SkillListResult;
import com.wzx.babiq.server.skill.LocalSkillRegistry;
import com.wzx.babiq.server.skill.SkillCatalogService;
import com.wzx.babiq.server.skill.SkillContentLoader;
import com.wzx.babiq.server.skill.SkillProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * P3-5 Skill JSON-RPC handler 测试。
 */
class SkillHandlersTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @TempDir
    Path tempDir;

    @Test
    @DisplayName("skills/list 和 skills/get 从允许目录读取 Skill metadata 与正文")
    void skill_handlers_should_list_and_load_content() throws Exception {
        Path skillDir = tempDir.resolve("agent").resolve("context");
        Files.createDirectories(skillDir);
        Files.writeString(skillDir.resolve("SKILL.md"), """
                ---
                name: context
                description: 上下文治理 Skill
                ---
                # Context
                使用分层上下文窗口。
                """);
        SkillProperties properties = new SkillProperties(true, List.of(tempDir), 1_000);
        LocalSkillRegistry registry = new LocalSkillRegistry(properties);
        SkillCatalogService service = new SkillCatalogService(registry, new SkillContentLoader(registry, properties));

        Object listResult = new SkillsListHandler(service).handle(objectMapper.nullNode(), null);
        assertThat(listResult).isInstanceOf(SkillListResult.class);
        SkillListResult skills = (SkillListResult) listResult;
        assertThat(skills.skills()).hasSize(1);

        Object getResult = new SkillsGetHandler(service)
                .handle(objectMapper.valueToTree(Map.of("skillId", skills.skills().get(0).id())), null);
        assertThat(getResult).isInstanceOf(SkillGetResult.class);
        SkillGetResult skill = (SkillGetResult) getResult;
        assertThat(skill.content()).contains("分层上下文窗口");
        assertThat(skill.truncated()).isFalse();
    }
}
