package com.wzx.huitai.desktop.auth.config

import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.BasicFileAttributes

/** 将完整的 bundled 配置仅在目标缺失时安装；不负责 properties 解析。 */
internal fun interface BusinessLegalLinksBootstrapInstaller {
    fun installIfAbsent(target: Path, bundledDefault: () -> InputStream?)
}

/** 可注入的最小文件操作边界，用于精确覆盖 fsync 与原子安装失败。 */
internal interface BusinessLegalLinksBootstrapFileOperations {
    fun writeAndForce(temporary: Path, source: InputStream)

    fun createLink(target: Path, temporary: Path)

    fun forceDirectory(directory: Path)
}

internal object NioBusinessLegalLinksBootstrapFileOperations :
    BusinessLegalLinksBootstrapFileOperations {
    override fun writeAndForce(temporary: Path, source: InputStream) {
        FileChannel.open(temporary, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING).use { channel ->
            val bytes = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val count = source.read(bytes)
                if (count < 0) break
                if (count == 0) continue
                val buffer = ByteBuffer.wrap(bytes, 0, count)
                while (buffer.hasRemaining()) channel.write(buffer)
            }
            channel.force(true)
        }
    }

    /**
     * createLink 是真正的原子 create-if-absent：目标已存在时失败，绝不会替换它。
     * 临时文件与目标同目录，生产目标 Windows NTFS 支持该原语；不支持的平台由上层 fail-closed。
     */
    override fun createLink(target: Path, temporary: Path) {
        Files.createLink(target, temporary)
    }

    override fun forceDirectory(directory: Path) {
        FileChannel.open(directory, StandardOpenOption.READ).use { it.force(true) }
    }
}

/**
 * 先把完整内容写入同目录临时文件并 fsync，再用 hard-link 原子安装目标。
 *
 * 单一 JVM mutex 只覆盖稀有的首启路径，避免重叠 FileLock；相邻锁文件继续提供跨进程互斥。
 * 即使不遵守锁的外部写入恰好落在最后检查之后，createLink 也只会输掉竞争而不会覆盖用户文件。
 */
internal class AtomicBusinessLegalLinksBootstrap(
    private val fileOperations: BusinessLegalLinksBootstrapFileOperations =
        NioBusinessLegalLinksBootstrapFileOperations,
) : BusinessLegalLinksBootstrapInstaller {
    override fun installIfAbsent(target: Path, bundledDefault: () -> InputStream?) {
        val normalizedTarget = target.toAbsolutePath().normalize()
        if (Files.exists(normalizedTarget, LinkOption.NOFOLLOW_LINKS)) {
            requireOrdinaryFile(normalizedTarget)
            return
        }
        synchronized(PROCESS_BOOTSTRAP_MUTEX) {
            installUnderProcessMutex(normalizedTarget, bundledDefault)
        }
    }

    private fun installUnderProcessMutex(target: Path, bundledDefault: () -> InputStream?) {
        try {
            Files.createDirectories(target.parent)
        } catch (_: Exception) {
            unavailable()
        }
        val lockPath = target.resolveSibling("${target.fileName}.bootstrap.lock")
        val lockChannel = try {
            FileChannel.open(
                lockPath,
                StandardOpenOption.CREATE,
                StandardOpenOption.WRITE,
                LinkOption.NOFOLLOW_LINKS,
            )
        } catch (_: Exception) {
            unavailable()
        }
        lockChannel.use { channel ->
            val lock = try {
                channel.lock()
            } catch (_: Exception) {
                unavailable()
            }
            lock.use {
                if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
                    requireOrdinaryFile(target)
                    return
                }
                installCompleteFile(target, bundledDefault)
            }
        }
    }

    private fun installCompleteFile(target: Path, bundledDefault: () -> InputStream?) {
        val temporary = try {
            Files.createTempFile(target.parent, "business-desktop-", ".tmp")
        } catch (_: Exception) {
            unavailable()
        }
        try {
            val source = try {
                bundledDefault()
            } catch (_: Exception) {
                unavailable()
            } ?: unavailable()
            try {
                source.use { fileOperations.writeAndForce(temporary, it) }
            } catch (error: BusinessLegalLinksConfigurationException) {
                throw error
            } catch (_: Exception) {
                unavailable()
            }

            // 这是原子 createLink 之前的最后一次观察；测试 seam 会精确在此后放入用户文件。
            if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
                requireOrdinaryFile(target)
                return
            }
            try {
                fileOperations.createLink(target, temporary)
            } catch (_: FileAlreadyExistsException) {
                requireExistingWinner(target)
                return
            } catch (error: BusinessLegalLinksConfigurationException) {
                throw error
            } catch (_: Exception) {
                if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
                    requireOrdinaryFile(target)
                    return
                }
                // hard link 不可用时不能退化为可能覆盖目标的 move。
                unavailable()
            }
        } finally {
            runCatching { Files.deleteIfExists(temporary) }
        }
        runCatching { fileOperations.forceDirectory(target.parent) }
    }

    private fun requireExistingWinner(target: Path) {
        if (!Files.exists(target, LinkOption.NOFOLLOW_LINKS)) unavailable()
        requireOrdinaryFile(target)
    }

    private fun requireOrdinaryFile(path: Path) {
        val ordinary = runCatching {
            val attributes = Files.readAttributes(
                path,
                BasicFileAttributes::class.java,
                LinkOption.NOFOLLOW_LINKS,
            )
            attributes.isRegularFile && !attributes.isOther && !Files.isSymbolicLink(path)
        }.getOrDefault(false)
        if (!ordinary) invalid()
    }

    private fun invalid(): Nothing =
        throw BusinessLegalLinksConfigurationException(BusinessLegalLinksConfigurationErrorCode.CONFIG_INVALID)

    private fun unavailable(): Nothing =
        throw BusinessLegalLinksConfigurationException(BusinessLegalLinksConfigurationErrorCode.CONFIG_UNAVAILABLE)

    private companion object {
        val PROCESS_BOOTSTRAP_MUTEX = Any()
    }
}
