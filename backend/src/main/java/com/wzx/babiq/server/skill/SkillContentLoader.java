package com.wzx.babiq.server.skill;

import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Skill 正文加载器。
 *
 * <p>metadata 可以被能力目录常驻扫描，但正文只在用户显式查看或模型搜索命中后按需读取。
 * 该类统一做字符预算截断，防止一个超长 SKILL.md 撑爆上下文窗口。</p>
 */
@Service
public class SkillContentLoader {

    /** Skill metadata 注册表。 */
    private final LocalSkillRegistry registry;
    /** Skill 扫描配置。 */
    private final SkillProperties properties;

    /**
     * 创建 Skill 正文加载器。
     */
    public SkillContentLoader(LocalSkillRegistry registry, SkillProperties properties) {
        this.registry = registry;
        this.properties = properties;
    }

    /**
     * 加载一个 Skill 正文。
     */
    public SkillContent load(String skillId) {
        SkillDescriptor descriptor = registry.findById(skillId)
                .orElseThrow(() -> new IllegalArgumentException("未知 Skill: " + skillId));
        try {
            String content = Files.readString(Path.of(descriptor.skillFile()), StandardCharsets.UTF_8);
            int limit = properties.maxContentChars();
            boolean truncated = content.length() > limit;
            String clipped = truncated ? content.substring(0, limit) : content;
            return new SkillContent(descriptor, clipped, truncated);
        } catch (Exception exception) {
            throw new IllegalStateException("读取 Skill 失败: " + skillId, exception);
        }
    }
}
