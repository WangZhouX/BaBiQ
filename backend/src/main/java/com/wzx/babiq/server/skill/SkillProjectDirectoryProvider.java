package com.wzx.babiq.server.skill;

import java.nio.file.Path;

/**
 * 提供当前项目级 Skill 目录。
 *
 * <p>BaBiQ 的当前工作区会在运行中切换，项目 Skill 目录必须跟随当前 cwd，
 * 不能只使用应用启动目录下的固定路径。</p>
 */
@FunctionalInterface
public interface SkillProjectDirectoryProvider {

    /**
     * 当前项目的 `.agents/skills` 目录。
     */
    Path projectSkillsDirectory();
}
