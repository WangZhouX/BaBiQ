package com.wzx.babiq.desktop.runtime

import com.wzx.babiq.desktop.app.DesktopConfig
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.Path
import kotlin.io.path.exists
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * 桌面端启动期的运行时会话。
 *
 * 这个对象把“客户端应该连接哪个后端端口”和“是否由桌面端托管后端进程”放在同一个生命周期里。
 * 主窗口关闭时调用 close，可以确保由安装包拉起的 Spring Boot 子进程不会留在用户机器上继续运行。
 *
 * @property config 传给 AgentClient 与 KtorAgentTransport 的真实连接配置。
 * @property backendHandle 只有打包态内置后端启动成功时才存在；开发态为空，继续使用外部 8080 后端。
 */
class DesktopRuntimeSession(
	val config: DesktopConfig,
	private val backendHandle: BackendProcessHandle? = null,
) : AutoCloseable {
	/**
	 * 当前会话是否管理了内置后端进程；测试和诊断可用它区分“打包态”和“开发态回退”。
	 */
	val managesBackend: Boolean = backendHandle != null

	override fun close() {
		backendHandle?.close()
	}
}

/**
 * 后端启动所需的不可变参数。
 *
 * 把命令构造收敛成纯数据，既方便测试，也避免 Main.kt 直接拼接命令行字符串。
 *
 * @property javaExecutable 当前桌面运行时自带的 java 可执行文件。
 * @property backendJar 安装包资源目录里的 Spring Boot fat jar。
 * @property port 桌面端为本次会话分配的本机空闲端口。
 * @property wsPath WebSocket 协议路径，必须和后端 babiq.ws.path 保持一致。
 * @property logFile 后端 stdout/stderr 追加写入的本地日志文件。
 */
data class BackendLaunchRequest(
	val javaExecutable: Path,
	val backendJar: Path,
	val port: Int,
	val wsPath: String,
	val logFile: Path,
) {
	/**
	 * ProcessBuilder 使用的参数数组。
	 *
	 * server.address 固定为 127.0.0.1，避免安装包启动的本地服务暴露到局域网。
	 */
	val command: List<String> = listOf(
		javaExecutable.toString(),
		"-jar",
		backendJar.toString(),
		"--server.port=$port",
		"--server.address=127.0.0.1",
		"--babiq.ws.path=$wsPath",
	)
}

/**
 * 选择一个本机 loopback 空闲端口。
 */
fun interface PortAllocator {
	fun allocate(): Int
}

/**
 * 定位打包进桌面安装目录的后端 jar；找不到时表示当前是开发态或外部后端模式。
 */
fun interface BackendJarLocator {
	fun locate(): Path?
}

/**
 * 定位当前桌面运行时可用的 java 可执行文件。
 */
fun interface JavaExecutableLocator {
	fun locate(): Path
}

/**
 * 产出后端进程日志文件路径，并负责让父目录可写。
 */
fun interface BackendLogFileProvider {
	fun resolve(): Path
}

/**
 * 启动后端进程的边界接口。
 */
fun interface BackendProcessStarter {
	fun start(request: BackendLaunchRequest): BackendProcessHandle
}

/**
 * 等待后端监听端口可连接的边界接口。
 */
fun interface BackendReadinessProbe {
	fun awaitReady(port: Int, timeout: Duration): Boolean
}

/**
 * 被桌面端托管的后端进程句柄。
 */
interface BackendProcessHandle : AutoCloseable {
	/**
	 * 子进程是否仍在运行；用于启动失败诊断和测试断言。
	 */
	val alive: Boolean
}

/**
 * 桌面端运行时启动器。
 *
 * 打包态：找到内置 backend jar，分配动态端口，启动后端并返回指向该端口的 DesktopConfig。
 * 开发态：找不到内置 jar 时不启动子进程，保留默认 8080 配置，方便继续分开运行 backend 和 desktop。
 */
class DesktopRuntimeLauncher(
	private val jarLocator: BackendJarLocator = ComposeAppBackendJarLocator(),
	private val portAllocator: PortAllocator = LoopbackPortAllocator(),
	private val javaExecutableLocator: JavaExecutableLocator = CurrentRuntimeJavaExecutableLocator(),
	private val logFileProvider: BackendLogFileProvider = UserHomeBackendLogFileProvider(),
	private val processStarter: BackendProcessStarter = JvmBackendProcessStarter(),
	private val readinessProbe: BackendReadinessProbe = SocketBackendReadinessProbe(),
	private val wsPath: String = "/ws/agent",
	private val readinessTimeout: Duration = 45.seconds,
) {
	/**
	 * 启动桌面运行时会话，并把动态端口写入客户端配置。
	 */
	fun start(): DesktopRuntimeSession {
		val backendJar = jarLocator.locate() ?: return DesktopRuntimeSession(DesktopConfig())
		val port = portAllocator.allocate()
		val request = BackendLaunchRequest(
			javaExecutable = javaExecutableLocator.locate(),
			backendJar = backendJar,
			port = port,
			wsPath = wsPath,
			logFile = logFileProvider.resolve(),
		)
		val backendHandle = processStarter.start(request)
		if (!readinessProbe.awaitReady(port, readinessTimeout)) {
			backendHandle.close()
			error("BaBiQ backend did not become ready on 127.0.0.1:$port within $readinessTimeout")
		}
		return DesktopRuntimeSession(
			config = DesktopConfig(backendPort = port, backendPath = wsPath),
			backendHandle = backendHandle,
		)
	}
}

