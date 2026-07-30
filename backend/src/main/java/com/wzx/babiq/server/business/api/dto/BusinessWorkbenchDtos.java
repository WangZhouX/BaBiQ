package com.wzx.babiq.server.business.api.dto;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.time.Instant;

/** Stable desktop-facing workbench DTOs. Remote OA implementation fields are excluded. */
public final class BusinessWorkbenchDtos {
    private BusinessWorkbenchDtos() {}

    public record PageRequest(String kind, String scope, String teamId, String roleCode,
                              int pageNo, int pageSize, Map<String, Object> filters) {
        public PageRequest {
            filters = filters == null ? Map.of() : Map.copyOf(filters);
        }
        public PageRequest withTeamId(String value) { return new PageRequest(kind, scope, value, roleCode, pageNo, pageSize, filters); }
        public PageRequest withRoleCode(String value) { return new PageRequest(kind, scope, teamId, value, pageNo, pageSize, filters); }
    }
    public record PageResult(long total, int pageNo, int pageSize, List<PageRow> items) {
        public PageResult { items = List.copyOf(items); }
    }
    public record PageRow(String id, String applicationNumber, String categoriesName, String scheduleName,
                          String title, Map<String, Object> values) {
        public PageRow { values = values == null ? Map.of() : Map.copyOf(values); }
    }
    public record Snapshot(Section notices, Section shortcuts, Section summary, Section profile,
                           Section teams, Section schedule, List<String> issues) {
        public Snapshot { issues = List.copyOf(issues); }
    }

    /** Stable section envelope used by the desktop client; remote URL/token fields never cross this boundary. */
    public record Section(String status, Object data) {
        public Section {
            if (status == null || status.isBlank()) throw new IllegalArgumentException("status must not be blank");
        }
        public static Section ok(Object data) { return new Section("OK", data); }
        public static Section error() { return new Section("ERROR", null); }
        public static Section empty() { return new Section("EMPTY", null); }
    }

    public record SnapshotEnvelope(long identityEpoch, long generation, Snapshot snapshot) {
        public SnapshotEnvelope {
            if (identityEpoch <= 0) throw new IllegalArgumentException("identityEpoch must be positive");
            if (generation < 0) throw new IllegalArgumentException("generation must not be negative");
            if (snapshot == null) throw new IllegalArgumentException("snapshot must not be null");
        }
    }

    public record NavigationTarget(String kind, String path, String title) {
        public NavigationTarget {
            if (kind == null || kind.isBlank() || path == null || path.isBlank() || title == null || title.isBlank()) {
                throw new IllegalArgumentException("navigation target fields must not be blank");
            }
        }
    }

    public record NavigationEnvelope(long identityEpoch, long generation, List<NavigationTarget> items) {
        public NavigationEnvelope {
            if (identityEpoch <= 0) throw new IllegalArgumentException("identityEpoch must be positive");
            if (generation < 0) throw new IllegalArgumentException("generation must not be negative");
            items = List.copyOf(items == null ? List.of() : items);
        }
    }

    public record HomeInfoEnvelope(long identityEpoch, long generation, Section section) {
        public HomeInfoEnvelope {
            if (identityEpoch <= 0) throw new IllegalArgumentException("identityEpoch must be positive");
            if (generation < 0) throw new IllegalArgumentException("generation must not be negative");
            if (section == null) throw new IllegalArgumentException("section must not be null");
        }
    }

    public record TeamRole(String roleCode, String name) {
        public TeamRole {
            if (roleCode == null || roleCode.isBlank()) throw new IllegalArgumentException("roleCode must not be blank");
            name = name == null ? roleCode : name;
        }
    }

    public record TeamRolesEnvelope(long identityEpoch, long generation, List<TeamRole> items) {
        public TeamRolesEnvelope {
            if (identityEpoch <= 0) throw new IllegalArgumentException("identityEpoch must be positive");
            if (generation < 0) throw new IllegalArgumentException("generation must not be negative");
            items = List.copyOf(items == null ? List.of() : items);
        }
    }

    /** Typed sort contract; the remote OA configType is intentionally not exposed. */
    public record SortRequest(String kind, List<String> ids, long expectedRevision) {
        public SortRequest {
            if (kind == null || kind.isBlank()) throw new IllegalArgumentException("sort kind must not be blank");
            ids = List.copyOf(ids == null ? List.of() : ids);
            if (ids.isEmpty() || ids.size() > 100 || ids.stream().anyMatch(
                    id -> id == null || id.isBlank() || id.length() > 256)) {
                throw new IllegalArgumentException("invalid sort ids");
            }
            if (expectedRevision < 0) throw new IllegalArgumentException("expected revision must not be negative");
        }
    }

