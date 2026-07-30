package com.wzx.huitai.security.secret

/** Persistable reference whose normal representation reveals no alias or secret. */
class SecretRef private constructor(val value: String) {
    internal val alias: String get() = value
    override fun equals(other: Any?): Boolean = other is SecretRef && value == other.value
    override fun hashCode(): Int = value.hashCode()
    override fun toString(): String = "SecretRef([REDACTED])"

    companion object {
        private val ALIAS = Regex("[A-Za-z0-9][A-Za-z0-9_.-]{0,127}")
        fun parse(value: String): SecretRef {
            require(ALIAS.matches(value)) { "invalid secret reference" }
            return SecretRef(value)
        }
        internal fun isValidAlias(value: String) = ALIAS.matches(value)
    }
}

interface SecretStore : AutoCloseable {
    fun save(alias: String, secret: CharArray): SecretRef
    fun upsert(alias: String, secret: CharArray): SecretRef
    fun load(ref: SecretRef): CharArray?
    fun replace(ref: SecretRef, secret: CharArray)
    fun delete(ref: SecretRef): Boolean
}

class SecretStoreException(
    @Suppress("UNUSED_PARAMETER") message: String = PUBLIC_MESSAGE,
    @Suppress("UNUSED_PARAMETER") cause: Throwable? = null,
) : IllegalStateException(PUBLIC_MESSAGE) {
    companion object {
        const val PUBLIC_MESSAGE = "Local secret store operation failed"
    }
}
