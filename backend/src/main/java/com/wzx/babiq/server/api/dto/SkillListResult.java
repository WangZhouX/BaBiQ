package com.wzx.babiq.server.api.dto;

import java.util.List;

/**
 * skills/list 响应。
 *
 * @param skills 当前可见的本地 Skill metadata 列表
 */
public record SkillListResult(List<SkillInfo> skills) {
}
