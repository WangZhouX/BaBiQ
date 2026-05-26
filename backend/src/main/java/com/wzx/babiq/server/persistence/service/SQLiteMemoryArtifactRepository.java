package com.wzx.babiq.server.persistence.service;

import com.wzx.babiq.server.memory.repository.MemoryArtifactRecord;
import com.wzx.babiq.server.memory.repository.MemoryArtifactRepository;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * MemoryArtifactRepository 的 SQLite 实现。
 */
@Repository
public class SQLiteMemoryArtifactRepository implements MemoryArtifactRepository {

    /** JDBC 访问器。 */
    private final JdbcTemplate jdbcTemplate;

    public SQLiteMemoryArtifactRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    @Transactional
    public void save(MemoryArtifactRecord record) {
        jdbcTemplate.update("""
                        INSERT INTO bq_memory_artifacts(
                            artifact_id, artifact_type, artifact_path, content_hash, version, source_job_id,
                            candidate_ids_json, summary_text, token_estimate, created_at, updated_at
                        ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                        ON CONFLICT(artifact_type, version) DO UPDATE SET
                            artifact_id = excluded.artifact_id,
                            artifact_path = excluded.artifact_path,
                            content_hash = excluded.content_hash,
                            source_job_id = excluded.source_job_id,
                            candidate_ids_json = excluded.candidate_ids_json,
                            summary_text = excluded.summary_text,
                            token_estimate = excluded.token_estimate,
                            updated_at = excluded.updated_at
                        """,
                record.artifactId(), record.artifactType(), record.artifactPath(), record.contentHash(),
                record.version(), record.sourceJobId(), record.candidateIdsJson(), record.summaryText(),
                record.tokenEstimate(), write(record.createdAt()), write(record.updatedAt()));
    }

    @Override
    public Optional<MemoryArtifactRecord> findLatestByType(String artifactType) {
        return jdbcTemplate.query("""
                        SELECT * FROM bq_memory_artifacts
                        WHERE artifact_type = ?
                        ORDER BY version DESC, created_at DESC
                        LIMIT 1
                        """,
                (rs, rowNum) -> toRecord(rs), artifactType).stream().findFirst();
    }

    @Override
    public List<MemoryArtifactRecord> listLatest(int limit) {
        return jdbcTemplate.query("SELECT * FROM bq_memory_artifacts ORDER BY created_at DESC LIMIT ?",
                (rs, rowNum) -> toRecord(rs), limit);
    }

    private static MemoryArtifactRecord toRecord(ResultSet rs) throws SQLException {
        return new MemoryArtifactRecord(
                rs.getString("artifact_id"),
                rs.getString("artifact_type"),
                rs.getString("artifact_path"),
                rs.getString("content_hash"),
                rs.getInt("version"),
                rs.getString("source_job_id"),
                rs.getString("candidate_ids_json"),
                rs.getString("summary_text"),
                rs.getInt("token_estimate"),
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
