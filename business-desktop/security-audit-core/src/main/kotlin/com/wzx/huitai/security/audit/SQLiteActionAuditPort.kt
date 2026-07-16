package com.wzx.huitai.security.audit

import com.wzx.huitai.action.model.ActionExecutionState
import com.wzx.huitai.action.port.ActionAuditDraft
import com.wzx.huitai.action.port.ActionAuditEvent
import com.wzx.huitai.action.port.ActionAuditPort
import com.wzx.huitai.security.database.BusinessDesktopDatabase
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.sql.Connection
import java.sql.ResultSet
import java.util.Collections
import java.util.UUID

/**
 * 将独立业务审计追加到 SQLite 的只追加端口。
 *
 * 执行存储已经在自己的状态事务中写事件，本端口只服务于独立审计追加，调用方不得用它重复写同一事实。
 * 全局读取按 `occurred_at, execution_id, event_sequence` 排序，因为 V1 schema 没有跨 execution 全局序号。
 */
class SQLiteActionAuditPort(
    private val database: BusinessDesktopDatabase,
    private val redactor: AuditRedactor = AuditRedactor(),
) : ActionAuditPort {
    private val persistenceRedactor = AuditRedactor(
        sensitiveFieldIds = setOf("claimToken", "claimOwner", "ownerId", "actorId", "reasoning"),
    )

    /** 追加调用方分配序号的事件，并在同一事务中校验序号连续。 */
    override suspend fun append(event: ActionAuditEvent) {
        validate(event.executionId, event.type)
        val safe = event.safeCopy()
        database.write { connection ->
            val expected = nextSequence(connection, safe.executionId)
            require(safe.sequence == expected) { "审计序号必须连续递增" }
            insert(connection, safe)
        }
    }

    /** 为草稿在写事务内分配下一个 execution 局部序号并追加。 */
    suspend fun append(draft: ActionAuditDraft): ActionAuditEvent {
        validate(draft.executionId, draft.type)
        return database.write { connection ->
            val event = ActionAuditEvent(
                executionId = draft.executionId,
                sequence = nextSequence(connection, draft.executionId),
                fromState = draft.fromState,
                toState = draft.toState,
                type = draft.type,
                redactedPayload = safePayload(draft.redactedPayload),
                actorId = safeActor(draft.executionId, draft.actorId),
                occurredAt = draft.occurredAt,
            )
            insert(connection, event)
            event.snapshot()
        }
    }

    /** 返回指定 execution 按局部序号排列的不可修改快照。 */
    suspend fun events(executionId: String): List<ActionAuditEvent> = database.read { connection ->
        connection.prepareStatement(SELECT_BY_EXECUTION).use { statement ->
            statement.setString(1, executionId)
            statement.executeQuery().use { rows -> immutableEvents(rows) }
        }
    }

    /** 按文档化的确定性全局顺序返回不可修改快照。 */
    suspend fun events(): List<ActionAuditEvent> = database.read { connection ->
        connection.prepareStatement(SELECT_ALL).use { statement ->
            statement.executeQuery().use { rows ->
                Collections.unmodifiableList(
                    immutableEvents(rows).sortedWith(
                        compareBy<ActionAuditEvent> { it.occurredAt }
                            .thenBy { it.executionId }
                            .thenBy { it.sequence },
                    ),
                )
            }
        }
    }

    /** 进入 SQL 前完成二次脱敏，阻止执行存储专有 claim/reasoning 字段泄露。 */
    private fun ActionAuditEvent.safeCopy(): ActionAuditEvent = copy(
        redactedPayload = safePayload(redactedPayload),
        actorId = safeActor(executionId, actorId),
    )

    private fun safePayload(payload: JsonObject): JsonObject =
        redactSecrets(persistenceRedactor.redact(redactor.redact(payload))) as JsonObject

    private fun redactSecrets(element: JsonElement): JsonElement = when (element) {
        JsonNull -> JsonNull
        is JsonObject -> JsonObject(element.mapValues { (_, value) -> redactSecrets(value) })
        is JsonArray -> JsonArray(element.map(::redactSecrets))
        is JsonPrimitive -> if (element.isString && containsSecret(element.content)) {
            JsonPrimitive(AuditRedactor.REDACTED)
        } else {
            element
        }
    }

    /** 普通短标识保留可用归属，其他 actor 保存 execution-bound 假名。 */
    private fun safeActor(executionId: String, actorId: String?): String? {
        if (actorId == null) return null
        return if (actorId.length <= MAX_ACTOR_LENGTH && SAFE_ACTOR.matches(actorId) && !containsSecret(actorId)) {
            actorId
        } else {
            "actor-sha256:${digest(executionId, actorId).take(PSEUDONYM_LENGTH)}"
        }
    }

    private fun containsSecret(value: String): Boolean {
        val compact = value.lowercase().replace(Regex("[^a-z0-9]+"), "")
        return SECRET_MARKERS.any(compact::contains) || JWT_PATTERN.containsMatchIn(value)
    }

    private fun digest(executionId: String, value: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest((executionId + "\u0000" + value).toByteArray(StandardCharsets.UTF_8))
            .joinToString("") { "%02x".format(it) }

    /** 事务内读取下一个局部序号；BusinessDesktopDatabase 使用 BEGIN IMMEDIATE 串行写者。 */
    private fun nextSequence(connection: Connection, executionId: String): Long =
        connection.prepareStatement(NEXT_SEQUENCE).use { statement ->
            statement.setString(1, executionId)
            statement.executeQuery().use { rows -> check(rows.next()); rows.getLong(1) }
        }

    private fun insert(connection: Connection, event: ActionAuditEvent) {
        connection.prepareStatement(INSERT_EVENT).use { statement ->
            val values = listOf(
                UUID.randomUUID().toString(), event.executionId, event.sequence, event.fromState?.name,
                event.toState.name, event.type, JSON.encodeToString(JsonElement.serializer(), event.redactedPayload),
                event.actorId, event.occurredAt.toString(),
            )
            values.forEachIndexed { index, value -> statement.setObject(index + 1, value) }
            check(statement.executeUpdate() == 1) { "审计事件追加失败" }
        }
    }

    private fun immutableEvents(rows: ResultSet): List<ActionAuditEvent> = Collections.unmodifiableList(buildList {
        while (rows.next()) {
            add(
                ActionAuditEvent(
                    executionId = rows.getString("execution_id"),
                    sequence = rows.getLong("event_sequence"),
                    fromState = rows.getString("from_status")?.let(ActionExecutionState::valueOf),
                    toState = ActionExecutionState.valueOf(rows.getString("to_status")),
                    type = rows.getString("event_type"),
                    redactedPayload = JSON.parseToJsonElement(rows.getString("payload_json_redacted")).jsonObject,
                    actorId = rows.getString("actor_id"),
                    occurredAt = java.time.Instant.parse(rows.getString("occurred_at")),
                ),
            )
        }
    })

    private fun ActionAuditEvent.snapshot() = copy(
        redactedPayload = JSON.parseToJsonElement(JSON.encodeToString(JsonElement.serializer(), redactedPayload)).jsonObject,
    )

    private fun validate(executionId: String, type: String) {
        require(executionId.isNotBlank()) { "审计 executionId 不能为空" }
        require(type.isNotBlank()) { "审计事件类型不能为空" }
    }

    private companion object {
        val JSON = Json { ignoreUnknownKeys = true }
        const val MAX_ACTOR_LENGTH = 128
        const val PSEUDONYM_LENGTH = 32
        val SAFE_ACTOR = Regex("[A-Za-z0-9][A-Za-z0-9._:@/-]{0,127}")
        val JWT_PATTERN = Regex("(?i)[A-Za-z0-9_-]{8,}\\.[A-Za-z0-9_-]{8,}\\.[A-Za-z0-9_-]{8,}")
        val SECRET_MARKERS = setOf("bearer", "authorization", "accesstoken", "refreshtoken", "claimtoken", "password", "clientsecret", "apikey", "secretkey", "privatekey")
        const val NEXT_SEQUENCE = "SELECT COALESCE(MAX(event_sequence),0)+1 FROM bd_action_events WHERE execution_id=?"
        const val INSERT_EVENT = """
            INSERT INTO bd_action_events (event_id,execution_id,event_sequence,from_status,to_status,event_type,payload_json_redacted,actor_id,occurred_at)
            VALUES (?,?,?,?,?,?,?,?,?)
        """
        const val SELECT_BY_EXECUTION = """
            SELECT execution_id,event_sequence,from_status,to_status,event_type,payload_json_redacted,actor_id,occurred_at
            FROM bd_action_events WHERE execution_id=? ORDER BY event_sequence
        """
        const val SELECT_ALL = """
            SELECT execution_id,event_sequence,from_status,to_status,event_type,payload_json_redacted,actor_id,occurred_at
            FROM bd_action_events
        """
    }
}
