package com.wzx.babiq.server.business.oa.client;

import com.wzx.babiq.server.business.oa.client.dto.OaWorkbenchDtos;

import java.util.List;
import java.util.Map;

/** Narrow authenticated OA port used by the workbench BFF. */
public interface OaWorkbenchGateway {
    OaWorkbenchDtos.NoticePage notices(String tenantId, char[] accessToken, int pageNo, int pageSize);
    List<Map<String, Object>> shortcuts(String tenantId, char[] accessToken);
    List<Map<String, Object>> summary(String tenantId, char[] accessToken);
    OaWorkbenchDtos.UserHomeInfo homeInfo(String tenantId, char[] accessToken);
    List<Map<String, Object>> teams(String tenantId, char[] accessToken);
    List<Map<String, Object>> teamRoles(String tenantId, char[] accessToken, String teamId, String kind);
    Object scheduleCount(String tenantId, char[] accessToken);
    Object scheduleDay(String tenantId, char[] accessToken);
    OaWorkbenchDtos.PageResult page(OaWorkbenchDtos.PageQuery query, String tenantId, char[] accessToken);

    default boolean updateSort(String tenantId, char[] accessToken, int configType, List<String> ids) {
        throw new OaWorkbenchException("REMOTE_PROTOCOL_ERROR");
    }
    default Object scheduleCount(String tenantId, char[] accessToken, String date,
                                 String scope, String teamId, boolean onlyMine) {
        return scheduleCount(tenantId, accessToken);
    }
    default Object scheduleDay(String tenantId, char[] accessToken, String date,
                               String scope, String teamId, boolean onlyMine, String typeId) {
        return scheduleDay(tenantId, accessToken);
    }
    default boolean setScheduleCompletion(String tenantId, char[] accessToken, String scheduleId, boolean completed) {
        throw new OaWorkbenchException("REMOTE_PROTOCOL_ERROR");
    }
    default List<Map<String, Object>> scheduleTypes(String tenantId, char[] accessToken, String scope, String teamId) {
        throw new OaWorkbenchException("REMOTE_PROTOCOL_ERROR");
    }
    default List<Map<String, Object>> scheduleMembers(String tenantId, char[] accessToken, String teamId) {
        throw new OaWorkbenchException("REMOTE_PROTOCOL_ERROR");
    }
    default boolean isTeamLeaderOrAdmin(String tenantId, char[] accessToken, String teamId) {
        throw new OaWorkbenchException("REMOTE_PROTOCOL_ERROR");
    }
    default List<Map<String, Object>> relationOptions(String tenantId, char[] accessToken, String relationType,
                                                       String keyword, String teamId, String parentId) {
        throw new OaWorkbenchException("REMOTE_PROTOCOL_ERROR");
    }
    default List<Map<String, Object>> serviceProjects(String tenantId, char[] accessToken, String recordId, String keyword) {
        throw new OaWorkbenchException("REMOTE_PROTOCOL_ERROR");
    }
    default Map<String, Object> createSchedule(String tenantId, char[] accessToken, Map<String, Object> payload) {
        throw new OaWorkbenchException("REMOTE_PROTOCOL_ERROR");
    }

    /** Downloads only a URL previously obtained from a trusted OA response. */
    default RemoteResource fetchResource(String tenantId, char[] accessToken, String trustedUrl) {
        throw new OaWorkbenchException("REMOTE_PROTOCOL_ERROR");
    }

    record RemoteResource(String mediaType, byte[] bytes) {
        public RemoteResource {
            if (mediaType == null || mediaType.isBlank() || bytes == null || bytes.length == 0) {
                throw new IllegalArgumentException("invalid remote resource");
            }
            bytes = bytes.clone();
        }

        @Override public byte[] bytes() {
            return bytes.clone();
        }
    }
}