    public record MutationEnvelope(long identityEpoch, long generation, long revision, boolean refreshRequired) {
        public MutationEnvelope {
            if (identityEpoch <= 0 || generation < 0 || revision < 0) throw new IllegalArgumentException("invalid mutation envelope");
        }
    }

    public record ScheduleQuery(String date, String scope, String teamId, boolean onlyMine, String typeId) {
        public ScheduleQuery {
            if (date == null || date.isBlank()) throw new IllegalArgumentException("date must not be blank");
            if (scope == null || !Set.of("ALL", "PERSONAL", "TEAM").contains(scope)) throw new IllegalArgumentException("invalid schedule scope");
            if ("TEAM".equals(scope) && (teamId == null || teamId.isBlank())) throw new IllegalArgumentException("TEAM requires teamId");
            if (!"TEAM".equals(scope) && teamId != null) throw new IllegalArgumentException("teamId requires TEAM scope");
            typeId = typeId == null || typeId.isBlank() ? null : typeId;
        }
    }

    public record ScheduleEnvelope(long identityEpoch, long generation, Object data) {
        public ScheduleEnvelope {
            if (identityEpoch <= 0 || generation < 0) throw new IllegalArgumentException("invalid schedule envelope");
        }
    }

    public record ScheduleMonthEntry(String date, int count) {
        public ScheduleMonthEntry {
            if (date == null || date.isBlank() || count < 0) {
                throw new IllegalArgumentException("invalid schedule month entry");
            }
        }
    }

    public record ScheduleMonthEnvelope(long identityEpoch, long generation, List<ScheduleMonthEntry> days) {
        public ScheduleMonthEnvelope {
            if (identityEpoch <= 0 || generation < 0) {
                throw new IllegalArgumentException("invalid schedule month envelope");
            }
            days = List.copyOf(days == null ? List.of() : days);
        }
    }

    public record ScheduleDayItem(String id, String title, String at, boolean completed,
                                  String typeTitle, String color, Integer priority,
                                  Integer repetition, Integer expiredDays) {
        public ScheduleDayItem {
            if (id == null || id.isBlank()) throw new IllegalArgumentException("invalid schedule item id");
            title = title == null || title.isBlank() ? "日程" : title;
            at = at == null ? "" : at;
        }

        public ScheduleDayItem(String id, String title, String at, boolean completed) {
            this(id, title, at, completed, null, null, null, null, null);
        }
    }

    public record ScheduleDayGroup(String time, boolean allDay, List<ScheduleDayItem> items) {
        public ScheduleDayGroup {
            time = time == null ? "" : time;
            items = List.copyOf(items == null ? List.of() : items);
        }
    }

    public record ScheduleDayEnvelope(long identityEpoch, long generation, List<ScheduleDayGroup> groups) {
        public ScheduleDayEnvelope {
            if (identityEpoch <= 0 || generation < 0) {
                throw new IllegalArgumentException("invalid schedule day envelope");
            }
            groups = List.copyOf(groups == null ? List.of() : groups);
        }
    }

    public record ScheduleCompletionResult(long identityEpoch, long generation, boolean completed,
                                           boolean refreshRequired, long revision) {
        public ScheduleCompletionResult {
            if (identityEpoch <= 0 || generation < 0 || revision < 0) throw new IllegalArgumentException("invalid completion result");
        }
    }

    /** Desktop-visible schedule form. Values are bounded, sanitized option records. */
    public record ScheduleFormEnvelope(long identityEpoch, long generation, long revision,
                                       List<Map<String, Object>> types, List<Map<String, Object>> members) {
        public ScheduleFormEnvelope {
            if (identityEpoch <= 0 || generation < 0 || revision < 0) throw new IllegalArgumentException("invalid form envelope");
            types = List.copyOf(types == null ? List.of() : types);
            members = List.copyOf(members == null ? List.of() : members);
        }
    }

    public record RelationOptionsEnvelope(long identityEpoch, long generation, long revision,
                                          String relationType, List<Map<String, Object>> items) {
        public RelationOptionsEnvelope {
            if (identityEpoch <= 0 || generation < 0 || revision < 0) throw new IllegalArgumentException("invalid relation envelope");
            if (relationType == null || relationType.isBlank()) throw new IllegalArgumentException("relationType must not be blank");
            items = List.copyOf(items == null ? List.of() : items);
        }
    }

