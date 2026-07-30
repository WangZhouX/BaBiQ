package com.wzx.babiq.server.business.oa.client.dto;

import java.util.List;
import java.util.Map;

/** OA workbench wire DTOs. These types never cross the desktop protocol boundary. */
public final class OaWorkbenchDtos {
    private OaWorkbenchDtos() {}

    public record NoticePage(long total, int pageNo, int pageSize, List<Map<String, Object>> items) {
        public NoticePage { items = List.copyOf(items); }
    }
    public record PageResult(long total, int pageNo, int pageSize, List<Map<String, Object>> items) {
        public PageResult { items = List.copyOf(items); }
    }
    public record UserHomeInfo(String userId, String tenantId, String nickname, String avatar,
                               Map<String, Object> values) {
        public UserHomeInfo { values = Map.copyOf(values); }
    }
    public record PageQuery(String kind, int moduleId, String scope, String teamId, String roleCode,
                            int pageNo, int pageSize, String filterValue) {}
}
