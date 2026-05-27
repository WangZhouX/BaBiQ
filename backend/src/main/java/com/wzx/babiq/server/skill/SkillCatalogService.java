package com.wzx.babiq.server.skill;

import com.wzx.babiq.server.api.dto.SkillGetResult;
import com.wzx.babiq.server.api.dto.SkillInfo;
import com.wzx.babiq.server.api.dto.SkillListResult;
import org.springframework.stereotype.Service;

/**
 * 本地 Skill 应用服务。
 *
 * <p>能力目录只常驻 Skill metadata；正文只有用户或后续按需装配明确请求时才会读取，
 * 这样可以避免把所有 Skill 说明一次性塞进模型上下文。</p>
 */
@Service
public class SkillCatalogService {

    /** Skill metadata 注册表。 */
    private final LocalSkillRegistry registry;
    /** Skill 正文加载器。 */
    private final SkillContentLoader contentLoader;

    public SkillCatalogService(LocalSkillRegistry registry, SkillContentLoader contentLoader) {
        this.registry = registry;
        this.contentLoader = contentLoader;
    }

    /**
     * 列出当前可见 Skill metadata。
     */
    public SkillListResult list() {
        return new SkillListResult(registry.listSkills().stream().map(this::toInfo).toList());
    }

    /**
     * 读取单个 Skill 正文。
     */
    public SkillGetResult get(String skillId) {
        SkillContent content = contentLoader.load(skillId);
        return new SkillGetResult(toInfo(content.descriptor()), content.content(), content.truncated());
    }

    private SkillInfo toInfo(SkillDescriptor descriptor) {
        return new SkillInfo(
                descriptor.id(),
                descriptor.namespace(),
                descriptor.name(),
                descriptor.description(),
                descriptor.sourceDirectory(),
                descriptor.skillFile(),
                descriptor.contentHash());
    }
}
