package com.wzx.huitai.desktop.runtime

import java.nio.channels.FileChannel
import java.nio.charset.StandardCharsets
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.util.UUID

/**
 * 原子创建并稳定读取单次安装的桌面实例 ID。
 *
 * 临时文件使用 create-new，持久文件使用不覆盖的原子移动；并发首次启动只有一个值获胜，其他
 * 调用者读取胜出的 UUID，避免同一安装在不同子进程中分裂为多个身份。
 */
class DesktopInstallationIdentityStore(
    path: Path,
) {
    private val path = path.toAbsolutePath().normalize()

    init {
        require(path.fileName != null) { "installation identity path must identify a file" }
    }

    /** 返回已有合法 UUID，或原子持久化一个新 UUID。 */
    fun loadOrCreate(): String {
        readExisting()?.let { return it }
        Files.createDirectories(path.parent)
        val candidate = UUID.randomUUID().toString()
        val temporary = path.resolveSibling("${path.fileName}.${UUID.randomUUID()}.tmp")
        try {
            FileChannel.open(temporary, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE).use { channel ->
                val bytes = StandardCharsets.US_ASCII.encode(candidate)
                while (bytes.hasRemaining()) channel.write(bytes)
                channel.force(true)
            }
            RuntimeFilePermissions.applyOwnerOnly(temporary, directory = false)
            try {
                Files.move(temporary, path, StandardCopyOption.ATOMIC_MOVE)
            } catch (_: FileAlreadyExistsException) {
                // 并发启动已安装胜出值；finally 删除本调用临时文件。
            }
            RuntimeFilePermissions.applyOwnerOnly(path, directory = false)
            return readExisting() ?: error("installation identity could not be persisted")
        } finally {
            Files.deleteIfExists(temporary)
        }
    }

    override fun toString(): String = "DesktopInstallationIdentityStore(path=[REDACTED])"

    /** 读取时严格验证 UUID 规范形式，损坏文件不能静默换身份。 */
    private fun readExisting(): String? {
        if (!Files.exists(path)) return null
        require(!Files.isSymbolicLink(path)) { "installation identity must not be a symbolic link" }
        val value = Files.readString(path, StandardCharsets.US_ASCII)
        val parsed = runCatching { UUID.fromString(value) }
            .getOrElse { throw IllegalStateException("installation identity is invalid") }
        check(parsed.toString() == value) { "installation identity is invalid" }
        return value
    }
}
