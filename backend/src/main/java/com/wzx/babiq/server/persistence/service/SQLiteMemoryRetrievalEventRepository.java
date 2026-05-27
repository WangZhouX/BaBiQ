package com.wzx.babiq.server.persistence.service;

import com.wzx.babiq.server.memory.retrieval.MemoryRetrievalEventRecord;
import com.wzx.babiq.server.memory.retrieval.MemoryRetrievalEventRepository;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.time.Instant;

/**
 * MemoryRetrievalEventRepository 的 SQLite 实现。
 */
@Repository
public class SQLiteMemoryRetrievalEventRepository implements MemoryRetrievalEventRepository {

    /** JDBC 访问器。 */
    private final JdbcTemplate jdbcTemplate;

    /**
     * 创建检索事件仓库。
     */
    public SQLiteMemoryRetrievalEventRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public void save(MemoryRetrievalEventRecord record) {
        jdbcTemplate.update("""
                        INSERT INTO bq_memory_retrieval_events(
                            retrieval_id, thread_id, turn_id, snapshot_id, query_text, strategy,
                            candidate_count, selected_references_json, token_estimate, pollution_flags_json, created_at
                        ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                        """,
                record.retrievalId(),
                record.threadId(),
                record.turnId(),
                record.snapshotId(),
                record.queryText(),
                record.strategy(),
                record.candidateCount(),
                record.selectedReferencesJson(),
                record.tokenEstimate(),
                record.pollutionFlagsJson(),
                write(record.createdAt()));
    }

    private static String write(Instant instant) {
        return (instant == null ? Instant.now() : instant).toString();
    }
}
