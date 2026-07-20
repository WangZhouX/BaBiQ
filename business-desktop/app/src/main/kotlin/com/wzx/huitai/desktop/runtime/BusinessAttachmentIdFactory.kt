package com.wzx.huitai.desktop.runtime

import java.util.UUID

data class BusinessAttachmentIdentity(
    val id: String,
    val displayId: String,
)

/**
 * 为本地附件生成持久 UUID 和适合在对话里引用的短 ID。
 *
 * 调用方必须把当前草稿及当前 thread 已有的两类 ID 一并传入；本工厂不会读取全局状态。
 */
class BusinessAttachmentIdFactory(
    private val uuidSource: () -> UUID = UUID::randomUUID,
    private val displayIdEncoder: (UUID) -> String = ::encodeDisplayId,
    private val maximumAttempts: Int = DEFAULT_MAXIMUM_ATTEMPTS,
) {
    init {
        require(maximumAttempts > 0) { "maximumAttempts must be positive" }
    }

    fun create(
        existingIds: Set<String> = emptySet(),
        existingDisplayIds: Set<String> = emptySet(),
    ): BusinessAttachmentIdentity {
        val normalizedIds = existingIds.mapTo(hashSetOf()) { it.lowercase() }
        val normalizedDisplayIds = existingDisplayIds.mapTo(hashSetOf()) { it.uppercase() }
        repeat(maximumAttempts) {
            val uuid = uuidSource()
            val id = uuid.toString()
            val displayId = displayIdEncoder(uuid).uppercase()
            require(DISPLAY_ID_PATTERN.matches(displayId)) {
                "displayId encoder returned an invalid attachment identifier"
            }
            if (id.lowercase() !in normalizedIds && displayId !in normalizedDisplayIds) {
                return BusinessAttachmentIdentity(id, displayId)
            }
        }
        throw IllegalStateException("Unable to allocate a unique attachment identifier")
    }

    private companion object {
        const val DEFAULT_MAXIMUM_ATTEMPTS = 128
        const val SAFE_BASE32 = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"
        val DISPLAY_ID_PATTERN = Regex("^A-[A-HJ-NP-Z2-9]{6}$")

        fun encodeDisplayId(uuid: UUID): String {
            var bits = uuid.mostSignificantBits xor uuid.leastSignificantBits
            return buildString(8) {
                append("A-")
                repeat(6) {
                    append(SAFE_BASE32[(bits and 31L).toInt()])
                    bits = bits ushr 5
                }
            }
        }
    }
}
