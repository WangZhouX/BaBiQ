package com.wzx.huitai.presentation.context

import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.nio.charset.StandardCharsets
import java.time.Instant

/**
 * 页面上下文传输使用的可信身份。
 *
 * 该值必须由认证和桌面会话层显式提供，不能从页面字段或草稿载荷构造。此本地 DTO
 * 不构成授权凭证；接收端仍必须用认证连接身份校验实例、会话和身份代次。
 */
@Serializable
data class TrustedPageContextIdentity(
    val desktopInstanceId: String,
    val authSessionId: String,
    val identityEpoch: Long,
) {
    init {
        require(desktopInstanceId.isNotBlank()) { "桌面实例标识不能为空" }
        require(authSessionId.isNotBlank()) { "认证会话标识不能为空" }
        require(identityEpoch > 0) { "身份代次必须为正整数" }
    }
}

/**
 * 可发送给 Agent 的页面上下文 envelope。
 *
 * envelope 身份字段由调用方的可信本地身份经发布器注入，只有 [payload] 来自页面状态。
 * 私有构造器仅减少本地误构造，并不能让可序列化 DTO 成为授权能力；接收端必须把反序列化字段视为
 * 不可信数据，并与认证连接上绑定的 desktop/auth/identity epoch 逐项校验。payload 内所有显示字符串
 * 同样是不可信数据。
 */
@Serializable
@ConsistentCopyVisibility
data class PublishedPageContext private constructor(
    val protocolVersion: String,
    val desktopInstanceId: String,
    val authSessionId: String,
    val identityEpoch: Long,
    val catalogEpoch: Long,
    val contextSequence: Long,
    val generatedAt: String,
    val payloadSize: Int,
    val payload: PageContextSnapshot,
) {
    internal companion object {
        /** 只允许发布器创建带可信 envelope 的页面上下文。 */
        fun create(
            identity: TrustedPageContextIdentity,
            catalogEpoch: Long,
            contextSequence: Long,
            generatedAt: String,
            payloadSize: Int,
            payload: PageContextSnapshot,
        ): PublishedPageContext = PublishedPageContext(
            protocolVersion = PageContextPublisher.PROTOCOL_VERSION,
            desktopInstanceId = identity.desktopInstanceId,
            authSessionId = identity.authSessionId,
            identityEpoch = identity.identityEpoch,
            catalogEpoch = catalogEpoch,
            contextSequence = contextSequence,
            generatedAt = generatedAt,
            payloadSize = payloadSize,
            payload = payload,
        )
    }
}

/** catalog 发生回退，或 context 序号未严格递增。 */
class NonMonotonicPageContextSequenceException(message: String) : IllegalArgumentException(message)

/** 清洗后页面载荷超过发布上限。 */
class PageContextPayloadTooLargeException(
    val actualBytes: Int,
    val maximumBytes: Int,
) : IllegalArgumentException("页面上下文载荷 ${actualBytes}B 超过上限 ${maximumBytes}B")

/**
 * 为页面事实添加可信身份、单调序号和实际 UTF-8 载荷大小。
 *
 * @param identity 由认证层提供的可信身份。
 * @param sanitizer 发布前数据清洗器。
 * @param maxPayloadBytes 清洗后 JSON 载荷允许的最大 UTF-8 字节数。
 * @param generatedAt 生成协议时间戳的函数；默认使用 UTC ISO-8601 时间。
 */
class PageContextPublisher(
    private val identity: TrustedPageContextIdentity,
    private val sanitizer: PageContextSanitizer = PageContextSanitizer(),
    private val maxPayloadBytes: Int = DEFAULT_MAX_PAYLOAD_BYTES,
    private val generatedAt: () -> String = { Instant.now().toString() },
) {
    private var latestCatalogEpoch: Long = 0
    private var latestContextSequence: Long = 0

    init {
        require(maxPayloadBytes > 0) { "页面上下文载荷上限必须为正整数" }
    }

    /**
     * 清洗并发布页面快照。
     *
     * 同一发布器上的 catalog 代次允许保持不变但不得回退，context 序号必须严格递增。
     * canonical payload、大小和完整 envelope 均在序号提交前生成，因此失败不会消耗调用方序号。
     */
    fun publish(
        snapshot: PageContextSnapshot,
        catalogEpoch: Long,
        contextSequence: Long,
    ): PublishedPageContext {
        require(catalogEpoch > 0) { "动作目录代次必须为正整数" }
        require(contextSequence > 0) { "页面上下文序号必须为正整数" }

        val sanitizedJson = Json.encodeToString(sanitizer.sanitize(snapshot))
        val payloadSize = sanitizedJson.toByteArray(StandardCharsets.UTF_8).size
        if (payloadSize > maxPayloadBytes) {
            throw PageContextPayloadTooLargeException(payloadSize, maxPayloadBytes)
        }
        val canonicalPayload = Json.decodeFromString<PageContextSnapshot>(sanitizedJson)

        return synchronized(this) {
            validateSequenceProgression(catalogEpoch, contextSequence)
            val candidate = PublishedPageContext.create(
                identity = identity,
                catalogEpoch = catalogEpoch,
                contextSequence = contextSequence,
                generatedAt = generatedAt(),
                payloadSize = payloadSize,
                payload = canonicalPayload,
            )
            latestCatalogEpoch = catalogEpoch
            latestContextSequence = contextSequence
            candidate
        }
    }

    private fun validateSequenceProgression(catalogEpoch: Long, contextSequence: Long) {
        if (catalogEpoch < latestCatalogEpoch) {
            throw NonMonotonicPageContextSequenceException(
                "动作目录代次不得回退：last=$latestCatalogEpoch, actual=$catalogEpoch",
            )
        }
        if (contextSequence <= latestContextSequence) {
            throw NonMonotonicPageContextSequenceException(
                "页面上下文序号必须严格递增：last=$latestContextSequence, actual=$contextSequence",
            )
        }
    }

    companion object {
        const val PROTOCOL_VERSION: String = "1.0"
        const val DEFAULT_MAX_PAYLOAD_BYTES: Int = 128 * 1024
    }
}