    public record ScheduleCreateRequest(String clientOperationId, String scope, String teamId, String assigneeUserId,
                                        String title, String typeId, String at, boolean allDay, int priority,
                                        String description, List<Integer> reminderMinutes, List<Map<String, Object>> relations,
                                        String attachmentBatchId, String attachmentParentResourceId,
                                        String attachmentParentRelationType, long formRevision, int repetition) {
        /** Backward-compatible constructor for schedule requests without explicit attachment parent metadata. */
        public ScheduleCreateRequest(String clientOperationId, String scope, String teamId, String assigneeUserId,
                                     String title, String typeId, String at, boolean allDay, int priority,
                                     String description, List<Integer> reminderMinutes, List<Map<String, Object>> relations,
                                     String attachmentBatchId) {
            this(clientOperationId, scope, teamId, assigneeUserId, title, typeId, at, allDay, priority,
                    description, reminderMinutes, relations, attachmentBatchId, null, null, 0, 0);
        }

        public ScheduleCreateRequest(String clientOperationId, String scope, String teamId, String assigneeUserId,
                                     String title, String typeId, String at, boolean allDay, int priority,
                                     String description, List<Integer> reminderMinutes, List<Map<String, Object>> relations,
                                     String attachmentBatchId, String attachmentParentResourceId) {
            this(clientOperationId, scope, teamId, assigneeUserId, title, typeId, at, allDay, priority,
                    description, reminderMinutes, relations, attachmentBatchId, attachmentParentResourceId,
                    null, 0, 0);
        }

        public ScheduleCreateRequest(String clientOperationId, String scope, String teamId, String assigneeUserId,
                                     String title, String typeId, String at, boolean allDay, int priority,
                                     String description, List<Integer> reminderMinutes, List<Map<String, Object>> relations,
                                     String attachmentBatchId, String attachmentParentResourceId,
                                     String attachmentParentRelationType, long formRevision) {
            this(clientOperationId, scope, teamId, assigneeUserId, title, typeId, at, allDay, priority,
                    description, reminderMinutes, relations, attachmentBatchId, attachmentParentResourceId,
                    attachmentParentRelationType, formRevision, 0);
        }

        public ScheduleCreateRequest {
            if (clientOperationId == null || clientOperationId.isBlank()) throw new IllegalArgumentException("clientOperationId must not be blank");
            if (scope == null || !Set.of("PERSONAL", "TEAM").contains(scope)) throw new IllegalArgumentException("invalid schedule scope");
            if (title == null || title.isBlank() || title.length() > 50) throw new IllegalArgumentException("invalid schedule title");
            if (typeId == null || typeId.isBlank() || at == null || at.isBlank()) throw new IllegalArgumentException("schedule type/time is required");
            if (priority < 1 || priority > 4) throw new IllegalArgumentException("invalid schedule priority");
            if ("TEAM".equals(scope) && (teamId == null || teamId.isBlank())) throw new IllegalArgumentException("TEAM requires teamId");
            if ("TEAM".equals(scope) && (assigneeUserId == null || assigneeUserId.isBlank())) {
                throw new IllegalArgumentException("TEAM requires assigneeUserId");
            }
            if ("PERSONAL".equals(scope) && teamId != null) throw new IllegalArgumentException("personal schedule must not include teamId");
            reminderMinutes = List.copyOf(reminderMinutes == null ? List.of() : reminderMinutes);
            relations = List.copyOf(relations == null ? List.of() : relations);
            if (description != null && description.length() > 200) {
                throw new IllegalArgumentException("invalid schedule description");
            }
            if (repetition < 0 || repetition > 4) throw new IllegalArgumentException("invalid schedule repetition");
            if (repetition != 0 && relations.stream().anyMatch(relation -> relation != null
                    && Set.of("VISIT", "SERVICE").contains(relation.get("relationType")))) {
                throw new IllegalArgumentException("visit and service schedules cannot repeat");
            }
            attachmentBatchId = attachmentBatchId == null || attachmentBatchId.isBlank() ? null : attachmentBatchId;
            attachmentParentResourceId = attachmentParentResourceId == null || attachmentParentResourceId.isBlank()
                    ? null : attachmentParentResourceId;
            if (attachmentBatchId != null && attachmentParentResourceId == null) {
                throw new IllegalArgumentException("attachment parent resource is required");
            }
            if (attachmentBatchId == null && attachmentParentResourceId != null) {
                throw new IllegalArgumentException("attachment parent requires a batch");
            }
            attachmentParentRelationType = attachmentParentRelationType == null
                    || attachmentParentRelationType.isBlank() ? null : attachmentParentRelationType;
            if (attachmentParentRelationType != null
                    && !Set.of("CASE", "CUSTOMER", "VISIT", "SERVICE").contains(attachmentParentRelationType)) {
                throw new IllegalArgumentException("invalid attachment parent relation type");
            }
            if (formRevision < 0) throw new IllegalArgumentException("invalid form revision");
        }
    }
}
