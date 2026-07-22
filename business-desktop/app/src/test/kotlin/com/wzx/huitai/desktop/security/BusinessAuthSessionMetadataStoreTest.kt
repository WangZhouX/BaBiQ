package com.wzx.huitai.desktop.security

import com.wzx.huitai.integration.auth.AuthTokenSet
import com.wzx.huitai.security.secret.JceksSecretStore
import com.wzx.huitai.security.secret.SecretRef
import java.nio.ByteBuffer
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import kotlin.io.path.readBytes
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class BusinessAuthSessionMetadataStoreTest {
    @Test
    fun `metadata save or replace reloads only identity fields and clear is idempotent`() {
        val root = Files.createTempDirectory("business-auth-session-metadata")
        val password = "password".toCharArray()
        try {
            JceksSecretStore(root.resolve("credentials.jceks"), password).use { secrets ->
                val store = BusinessAuthSessionMetadataStore(secrets)
                val first = BusinessAuthSessionMetadata("user-1", "tenant-1", "100")
                val replacement = BusinessAuthSessionMetadata("user-2", "tenant-2", "200")

                assertNull(store.load())
                store.saveOrReplace(first)
                assertEquals(first, store.load())
                store.saveOrReplace(replacement)
                assertEquals(replacement, store.load())
                store.clear()
                store.clear()
                assertNull(store.load())
                assertEquals(
                    "BusinessAuthSessionMetadata(userId=[REDACTED], tenantId=[REDACTED], platformId=[REDACTED])",
                    replacement.toString(),
                )
            }
        } finally {
            password.fill('\u0000')
        }
    }

    @Test
    fun `all malformed metadata forms report redacted failure and preserve other aliases`() = runBlocking {
        val password = "password".toCharArray()
        try {
            JceksSecretStore(Files.createTempDirectory("business-auth-session-corrupt").resolve("credentials.jceks"), password).use { secrets ->
                val metadata = BusinessAuthSessionMetadataStore(secrets)
                val tokens = JceksAuthCredentialPersistence(secrets)
                tokens.replace(AuthTokenSet("access-token", "refresh-token"))
                secrets.upsert(PROVIDER_ALIAS, "provider-secret".toCharArray())

                malformedEntries(METADATA_MAGIC, 3).forEach { (caseName, corrupt) ->
                    secrets.upsert(BusinessAuthSessionMetadataStore.DEFAULT_ALIAS, hex(corrupt))
                    val failure = assertFailsWith<SessionMetadataPersistenceException>(caseName) { metadata.load() }
                    assertEquals("Stored authentication session metadata is invalid", failure.message, caseName)
                    assertFalse("access-token" in failure.toString(), caseName)
                    assertEquals("access-token", tokens.load()?.accessToken, caseName)
                    assertSecretEquals(secrets, PROVIDER_ALIAS, "provider-secret", caseName)
                    val retainedMetadata = requireNotNull(
                        secrets.load(SecretRef.parse(BusinessAuthSessionMetadataStore.DEFAULT_ALIAS)),
                    ) { "$caseName must not delete metadata" }
                    retainedMetadata.fill('\u0000')
                }
            }
        } finally {
            password.fill('\u0000')
        }
    }

    @Test
    fun `codec wipes prior encoded fields when a later field has an illegal surrogate`() {
        val wiped = mutableListOf<Any>()
        val codec = VersionedJceksCodec { wiped += it }
        val first = "prefix-secret".toCharArray()
        val malformed = charArrayOf('\uD800')

        assertFailsWith<IllegalArgumentException> { codec.encode(METADATA_MAGIC, listOf(first, malformed)) }

        assertTrue(first.all { it == '\u0000' })
        assertTrue(malformed.all { it == '\u0000' })
        assertTrue(
            wiped.filterIsInstance<ByteArray>().any { it.size == "prefix-secret".length && it.all { byte -> byte == 0.toByte() } },
            "the already encoded field must be observed only after it is wiped",
        )
    }

    @Test
    fun `codec wipes current encoding staging buffer after sensitive prefix and illegal surrogate`() {
        val wiped = mutableListOf<Any>()
        val codec = VersionedJceksCodec { wiped += it }
        val sensitivePrefix = "current-sensitive-prefix"
        val malformed = (sensitivePrefix + '\uD800').toCharArray()

        assertFailsWith<IllegalArgumentException> { codec.encode(METADATA_MAGIC, listOf(malformed)) }

        assertTrue(
            wiped.filterIsInstance<ByteArray>().any {
                it.size >= sensitivePrefix.toByteArray().size && it.all { byte -> byte == 0.toByte() }
            },
            "the current field encoding staging buffer must be observed only after it is wiped",
        )
    }

    @Test
    fun `codec wipes current decoding staging buffer after sensitive prefix and illegal utf8`() {
        val wiped = mutableListOf<Any>()
        val codec = VersionedJceksCodec { wiped += it }
        val sensitivePrefix = "current-sensitive-prefix"
        val malformed = sensitivePrefix.toByteArray() + byteArrayOf(0x80.toByte())

        assertFailsWith<VersionedJceksCodec.InvalidEntryException> { codec.decodeUtf8Chars(malformed) }

        assertTrue(
            wiped.filterIsInstance<CharArray>().any {
                it.size >= sensitivePrefix.length && it.all { char -> char == '\u0000' }
            },
            "the current field decoding staging buffer must be observed only after it is wiped",
        )
    }

    @Test
    fun `codec wipes partially decoded payload when hex becomes invalid midway`() {
        val wiped = mutableListOf<Any>()
        val codec = VersionedJceksCodec { wiped += it }

        assertFailsWith<VersionedJceksCodec.InvalidEntryException> {
            codec.decode("001122z0".toCharArray(), METADATA_MAGIC, 3)
        }

        assertTrue(
            wiped.filterIsInstance<ByteArray>().any { it.size == 4 && it.all { byte -> byte == 0.toByte() } },
            "the partially populated binary payload must be observed only after it is wiped",
        )
    }

    @Test
    fun `wrong keystore password leaves bytes unchanged and all aliases recoverable`() = runBlocking {
        val root = Files.createTempDirectory("business-auth-session-wrong-password")
        val path = root.resolve("credentials.jceks")
        val correctPassword = "correct-password".toCharArray()
        val wrongPassword = "wrong-password".toCharArray()
        try {
            JceksSecretStore(path, correctPassword).use { secrets ->
                JceksAuthCredentialPersistence(secrets).replace(AuthTokenSet("access-token", "refresh-token"))
                BusinessAuthSessionMetadataStore(secrets).saveOrReplace(
                    BusinessAuthSessionMetadata("user-1", "tenant-1", "100"),
                )
                BusinessLoginCredentialStore(secrets).saveOrReplace("account-1", "login-password".toCharArray())
                secrets.upsert(PROVIDER_ALIAS, "provider-secret".toCharArray())
            }
            val snapshot = path.readBytes()

            JceksSecretStore(path, wrongPassword).use { secrets ->
                assertFailsWith<LocalCredentialStoreUnavailableException> {
                    BusinessAuthSessionMetadataStore(secrets).load()
                }
                assertFailsWith<LocalCredentialStoreUnavailableException> {
                    BusinessLoginCredentialStore(secrets).load()
                }
            }
            assertContentEquals(snapshot, path.readBytes())

            JceksSecretStore(path, correctPassword).use { secrets ->
                assertEquals("access-token", JceksAuthCredentialPersistence(secrets).load()?.accessToken)
                assertEquals("user-1", BusinessAuthSessionMetadataStore(secrets).load()?.userId)
                val remembered = requireNotNull(BusinessLoginCredentialStore(secrets).load())
                try {
                    assertEquals("account-1", remembered.account)
                    assertEquals("login-password", remembered.password.concatToString())
                } finally {
                    remembered.password.fill('\u0000')
                }
                assertSecretEquals(secrets, PROVIDER_ALIAS, "provider-secret", "wrong-password recovery")
            }
        } finally {
            correctPassword.fill('\u0000')
            wrongPassword.fill('\u0000')
        }
    }

    @Test
    fun `unavailable local keystore is left byte for byte unchanged`() {
        val root = Files.createTempDirectory("business-auth-session-unavailable")
        val path = root.resolve("credentials.jceks")
        val snapshot = byteArrayOf(1, 2, 3, 4, 5, 6)
        Files.write(path, snapshot)
        val password = "wrong-password".toCharArray()
        try {
            JceksSecretStore(path, password).use { secrets ->
                val failure = assertFailsWith<LocalCredentialStoreUnavailableException> {
                    BusinessAuthSessionMetadataStore(secrets).load()
                }
                assertEquals("Local key store is unavailable", failure.message)
                assertFalse("wrong-password" in failure.toString())
                assertContentEquals(snapshot, path.readBytes())
            }
        } finally {
            password.fill('\u0000')
        }
    }

    private fun hex(bytes: ByteArray): CharArray {
        val digits = "0123456789abcdef"
        return CharArray(bytes.size * 2) { index ->
            val value = bytes[index / 2].toInt() and 0xff
            digits[if (index % 2 == 0) value ushr 4 else value and 0xf]
        }
    }

    companion object {
        internal const val METADATA_MAGIC = 0x48534d44
        internal const val REMEMBERED_MAGIC = 0x484c4f47
        internal const val PROVIDER_ALIAS = "provider.sentinel"

        internal fun malformedEntries(magic: Int, fieldCount: Int): List<Pair<String, ByteArray>> {
            val fields = List(fieldCount) { index -> "field-$index".toByteArray() }
            val valid = payload(magic, 1, fieldCount, fields)
            return listOf(
                "wrong magic" to valid.copyOf().also { ByteBuffer.wrap(it).putInt(magic xor 0x00ff00ff) },
                "wrong version" to valid.copyOf().also { ByteBuffer.wrap(it).putInt(Int.SIZE_BYTES, 2) },
                "wrong field count" to valid.copyOf().also { ByteBuffer.wrap(it).putInt(Int.SIZE_BYTES * 2, fieldCount + 1) },
                "negative length" to lengthOnlyPayload(magic, fieldCount, -1),
                "oversized length" to lengthOnlyPayload(magic, fieldCount, 64 * 1024 + 1),
                "invalid utf8" to payload(magic, 1, fieldCount, listOf(byteArrayOf(0x80.toByte())) + fields.drop(1)),
                "trailing bytes" to (valid + byteArrayOf(0x01)),
            )
        }

        private fun payload(
            magic: Int,
            version: Int,
            declaredFields: Int,
            fields: List<ByteArray>,
        ): ByteArray = ByteBuffer.allocate(Int.SIZE_BYTES * (3 + fields.size) + fields.sumOf { it.size })
            .putInt(magic)
            .putInt(version)
            .putInt(declaredFields)
            .also { buffer -> fields.forEach { buffer.putInt(it.size).put(it) } }
            .array()

        private fun lengthOnlyPayload(magic: Int, fieldCount: Int, length: Int): ByteArray =
            ByteBuffer.allocate(Int.SIZE_BYTES * 4)
                .putInt(magic)
                .putInt(1)
                .putInt(fieldCount)
                .putInt(length)
                .array()

        internal fun assertSecretEquals(
            secrets: JceksSecretStore,
            alias: String,
            expected: String,
            caseName: String,
        ) {
            val loaded = requireNotNull(secrets.load(SecretRef.parse(alias))) { caseName }
            try {
                assertEquals(expected, loaded.concatToString(), caseName)
            } finally {
                loaded.fill('\u0000')
            }
        }
    }
}
