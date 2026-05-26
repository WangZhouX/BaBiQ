package com.wzx.babiq.server.persistence.service;

import com.wzx.babiq.server.memory.repository.MemoryReferenceRecord;
import com.wzx.babiq.server.memory.repository.MemoryReferenceRepository;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

/**
 * MemoryReferenceRepository 的 SQLite 实现。
 */
@Repository
public class SQLiteMemoryReferenceRepository implements MemoryReferenceRepository {

    /** JDBC 访问器。 */
    private final JdbcTemplate jdbcTemplate;

    public SQLiteMemoryReferenceRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    @Transactional
    public void save(MemoryReferenceRecord record) {
        jdbcTemplate.update("""
                        INSERT INTO bq_memory_references(
                            reference_id, thread_id, turn_id, snapshot_id, artifact_id, candidate_id,
                            reference_type, token_estimate, created_at
                        ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                        ON CONFLICT(reference_id) DO NOTHING
                        """,
                record.referenceId(), record.threadId(), record.turnId(), record.snapshotId(), record.artifactId(),
                record.candidateId(), record.referenceType(), record.tokenEstimate(), write(record.createdAt()));
    }

    private static String write(Instant instant) {
        return instant == null ? null : instant.toString();
    }
}
