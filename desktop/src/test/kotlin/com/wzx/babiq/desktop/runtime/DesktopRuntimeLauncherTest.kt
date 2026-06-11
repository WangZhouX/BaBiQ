package com.wzx.babiq.desktop.runtime

import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.nio.file.Path
import kotlin.io.path.createTempFile
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DesktopRuntimeLauncherTest {

	@Test
	fun `loopback port allocator returns a bindable port`() {
		val port = LoopbackPortAllocator().allocate()

		ServerSocket().use { socket ->
			socket.reuseAddress = false
			socket.bind(InetSocketAddress(InetAddress.getByName("127.0.0.1"), port))
			assertEquals(port, socket.localPort)
		}
	}

	@Test
	fun `backend launch request builds java jar command with dynamic port`() {
		val request = BackendLaunchRequest(
			javaExecutable = Path.of("C:/runtime/bin/java.exe"),
			backendJar = Path.of("C:/BaBiQ/app/resources/backend/babiq-server.jar"),
			port = 49152,
			wsPath = "/ws/agent",
			logFile = Path.of("C:/Users/dev/.babiq/logs/backend.log"),
		)

		assertEquals(
			listOf(
				"C:\\runtime\\bin\\java.exe",
				"-jar",
				"C:\\BaBiQ\\app\\resources\\backend\\babiq-server.jar",
				"--server.port=49152",
				"--server.address=127.0.0.1",
				"--babiq.ws.path=/ws/agent",
			),
			request.command.mapIndexed { index, value ->
				if (index <= 2) value.replace('/', '\\') else value
			},
		)
	}

	@Test
	fun `packaged runtime starts bundled backend on allocated port and closes it on exit`() {
		val jar = createTempFile(suffix = ".jar")
		val process = FakeBackendProcessHandle()
		var capturedRequest: BackendLaunchRequest? = null
		val launcher = DesktopRuntimeLauncher(
			jarLocator = BackendJarLocator { jar },
			portAllocator = PortAllocator { 52341 },
			javaExecutableLocator = JavaExecutableLocator { Path.of("C:/runtime/bin/java.exe") },
			logFileProvider = BackendLogFileProvider { Path.of("C:/Users/dev/.babiq/logs/backend.log") },
			processStarter = BackendProcessStarter { request ->
				capturedRequest = request
				process
			},
			readinessProbe = BackendReadinessProbe { port, _ -> port == 52341 },
		)

		val session = launcher.start()

		assertEquals(52341, session.config.backendPort)
		assertEquals("127.0.0.1", session.config.backendHost)
		assertEquals("/ws/agent", session.config.backendPath)
		assertEquals(52341, capturedRequest?.port)
		assertEquals(jar, capturedRequest?.backendJar)

		session.close()

		assertTrue(process.closed)
	}

	@Test
	fun `development runtime falls back to default config when bundled backend jar is absent`() {
		val launcher = DesktopRuntimeLauncher(
			jarLocator = BackendJarLocator { null },
			portAllocator = PortAllocator { 52341 },
			javaExecutableLocator = JavaExecutableLocator { error("java should not be located without a bundled jar") },
			logFileProvider = BackendLogFileProvider { error("log should not be created without a bundled jar") },
			processStarter = BackendProcessStarter { error("process should not start without a bundled jar") },
			readinessProbe = BackendReadinessProbe { _, _ -> error("readiness should not run without a bundled jar") },
		)

		val session = launcher.start()

		assertEquals(8080, session.config.backendPort)
		assertFalse(session.managesBackend)
	}

	private class FakeBackendProcessHandle : BackendProcessHandle {
		var closed = false

		override val alive: Boolean
			get() = !closed

		override fun close() {
			closed = true
		}
	}
}
