package com.wzx.babiq.server.persistence.service;

import com.wzx.babiq.server.capability.CapabilityDescriptor;
import com.wzx.babiq.server.capability.CapabilityExposureMode;
import com.wzx.babiq.server.capability.CapabilityRepository;
import com.wzx.babiq.server.capability.CapabilitySearchEventRecord;
import com.wzx.babiq.server.capability.CapabilityType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * CapabilityRepository 的 SQLite 实现。
 *
 * <p>这里使用 JdbcTemplate 做 upsert 和 JSON 审计写入，保持和 P3-4 长期记忆 repository 的风格一致。
 * MyBatis-Plus Mapper 仍保留表结构可见性，业务层不直接依赖 Mapper。</p>
 */
@Repository
public class SQLiteCapabilityRepository implements CapabilityRepository {

    /** JDBC 访问器。 */
    private final JdbcTemplate jdbcTemplate;

    /**
     * 创建 SQLite 能力目录仓库。
     */
    public SQLiteCapabilityRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    @Transactional
    public void upsert(CapabilityDescriptor descriptor) {
        Instant now = Instant.now();
        Optional<CapabilityDescriptor> existing = findById(descriptor.capabilityId());
        boolean enabled = existing.map(CapabilityDescriptor::enabled).orElse(descriptor.enabled());
        CapabilityExposureMode exposureMode = existing.map(CapabilityDescriptor::exposureMode)
                .orElse(descriptor.exposureMode());
        jdbcTemplate.update("""
                        INSERT INTO bq_capabilities(
                            capability_id, type, namespace, name, display_name, description, source_id,
                            schema_hash, search_text, exposure_mode, enabled, last_seen_at, created_at, updated_at
                        ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                        ON CONFLICT(capability_id) DO UPDATE SET
                            type = excluded.type,
                            namespace = excluded.namespace,
                            name = excluded.name,
                            display_name = excluded.display_name,
                            description = excluded.description,
                            source_id = excluded.source_id,
                            schema_hash = excluded.schema_hash,
                            search_text = excluded.search_text,
                            exposure_mode = ?,
                            enabled = ?,
                            last_seen_at = excluded.last_seen_at,
                            updated_at = excluded.updated_at
                        """,
                descriptor.capabilityId(),
                descriptor.type().name(),
                descriptor.namespace(),
                descriptor.name(),
                descriptor.displayName(),
                descriptor.description(),
                descriptor.sourceId(),
                descriptor.schemaHash(),
                descriptor.searchText(),
                exposureMode.name(),
                enabled ? 1 : 0,
                write(descriptor.lastSeenAt()),
                write(now),
                write(now),
                exposureMode.name(),
                enabled ? 1 : 0);
    }

    @Override
    public List<CapabilityDescriptor> listAll() {
        return jdbcTemplate.query("SELECT * FROM bq_capabilities ORDER BY type, namespace, name",
                (rs, rowNum) -> toDescriptor(rs));
    }

    @Override
    public List<CapabilityDescriptor> listEnabled() {
        return jdbcTemplate.query("""
                        SELECT * FROM bq_capabilities
                        WHERE enabled = 1
                        ORDER BY type, namespace, name
                        """,
                (rs, rowNum) -> toDescriptor(rs));
    }

    @Override
    public Optional<CapabilityDescriptor> findById(String capabilityId) {
        return jdbcTemplate.query("SELECT * FROM bq_capabilities WHERE capability_id = ?",
                (rs, rowNum) -> toDescriptor(rs), capabilityId).stream().findFirst();
    }

    @Override
    @Transactional
    public void updateSettings(String capabilityId, Boolean enabled, CapabilityExposureMode exposureMode) {
        CapabilityDescriptor existing = findById(capabilityId)
                .orElseThrow(() -> new IllegalArgumentException("未知能力: " + capabilityId));
        jdbcTemplate.update("""
                        UPDATE bq_capabilities
                        SET enabled = ?, exposure_mode = ?, updated_at = ?
                        WHERE capability_id = ?
                        """,
                enabled == null ? (existing.enabled() ? 1 : 0) : (enabled ? 1 : 0),
                exposureMode == null ? existing.exposureMode().name() : exposureMode.name(),
                write(Instant.now()),
                capabilityId);
    }

    @Override
    public void recordSearchEvent(CapabilitySearchEventRecord record) {
        jdbcTemplate.update("""
                        INSERT INTO bq_capability_search_events(
                            event_id, thread_id, turn_id, query_text, strategy, result_count,
                            selected_capability_ids_json, rejected_capability_ids_json, created_at
                        ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                        """,
                record.eventId(),
                record.threadId(),
                record.turnId(),
                record.queryText(),
                record.strategy(),
                record.resultCount(),
                record.selectedCapabilityIdsJson(),
                record.rejectedCapabilityIdsJson(),
                write(record.createdAt()));
    }

    @Override
    public List<String> recentSelectedCapabilityIds(String threadId, int limit) {
        if (threadId == null || threadId.isBlank() || limit <= 0) {
            return List.of();
        }
        List<String> values = jdbcTemplate.query("""
                        SELECT selected_capability_ids_json
                        FROM bq_capability_search_events
                        WHERE thread_id = ?
                        ORDER BY created_at DESC
                        LIMIT ?
                        """,
                (rs, rowNum) -> rs.getString(1), threadId, limit);
        List<String> ids = new ArrayList<>();
        for (String json : values) {
            for (String token : json.replace("[", "").replace("]", "").replace("\"", "").split(",")) {
                String id = token.trim();
                if (!id.isBlank() && !ids.contains(id)) {
                    ids.add(id);
                }
            }
            if (ids.size() >= limit) {
                break;
            }
        }
        return ids;
    }

    private static CapabilityDescriptor toDescriptor(ResultSet rs) throws SQLException {
        return new CapabilityDescriptor(
                rs.getString("capability_id"),
                CapabilityType.valueOf(rs.getString("type")),
                rs.getString("namespace"),
                rs.getString("name"),
                rs.getString("display_name"),
                rs.getString("description"),
                rs.getString("source_id"),
                rs.getString("schema_hash"),
                rs.getString("search_text"),
                CapabilityExposureMode.valueOf(rs.getString("exposure_mode")),
                rs.getInt("enabled") == 1,
                read(rs.getString("last_seen_at")));
    }

    private static String write(Instant instant) {
        return (instant == null ? Instant.now() : instant).toString();
    }

    private static Instant read(String value) {
        return value == null || value.isBlank() ? null : Instant.parse(value);
    }
}
