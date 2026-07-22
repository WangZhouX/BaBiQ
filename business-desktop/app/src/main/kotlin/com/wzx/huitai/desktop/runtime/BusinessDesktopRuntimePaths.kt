package com.wzx.huitai.desktop.runtime

import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.attribute.BasicFileAttributes
import java.nio.file.attribute.PosixFilePermission

/**
 * 业务桌面父进程与内置 Agent 子进程共享的隔离路径清单。
 *
 * 所有路径都由调用方给定的用户目录确定性派生；创建目录后再次使用真实路径校验，防止已有
 * 符号链接或 reparse point 把数据库、密钥、日志、记忆及锁文件引到隔离根之外。
 */
class BusinessDesktopRuntimePaths private constructor(
    val root: Path,
    val agentRoot: Path,
    val agentDatabase: Path,
    val agentKeyStore: Path,
    val agentLog: Path,
    val agentMemoryRoot: Path,
    val agentTeamRoot: Path,
    val agentInstanceLock: Path,
    val agentSessionToken: Path,
    val agentDevelopmentSession: Path,
    val agentAttachmentRoot: Path,
    val agentClipboardAttachmentRoot: Path,
    val desktopRoot: Path,
    val desktopDatabase: Path,
    val desktopKeyStore: Path,
    val desktopLog: Path,
    val desktopInstanceLock: Path,
    val desktopInstallationId: Path,
    val desktopConfiguration: Path,
    val desktopAuthRevocationMarker: Path,
    val desktopAuthRevocationFallbackMarker: Path,
) {
    override fun toString(): String = "BusinessDesktopRuntimePaths(root=[REDACTED])"

    companion object {
        /** 创建精确运行目录；一次性 token、安装 ID、数据库和密钥文件均不会被提前创建。 */
        fun create(home: Path): BusinessDesktopRuntimePaths {
            val normalizedHome = home.toAbsolutePath().normalize()
            require(normalizedHome.fileName != null) { "home must identify a directory" }
            Files.createDirectories(normalizedHome)
            rejectLinkIfPresent(normalizedHome)

            val root = normalizedHome.resolve(".huitai-agent-desktop")
            val agent = root.resolve("agent")
            val desktop = root.resolve("desktop")
            val result = BusinessDesktopRuntimePaths(
                root = root,
                agentRoot = agent,
                agentDatabase = agent.resolve("data/babiq-business.db"),
                agentKeyStore = agent.resolve("secrets/business-agent.jceks"),
                agentLog = agent.resolve("logs/backend.log"),
                agentMemoryRoot = agent.resolve("memory"),
                agentTeamRoot = agent.resolve("teams"),
                agentInstanceLock = agent.resolve("instance.lock"),
                agentSessionToken = agent.resolve("session-token"),
                agentDevelopmentSession = agent.resolve("development-session.json"),
                agentAttachmentRoot = agent.resolve("attachments"),
                agentClipboardAttachmentRoot = agent.resolve("attachments/clipboard"),
                desktopRoot = desktop,
                desktopDatabase = desktop.resolve("data/business-desktop.db"),
                desktopKeyStore = desktop.resolve("secrets/business-desktop.jceks"),
                desktopLog = desktop.resolve("logs/desktop.log"),
                desktopInstanceLock = desktop.resolve("instance.lock"),
                desktopInstallationId = desktop.resolve("installation-id"),
                desktopConfiguration = desktop.resolve("config/business-desktop.properties"),
                desktopAuthRevocationMarker = desktop.resolve("secrets/auth-revoked-v1"),
                desktopAuthRevocationFallbackMarker = desktop.resolve("data/auth-revoked-v1"),
            )
            result.prepareDirectories()
            return result
        }

        /** 只检查受控根及其后代，避免把合法用户目录祖先误判为业务侧可控制链接。 */
        private fun rejectLinkIfPresent(path: Path) {
            if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) return
            val attributes = Files.readAttributes(
                path,
                BasicFileAttributes::class.java,
                LinkOption.NOFOLLOW_LINKS,
            )
            require(!Files.isSymbolicLink(path) && !attributes.isOther) {
                "business desktop runtime path must not contain a link"
            }
        }
    }

    /** 创建文件父目录和长期根目录，再校验每个真实目录仍位于真实隔离根内。 */
    private fun prepareDirectories() {
        val directories = linkedSetOf(
            root,
            agentRoot,
            agentDatabase.parent,
            agentKeyStore.parent,
            agentLog.parent,
            agentMemoryRoot,
            agentTeamRoot,
            agentAttachmentRoot,
            agentClipboardAttachmentRoot,
            desktopRoot,
            desktopDatabase.parent,
            desktopKeyStore.parent,
            desktopLog.parent,
            desktopConfiguration.parent,
        )
        val controlledLeaves = linkedSetOf(
            agentDatabase,
            agentKeyStore,
            agentLog,
            agentInstanceLock,
            agentSessionToken,
            agentDevelopmentSession,
            desktopDatabase,
            desktopKeyStore,
            desktopLog,
            desktopInstanceLock,
            desktopInstallationId,
            desktopConfiguration,
            desktopAuthRevocationMarker,
            desktopAuthRevocationFallbackMarker,
        )
        rejectExistingLinks(directories + controlledLeaves)
        directories.forEach(Files::createDirectories)
        val realRoot = root.toRealPath()
        directories.forEach { directory ->
            require(directory.toRealPath().startsWith(realRoot)) {
                "business desktop runtime path escaped isolated root"
            }
        }
        val realAgentRoot = agentRoot.toRealPath()
        require(agentAttachmentRoot.toRealPath().startsWith(realAgentRoot)) {
            "business attachment root escaped isolated Agent root"
        }
        require(agentClipboardAttachmentRoot.toRealPath().startsWith(realAgentRoot)) {
            "business clipboard attachment root escaped isolated Agent root"
        }
        applyOwnerOnlyDirectoryPermissions(agentAttachmentRoot)
        applyOwnerOnlyDirectoryPermissions(agentClipboardAttachmentRoot)
    }

    private fun applyOwnerOnlyDirectoryPermissions(path: Path) {
        runCatching {
            Files.setPosixFilePermissions(
                path,
                setOf(
                    PosixFilePermission.OWNER_READ,
                    PosixFilePermission.OWNER_WRITE,
                    PosixFilePermission.OWNER_EXECUTE,
                ),
            )
        }
    }

    /** 对隔离根以下的每个已存在路径片段做 NOFOLLOW 检查。 */
    private fun rejectExistingLinks(paths: Iterable<Path>) {
        paths.forEach { path ->
            var current = root
            if (Files.exists(current, LinkOption.NOFOLLOW_LINKS)) rejectControlledLink(current)
            val relative = root.relativize(path)
            relative.forEach { segment ->
                current = current.resolve(segment)
                if (Files.exists(current, LinkOption.NOFOLLOW_LINKS)) rejectControlledLink(current)
            }
        }
    }

    /** 拒绝符号链接及 Windows reparse-like other 文件类型。 */
    private fun rejectControlledLink(path: Path) {
        val attributes = Files.readAttributes(
            path,
            BasicFileAttributes::class.java,
            LinkOption.NOFOLLOW_LINKS,
        )
        require(!Files.isSymbolicLink(path) && !attributes.isOther) {
            "business desktop runtime path must not contain a link"
        }
    }
}
