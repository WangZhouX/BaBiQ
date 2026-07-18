package com.wzx.huitai.desktop.runtime

import com.wzx.huitai.agent.client.DesktopSessionIdentity
import java.nio.channels.FileChannel
import java.nio.charset.StandardCharsets
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.AclEntry
import java.nio.file.attribute.AclEntryFlag
import java.nio.file.attribute.AclEntryPermission
import java.nio.file.attribute.AclEntryType
import java.nio.file.attribute.AclFileAttributeView
import java.nio.file.attribute.PosixFileAttributeView
import java.nio.file.attribute.PosixFilePermission
import java.util.EnumSet
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 持有一次内置 Agent 子进程启动的内存身份和单次消费 token 文件。
 *
 * 后端使用 DELETE_ON_CLOSE 消费文件后，本对象仍保留同一 token 供该子进程生命周期内的
 * WebSocket 重连；关闭及启动失败路径都会幂等删除尚未消费的文件。
 */
class DesktopSessionTokenFile private constructor(
    val path: Path,
    val identity: DesktopSessionIdentity,
) : AutoCloseable {
    private val closed = AtomicBoolean(false)

    /** 删除未被后端消费的 token；文件已被消费时同样成功。 */
    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        Files.deleteIfExists(path)
    }

    override fun toString(): String =
        "DesktopSessionTokenFile(path=[REDACTED], desktopSessionId=[REDACTED], token=[REDACTED])"

    companion object {
        private const val LOCAL_ORIGIN = "http://127.0.0.1"

        /** 为一个全新子进程创建身份，并以 CREATE_NEW 写入 256-bit URL-safe token。 */
        fun create(
            path: Path,
            desktopInstanceId: String,
            localOrigin: String = LOCAL_ORIGIN,
        ): DesktopSessionTokenFile {
            val normalized = path.toAbsolutePath().normalize()
            require(normalized.fileName != null) { "session token path must identify a file" }
            Files.createDirectories(normalized.parent)
            rejectLinkedToken(normalized)
            val identity = DesktopSessionIdentity.forChildLaunch(desktopInstanceId, localOrigin)
            try {
                FileChannel.open(normalized, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE).use { channel ->
                    val bytes = StandardCharsets.US_ASCII.encode(identity.desktopSessionToken)
                    while (bytes.hasRemaining()) channel.write(bytes)
                    channel.force(true)
                }
                RuntimeFilePermissions.applyOwnerOnly(normalized, directory = false)
                return DesktopSessionTokenFile(normalized, identity)
            } catch (_: FileAlreadyExistsException) {
                throw IllegalStateException("business desktop session token already exists")
            } catch (failure: Throwable) {
                Files.deleteIfExists(normalized)
                throw failure
            }
        }

        /** 已存在 token 路径绝不跟随，防止 CREATE_NEW 的安全语义被链接目标绕过。 */
        private fun rejectLinkedToken(path: Path) {
            if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) return
            require(!Files.isSymbolicLink(path)) { "business desktop session token must not be a link" }
        }
    }
}

/** 运行文件权限的跨平台 best-effort 收紧；路径和 link 校验不依赖其成功。 */
object RuntimeFilePermissions {
    fun applyOwnerOnly(path: Path, directory: Boolean) {
        val posixApplied = runCatching {
            val view = Files.getFileAttributeView(path, PosixFileAttributeView::class.java, LinkOption.NOFOLLOW_LINKS)
                ?: return@runCatching false
            val permissions = if (directory) {
                setOf(
                    PosixFilePermission.OWNER_READ,
                    PosixFilePermission.OWNER_WRITE,
                    PosixFilePermission.OWNER_EXECUTE,
                )
            } else {
                setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE)
            }
            view.setPermissions(permissions)
            true
        }.getOrDefault(false)
        if (posixApplied) return

        runCatching {
            val view = Files.getFileAttributeView(path, AclFileAttributeView::class.java, LinkOption.NOFOLLOW_LINKS)
                ?: return@runCatching
            val owner = Files.getOwner(path, LinkOption.NOFOLLOW_LINKS)
            val entry = AclEntry.newBuilder()
                .setType(AclEntryType.ALLOW)
                .setPrincipal(owner)
                .setPermissions(EnumSet.allOf(AclEntryPermission::class.java))
            if (directory) entry.setFlags(AclEntryFlag.FILE_INHERIT, AclEntryFlag.DIRECTORY_INHERIT)
            view.acl = listOf(entry.build())
        }
    }
}
