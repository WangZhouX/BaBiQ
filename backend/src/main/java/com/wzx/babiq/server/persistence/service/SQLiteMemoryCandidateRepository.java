package com.wzx.babiq.server.persistence.service;

import com.wzx.babiq.server.memory.redaction.MemoryPollutionStatus;
import com.wzx.babiq.server.memory.repository.MemoryCandidateRecord;
import com.wzx.babiq.server.memory.repository.MemoryCandidateRepository;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;

/**
 * MemoryCandidateRepository 的 SQLite 实现。
 *
 * <p>候选选择规则参考 Codex：优先保留被使用次数多、最近使用、最近生成的候选，
 * 并限制每次 Phase2 的输入规模。</p>
 */
@Repository
public class SQLiteMemoryCandidateRepository implements MemoryCandidateRepository {

    /** JDBC 访问器。 */
    private final JdbcTemplate jdbcTemplate;

    public SQLiteMemoryCandidateRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public long countUnmergedCleanCandidates() {
        Long count = jdbcTemplate.queryForObject("""
                        SELECT COUNT(*) FROM bq_memory_candidates
                        WHERE pollution_status = 'CLEAN' AND selected_for_phase2 = 0
                        """,
                Long.class);
        return count == null ? 0 : count;
    }

    @Override
    public List<MemoryCandidateRecord> selectForPhase2(int limit) {
        return jdbcTemplate.query("""
                        SELECT * FROM bq_memory_candidates
                        WHERE pollution_status = 'CLEAN' AND selected_for_phase2 = 0
                        ORDER BY usage_count DESC,
                                 COALESCE(last_used_at, created_at) DESC,
                                 created_at DESC,
                                 thread_id DESC
                        LIMIT ?
                        """,
                (rs, rowNum) -> toRecord(rs), limit);
    }

    @Override
    @Transactional
    public void save(MemoryCandidateRecord record) {
        jdbcTemplate.update("""
                        INSERT INTO bq_memory_candidates(
                            candidate_id, thread_id, turn_id, job_id, cwd, provider_id, model, raw_memory,
                            rollout_summary, rollout_slug, source_item_ids_json, source_snapshot_id,
                            pollution_status, redaction_count, selected_for_phase2, selected_at,
                            usage_count, last_used_at, created_at, updated_at
                        ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                        ON CONFLICT(candidate_id) DO UPDATE SET
                            thread_id = excluded.thread_id,
                            turn_id = excluded.turn_id,
                            job_id = excluded.job_id,
                            cwd = excluded.cwd,
                            provider_id = excluded.provider_id,
                            model = excluded.model,
                            raw_memory = excluded.raw_memory,
                            rollout_summary = excluded.rollout_summary,
                            rollout_slug = excluded.rollout_slug,
                            source_item_ids_json = excluded.source_item_ids_json,
                            source_snapshot_id = excluded.source_snapshot_id,
                            pollution_status = excluded.pollution_status,
                            redaction_count = excluded.redaction_count,
                            selected_for_phase2 = excluded.selected_for_phase2,
                            selected_at = excluded.selected_at,
                            usage_count = excluded.usage_count,
                            last_used_at = excluded.last_used_at,
                            updated_at = excluded.updated_at
                        """,
                record.candidateId(), record.threadId(), record.turnId(), record.jobId(), record.cwd(),
                record.providerId(), record.model(), record.rawMemory(), record.rolloutSummary(), record.rolloutSlug(),
                record.sourceItemIdsJson(), record.sourceSnapshotId(), record.pollutionStatus().name(),
                record.redactionCount(), record.selectedForPhase2() ? 1 : 0, write(record.selectedAt()),
                record.usageCount(), write(record.lastUsedAt()), write(record.createdAt()), write(record.updatedAt()));
    }

    @Override
    @Transactional
    public void markSelected(List<String> candidateIds, Instant selectedAt) {
        if (candidateIds == null || candidateIds.isEmpty()) {
            return;
        }
        for (String candidateId : candidateIds) {
            jdbcTemplate.update("""
                            UPDATE bq_memory_candidates
                            SET selected_for_phase2 = 1, selected_at = ?, updated_at = ?
                            WHERE candidate_id = ?
                            """,
                    write(selectedAt), write(selectedAt), candidateId);
        }
    }

    private static MemoryCandidateRecord toRecord(ResultSet rs) throws SQLException {
        return new MemoryCandidateRecord(
                rs.getString("candidate_id"),
                rs.getString("thread_id"),
                rs.getString("turn_id"),
                rs.getString("job_id"),
                rs.getString("cwd"),
                rs.getString("provider_id"),
                rs.getString("model"),
                rs.getString("raw_memory"),
                rs.getString("rollout_summary"),
                rs.getString("rollout_slug"),
                rs.getString("source_item_ids_json"),
                rs.getString("source_snapshot_id"),
                MemoryPollutionStatus.valueOf(rs.getString("pollution_status")),
                rs.getInt("redaction_count"),
                rs.getInt("selected_for_phase2") == 1,
                read(rs.getString("selected_at")),
                rs.getInt("usage_count"),
                read(rs.getString("last_used_at")),
                read(rs.getString("created_at")),
                read(rs.getString("updated_at")));
    }

    private static String write(Instant instant) {
        return instant == null ? null : instant.toString();
    }

    private static Instant read(String value) {
        return value == null || value.isBlank() ? null : Instant.parse(value);
    }
}
