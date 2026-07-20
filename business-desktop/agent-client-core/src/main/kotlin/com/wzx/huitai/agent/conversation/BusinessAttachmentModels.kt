package com.wzx.huitai.agent.conversation

import java.nio.file.Path
import java.util.UUID

private val attachmentDisplayIdPattern = Regex("^A-[A-HJ-NP-Z2-9]{6}$")
private val sha256Pattern = Regex("^[A-Fa-f0-9]{64}$")
private val attachmentSources = setOf("SELECTED_FILE", "CLIPBOARD_IMAGE")

data class BusinessAttachmentDraft(
    val id: String,
    val displayId: String,
    val name: String,
    val localPath: String,
    val sizeBytes: Long,
    val displayType: String,
) {
    init {
        requireUuid(id)
        requireDisplayId(displayId)
        requireDisplayName(name)
        requireLocalPath(localPath)
        require(sizeBytes >= 0) { "sizeBytes must not be negative" }
        require(displayType.isNotBlank()) { "displayType must not be blank" }
    }

    override fun toString(): String =
        "BusinessAttachmentDraft(id=$id, displayId=$displayId, name=$name, " +
            "localPath=[REDACTED], sizeBytes=$sizeBytes, displayType=$displayType)"
}

data class BusinessMessageAttachment(
    val id: String,
    val displayId: String,
    val name: String,
    val mediaType: String,
    val sizeBytes: Long,
    val sha256: String,
    val source: String,
    val localPath: String,
) {
    init {
        requireUuid(id)
        requireDisplayId(displayId)
        requireDisplayName(name)
        require(mediaType.isNotBlank()) { "mediaType must not be blank" }
        require(sizeBytes >= 0) { "sizeBytes must not be negative" }
        require(sha256Pattern.matches(sha256)) { "sha256 must be a 64-character hexadecimal digest" }
        require(source in attachmentSources) { "source must identify a supported attachment source" }
        requireLocalPath(localPath)
    }

    override fun toString(): String =
        "BusinessMessageAttachment(id=$id, displayId=$displayId, name=$name, " +
            "mediaType=$mediaType, sizeBytes=$sizeBytes, sha256=$sha256, source=$source, " +
            "localPath=[REDACTED])"
}

private fun requireUuid(id: String) {
    require(runCatching { UUID.fromString(id) }.isSuccess) { "id must be a UUID" }
}

private fun requireDisplayId(displayId: String) {
    require(attachmentDisplayIdPattern.matches(displayId)) {
        "displayId must match A-XXXXXX using safe uppercase Base32 characters"
    }
}

private fun requireDisplayName(name: String) {
    require(name.isNotBlank()) { "name must not be blank" }
    require(name.codePointCount(0, name.length) <= 255) { "name must not exceed 255 Unicode characters" }
}

private fun requireLocalPath(localPath: String) {
    require(localPath.isNotBlank()) { "localPath must not be blank" }
    require(localPath.codePointCount(0, localPath.length) <= 4_096) {
        "localPath must not exceed 4096 Unicode characters"
    }
    require(runCatching { Path.of(localPath).isAbsolute }.getOrDefault(false)) {
        "localPath must be absolute"
    }
}
