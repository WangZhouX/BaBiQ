package com.wzx.huitai.desktop.smoke

import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DirectIdeaRunConfigurationContractTest {
    @Test
    fun `direct idea configurations run spring boot and compose main classes without gradle backend task`() {
        val root = Path.of("..", "..")
        val backend = root.resolve(".run/Business Backend Direct.run.xml").toFile().readText()
        val frontend = root.resolve(".run/Business Frontend Direct.run.xml").toFile().readText()

        assertTrue(backend.contains("SpringBootApplicationConfigurationType"))
        assertTrue(backend.contains("com.wzx.babiq.server.BaBiQApplication"))
        assertFalse(backend.contains("runBusinessBackendDevelopment"))
        assertTrue(backend.contains("HUITAI_BUSINESS_DIRECT_DEVELOPMENT"))
        assertTrue(backend.contains("HUITAI_OA_BASE_URL"))
        assertTrue(backend.contains("--server.port=49391"))

        assertTrue(frontend.contains("com.wzx.huitai.desktop.MainKt"))
        assertFalse(frontend.contains("runBusinessFrontendDevelopment"))
        assertTrue(frontend.contains("HUITAI_DESKTOP_EXTERNAL_BACKEND"))
        assertTrue(frontend.contains("HUITAI_BUSINESS_DIRECT_DEVELOPMENT"))
        assertTrue(frontend.contains("HUITAI_DESKTOP_HOME"))
    }
}
