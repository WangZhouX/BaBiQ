package com.wzx.babiq.server.api.dto;

import java.util.List;

/**
 * memory/jobs/list 响应。
 */
public record MemoryJobsListResult(List<MemoryJobInfo> jobs) {
}