/**
 * 通过临时绑定 127.0.0.1:0 获取一个可用端口。
 */
class LoopbackPortAllocator : PortAllocator {
	override fun allocate(): Int {
		ServerSocket().use { socket ->
			socket.reuseAddress = false
			socket.bind(InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0))
			return socket.localPort
		}
	}
}

/**
 * 从 Compose Desktop appResourcesRootDir 对应的运行期目录中定位内置后端 jar。
 *
 * `babiq.backend.jar` 和 `BABIQ_BACKEND_JAR` 用于本地诊断；正式安装包优先依赖
 * `compose.application.resources.dir/backend/babiq-server.jar`。
 */
class ComposeAppBackendJarLocator : BackendJarLocator {
	override fun locate(): Path? {
		systemPropertyPath("babiq.backend.jar")?.let { return it }
		environmentPath("BABIQ_BACKEND_JAR")?.let { return it }
		val appResources = System.getProperty("compose.application.resources.dir")?.takeIf { it.isNotBlank() }
			?: return null
		return Path(appResources).resolve("backend").resolve("babiq-server.jar").takeIf { it.exists() }
	}

	private fun systemPropertyPath(name: String): Path? =
		System.getProperty(name)
			?.takeIf { it.isNotBlank() }
			?.let { Path(it) }
			?.takeIf { it.exists() }

	private fun environmentPath(name: String): Path? =
		System.getenv(name)
			?.takeIf { it.isNotBlank() }
			?.let { Path(it) }
			?.takeIf { it.exists() }
}

/**
 * 使用当前 jpackage/Compose runtime image 中的 java 启动后端。
 */
class CurrentRuntimeJavaExecutableLocator : JavaExecutableLocator {
	override fun locate(): Path {
		val executableName = if (System.getProperty("os.name").contains("Windows", ignoreCase = true)) {
			"java.exe"
		} else {
			"java"
		}
		return Path(System.getProperty("java.home")).resolve("bin").resolve(executableName)
	}
}

/**
 * 把后端日志放到用户目录，避免写安装目录导致权限问题。
 */
class UserHomeBackendLogFileProvider : BackendLogFileProvider {
	override fun resolve(): Path {
		val logDir = Path(System.getProperty("user.home")).resolve(".babiq").resolve("logs")
		Files.createDirectories(logDir)
		return logDir.resolve("backend.log")
	}
}

/**
 * 真实的 JVM 子进程启动器。
 */
class JvmBackendProcessStarter : BackendProcessStarter {
	override fun start(request: BackendLaunchRequest): BackendProcessHandle {
		Files.createDirectories(request.logFile.parent)
		val process = ProcessBuilder(request.command)
			.redirectErrorStream(true)
			.redirectOutput(ProcessBuilder.Redirect.appendTo(request.logFile.toFile()))
			.start()
		return ProcessBackendHandle(process)
	}
}

/**
 * Process 的安全关闭包装。
 */
private class ProcessBackendHandle(
	private val process: Process,
) : BackendProcessHandle {
	override val alive: Boolean
		get() = process.isAlive

	override fun close() {
		if (!process.isAlive) {
			return
		}
		process.destroy()
		if (!process.waitFor(3, java.util.concurrent.TimeUnit.SECONDS)) {
			process.destroyForcibly()
		}
	}
}

/**
 * 用 TCP 连接探测 Spring Boot 是否已经监听动态端口。
 */
class SocketBackendReadinessProbe(
	private val intervalMillis: Long = 200,
) : BackendReadinessProbe {
	override fun awaitReady(port: Int, timeout: Duration): Boolean {
		val deadline = System.nanoTime() + timeout.inWholeNanoseconds
		while (System.nanoTime() < deadline) {
			if (canConnect(port)) {
				return true
			}
			Thread.sleep(intervalMillis)
		}
		return false
	}

	private fun canConnect(port: Int): Boolean =
		try {
			Socket().use { socket ->
				socket.connect(InetSocketAddress(InetAddress.getByName("127.0.0.1"), port), 300)
			}
			true
		} catch (_: Exception) {
			false
		}
}
