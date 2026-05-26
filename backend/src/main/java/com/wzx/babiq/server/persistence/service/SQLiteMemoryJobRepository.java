package com.wzx.babiq.server.persistence.service;

import com.wzx.babiq.server.memory.repository.MemoryJobRecord;
import com.wzx.babiq.server.memory.repository.MemoryJobRepository;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * MemoryJobRepository 的 SQLite 实现。
 *
 * <p>任务表需要一些 lease、status、generation 查询，直接使用 JdbcTemplate 可以让 SQL
 * 显式可读，同时仍然被上层 repository 端口隔离。</p>
 */
@Repository
public class SQLiteMemoryJobRepository implements MemoryJobRepository {

    /** JDBC 访问器，使用项目统一 DataSource 和 Flyway migration。 */
    private final JdbcTemplate jdbcTemplate;

    public SQLiteMemoryJobRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public int nextPhase2Generation() {
        Integer generation = jdbcTemplate.queryForObject(
                "SELECT COALESCE(MAX(generation), 0) + 1 FROM bq_memory_jobs WHERE job_type = 'PHASE2'",
                Integer.class);
        return generation == null ? 1 : generation;
    }

    @Override
    public Optional<MemoryJobRecord> findLatestCompletedPhase2() {
        return jdbcTemplate.query("""
                        SELECT * FROM bq_memory_jobs
                        WHERE job_type = 'PHASE2' AND status = 'SUCCEEDED'
                        ORDER BY completed_at DESC, created_at DESC
                        LIMIT 1
                        """,
                (rs, rowNum) -> toRecord(rs)).stream().findFirst();
    }

    @Override
    public Optional<MemoryJobRecord> findActivePhase2() {
        return jdbcTemplate.query("""
                        SELECT * FROM bq_memory_jobs
                        WHERE job_type = 'PHASE2' AND status IN ('PENDING','RUNNING')
                        ORDER BY created_at ASC
                        LIMIT 1
                        """,
                (rs, rowNum) -> toRecord(rs)).stream().findFirst();
    }

    @Override
    @Transactional
    public void save(MemoryJobRecord record) {
        jdbcTemplate.update("""
                        INSERT INTO bq_memory_jobs(
                            job_id, job_type, job_key, generation, thread_id, turn_id, status, worker_id,
                            lease_until, retry_count, max_retries, input_watermark, error_message,
                            created_at, started_at, completed_at, updated_at
                        ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                        ON CONFLICT(job_key) DO UPDATE SET
                            job_id = excluded.job_id,
                            job_type = excluded.job_type,
                            generation = excluded.generation,
                            thread_id = excluded.thread_id,
                            turn_id = excluded.turn_id,
                            status = excluded.status,
                            worker_id = excluded.worker_id,
                            lease_until = excluded.lease_until,
                            retry_count = excluded.retry_count,
                            max_retries = excluded.max_retries,
                            input_watermark = excluded.input_watermark,
                            error_message = excluded.error_message,
                            started_at = excluded.started_at,
                            completed_at = excluded.completed_at,
                            updated_at = excluded.updated_at
                        """,
                record.jobId(), record.jobType(), record.jobKey(), record.generation(), record.threadId(),
                record.turnId(), record.status(), record.workerId(), write(record.leaseUntil()), record.retryCount(),
                record.maxRetries(), record.inputWatermark(), record.errorMessage(), write(record.createdAt()),
                write(record.startedAt()), write(record.completedAt()), write(record.updatedAt()));
    }

    @Override
    public List<MemoryJobRecord> listLatest(int limit) {
        return jdbcTemplate.query("SELECT * FROM bq_memory_jobs ORDER BY created_at DESC LIMIT ?",
                (rs, rowNum) -> toRecord(rs), limit);
    }

    @Override
    public long countByStatus(String status) {
        Long count = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM bq_memory_jobs WHERE status = ?",
                Long.class, status);
        return count == null ? 0 : count;
    }

    @Override
    public Optional<MemoryJobRecord> findPendingPhase2() {
        return jdbcTemplate.query("""
                        SELECT * FROM bq_memory_jobs
                        WHERE job_type = 'PHASE2' AND status = 'PENDING'
                        ORDER BY generation ASC
                        LIMIT 1
                        """,
                (rs, rowNum) -> toRecord(rs)).stream().findFirst();
    }

    @Override
    public Optional<MemoryJobRecord> findPendingPhase1() {
        return jdbcTemplate.query("""
                        SELECT * FROM bq_memory_jobs
                        WHERE job_type = 'PHASE1' AND status = 'PENDING'
                        ORDER BY created_at ASC
                        LIMIT 1
                        """,
                (rs, rowNum) -> toRecord(rs)).stream().findFirst();
    }

    private static MemoryJobRecord toRecord(ResultSet rs) throws SQLException {
        return new MemoryJobRecord(
                rs.getString("job_id"),
                rs.getString("job_type"),
                rs.getString("job_key"),
                rs.getInt("generation"),
                rs.getString("thread_id"),
                rs.getString("turn_id"),
                rs.getString("status"),
                rs.getString("worker_id"),
                read(rs.getString("lease_until")),
                rs.getInt("retry_count"),
                rs.getInt("max_retries"),
                rs.getString("input_watermark"),
                rs.getString("error_message"),
                read(rs.getString("created_at")),
                read(rs.getString("started_at")),
                read(rs.getString("completed_at")),
                read(rs.getString("updated_at")));
    }

    private static String write(Instant instant) {
        return instant == null ? null : instant.toString();
    }

    private static Instant read(String value) {
        return value == null || value.isBlank() ? null : Instant.parse(value);
    }
}
