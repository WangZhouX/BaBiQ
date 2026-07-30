package com.wzx.huitai.desktop.runtime

import com.wzx.huitai.security.secret.JceksSecretStore
import com.wzx.huitai.security.secret.SecretRef
import java.security.SecureRandom
import java.nio.file.Files
import java.nio.file.Path
import java.util.Arrays
import java.util.Base64

/**
 * Keeps the backend JCEKS master password in the desktop JCEKS so embedded and standalone
 * development launches always open the same provider-secret store.
 */
object BusinessBackendKeyStorePasswordVault {
    const val ALIAS = "huitai.backend.keystore.password.v1"

    fun loadOrCreate(secretStore: JceksSecretStore): CharArray {
        val ref = SecretRef.parse(ALIAS)
        secretStore.load(ref)?.let { return it }
        directDevelopmentPassword()?.let { generated ->
            try {
                secretStore.upsert(ref.value, generated)
                return generated.copyOf()
            } finally {
                Arrays.fill(generated, '\u0000')
            }
        }
        val bytes = ByteArray(32).also(SecureRandom()::nextBytes)
        val generated = try {
            Base64.getUrlEncoder().withoutPadding().encodeToString(bytes).toCharArray()
        } finally {
            Arrays.fill(bytes, 0)
        }
        try {
            secretStore.upsert(ref.value, generated)
            return generated.copyOf()
        } finally {
            Arrays.fill(generated, '\u0000')
        }
    }

    private fun directDevelopmentPassword(): CharArray? {
        if (System.getenv("HUITAI_BUSINESS_DIRECT_DEVELOPMENT") != "1") return null
        val home = System.getenv("HUITAI_DESKTOP_HOME")
            ?.takeIf(String::isNotBlank)
            ?.let(Path::of)
            ?: Path.of(System.getProperty("user.home"))
        val path = home.toAbsolutePath().normalize()
            .resolve(".huitai-agent-desktop/agent/backend-keystore-password")
        return runCatching {
            Files.readString(path, Charsets.US_ASCII)
                .trim()
                .takeIf(String::isNotBlank)
                ?.toCharArray()
        }.getOrNull()
    }
}
