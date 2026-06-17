package com.wzx.babiq.server.api.dto;

import java.util.List;

/**
 * team/list 返回结果。
 *
 * @param teams 当前会话下的团队运行摘要
 */
public record TeamListResult(List<TeamInfo> teams) {
}
