package com.wzx.huitai.desktop.runtime

import com.wzx.huitai.agent.client.AgentConnectRequest
import com.wzx.huitai.agent.client.DesktopSessionIdentity
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.nio.file.Path
import java.util.Arrays
import java.util.concurrent.atomic.AtomicBoolean

/** 只在 loopback 上短暂绑定端口 0，并把操作系统选择的非零端口返回给子进程。 */
fun interface LoopbackPortAllocator {
    fun allocate(): Int

    companion object {
        /** 生产分配器不绑定 wildcard，也不经过 DNS。 */
        val SYSTEM = LoopbackPortAllocator {
            ServerSocket().use { socket ->
                socket.reuseAddress = false
                socket.bind(InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0))
                socket.localPort.also { require(it in 1..65535) { "loopback port allocation failed" } }
            }
        }
    }
}

/**
 * 一次子进程启动的冻结参数。
 *
 * 命令只包含非敏感 profile 和隔离路径；后端 KeyStore 密码只通过 [environment] 交给
 * ProcessBuilder。对象字符串隐藏路径、安装/会话标识、token 与密码。
 */
class BusinessAgentLaunchRequest private constructor(
    val paths: BusinessDesktopRuntimePaths,
    val backendJar: Path,
    val javaExecutable: Path,
    val port: Int,
    private val tokenFile: DesktopSessionTokenFile,
    backendKeyStorePassword: CharArray,
) : AutoCloseable {
    private val password = backendKeyStorePassword.copyOf()
    private val closed = AtomicBoolean(false)

    val identity: DesktopSessionIdentity
        get() = tokenFile.identity

    val connectRequest: AgentConnectRequest = AgentConnectRequest(
        url = "ws://127.0.0.1:$port/ws/agent",
        identity = identity,
    )

    init {
        require(port in 1..65535) { "server port must be a non-zero TCP port" }
        require(password.isNotEmpty()) { "backend KeyStore password must not be empty" }
    }

    /** 生成 ProcessBuilder 参数数组，绝不拼成 shell 字符串。 */
    fun command(): List<String> = listOf(
        javaExecutable.toString(),
        "-jar",
        backendJar.toString(),
        "--spring.profiles.active=business-desktop",
        "--server.address=127.0.0.1",
        "--server.port=$port",
        "--babiq.business.runtime-dir=${paths.agentRoot}",
        "--babiq.persistence.database-path=${paths.agentDatabase}",
        "--babiq.secrets.keystore-path=${paths.agentKeyStore}",
        "--logging.file.name=${paths.agentLog}",
        "--babiq.memory.long-term.root-dir=${paths.agentMemoryRoot}",
        "--babiq.team.root-dir=${paths.agentTeamRoot}",
        "--babiq.business.backend-lock-path=${paths.agentInstanceLock}",
        "--babiq.business.session-token-file=${paths.agentSessionToken}",
        "--babiq.business.attachment-clipboard-root=${paths.agentClipboardAttachmentRoot}",
    )

    /** 只在实际启动前短暂产生环境字符串；调用方不得记录返回值。 */
    fun environment(): Map<String, String> {
        check(!closed.get()) { "launch request is closed" }
        return mapOf(BACKEND_KEYSTORE_PASSWORD_ENV to password.concatToString())
    }

    /** 后端消费后的 token 文件不存在；启动失败或关闭时删除尚未消费的文件并擦除密码字符。 */
    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        tokenFile.close()
        Arrays.fill(password, '\u0000')
    }

    override fun toString(): String =
        "BusinessAgentLaunchRequest(java=[REDACTED], jar=[REDACTED], paths=[REDACTED], " +
            "port=$port, identity=[REDACTED], password=[REDACTED])"

    companion object {
        const val BACKEND_KEYSTORE_PASSWORD_ENV = "BABIQ_SECRETS_KEYSTORE_PASSWORD"

        /** 为每个调用创建全新的 desktopSessionId、token 和动态 loopback 端口。 */
        fun create(
            paths: BusinessDesktopRuntimePaths,
            desktopInstanceId: String,
            backendJar: Path,
            javaExecutable: Path = Path.of(System.getProperty("java.home"), "bin", executableName()),
            backendKeyStorePassword: CharArray,
            port: Int? = null,
            loopbackPortAllocator: LoopbackPortAllocator = LoopbackPortAllocator.SYSTEM,
        ): BusinessAgentLaunchRequest {
            val selectedPort = port ?: loopbackPortAllocator.allocate()
            require(selectedPort in 1..65535) { "server port must be a non-zero TCP port" }
            val tokenFile = DesktopSessionTokenFile.create(paths.agentSessionToken, desktopInstanceId)
            return try {
                BusinessAgentLaunchRequest(
                    paths = paths,
                    backendJar = backendJar.toAbsolutePath().normalize(),
                    javaExecutable = javaExecutable.toAbsolutePath().normalize(),
                    port = selectedPort,
                    tokenFile = tokenFile,
                    backendKeyStorePassword = backendKeyStorePassword,
                )
            } catch (failure: Throwable) {
                tokenFile.close()
                throw failure
            }
        }

        private fun executableName(): String =
            if (System.getProperty("os.name").startsWith("Windows", ignoreCase = true)) "java.exe" else "java"
    }
}
