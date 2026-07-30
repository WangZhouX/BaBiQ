package com.wzx.huitai.desktop.security

import com.wzx.huitai.security.secret.JceksSecretStore

private val LEGACY_OA_CREDENTIAL_ALIASES = setOf(
    "huitai.auth.tokens.v1",
    "huitai.auth.session-metadata.v1",
    "huitai.login.remembered.v1",
)

class LegacyOaCredentialAliasCleanup(
    private val secretStore: JceksSecretStore,
) {
    fun cleanup() {
        try {
            secretStore.deleteAliasesAtomically(LEGACY_OA_CREDENTIAL_ALIASES)
        } catch (_: Exception) {
            throw LegacyOaCredentialCleanupException()
        }
    }
}

class LegacyOaCredentialCleanupException : IllegalStateException("Legacy OA credential cleanup failed")
