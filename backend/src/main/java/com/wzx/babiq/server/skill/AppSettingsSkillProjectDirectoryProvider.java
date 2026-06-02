package com.wzx.babiq.server.skill;

import com.wzx.babiq.server.settings.AppSettingsService;
import org.springframework.stereotype.Service;

import java.nio.file.Path;

/**
 * 从应用设置读取当前工作区，并换算为项目级 Skill 目录。
 */
@Service
public class AppSettingsSkillProjectDirectoryProvider implements SkillProjectDirectoryProvider {

    private final AppSettingsService appSettingsService;

    public AppSettingsSkillProjectDirectoryProvider(AppSettingsService appSettingsService) {
        this.appSettingsService = appSettingsService;
    }

    @Override
    public Path projectSkillsDirectory() {
        return Path.of(appSettingsService.get().defaultCwd())
                .toAbsolutePath()
                .normalize()
                .resolve(".agents")
                .resolve("skills");
    }
}
