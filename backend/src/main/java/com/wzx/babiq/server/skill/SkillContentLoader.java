package com.wzx.babiq.server.skill;

import org.springframework.stereotype.Service;

/**
 * Skill 正文加载器。
 *
 * <p>metadata 可以被能力目录常驻扫描，正文只在用户显式查看或模型按需装配命中后读取。
 * 读取动作统一委托给 Spring AI Alibaba 官方 SkillRegistry，BaBiQ 只负责字符预算截断。</p>
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
            String content = registry.readFullContent(descriptor);
            int limit = properties.maxContentChars();
            boolean truncated = content.length() > limit;
            String clipped = truncated ? content.substring(0, limit) : content;
            return new SkillContent(descriptor, clipped, truncated);
        } catch (Exception exception) {
            throw new IllegalStateException("读取 Skill 失败: " + skillId, exception);
        }
    }
}
