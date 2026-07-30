package com.wzx.huitai.desktop.smoke

import com.wzx.huitai.agent.business.workbench.BusinessWorkbenchSectionStatus
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

class PackagedAuthenticatedSmokeProbeTest {
    @Test
    fun `only successful workbench section statuses count as authenticated evidence`() {
        assertTrue(isVerifiedWorkbenchSectionStatus(BusinessWorkbenchSectionStatus.OK))
        assertTrue(isVerifiedWorkbenchSectionStatus(BusinessWorkbenchSectionStatus.EMPTY))
        assertFalse(isVerifiedWorkbenchSectionStatus(BusinessWorkbenchSectionStatus.ERROR))
        assertFalse(isVerifiedWorkbenchSectionStatus(BusinessWorkbenchSectionStatus.UNKNOWN))
    }

    @Test
    fun `authenticated package workflow exercises login workbench and assistant in order`() = runTest {
        val calls = mutableListOf<String>()
        val runtime = object : PackagedAuthenticatedSmokeRuntime {
            override suspend fun authenticate() {
                calls += "authenticate"
            }

            override suspend fun awaitReady(): Long {
                calls += "ready"
                return 9
            }

            override suspend fun loadWorkbench(identityEpoch: Long): PackagedWorkbenchCheck {
                calls += "workbench:$identityEpoch"
                return PackagedWorkbenchCheck(
                    sections = PackagedAuthenticatedSmokeProbe.REQUIRED_SECTIONS,
                    navigationAllowlisted = true,
                )
            }

            override suspend fun verifyAssistantController(): Boolean {
                calls += "assistant"
                return true
            }
        }

        val evidence = PackagedAuthenticatedSmokeWorkflow(
            oaBaseUrl = "http://127.0.0.1:43123",
            runtime = runtime,
        ).run()

        assertEquals(listOf("authenticate", "ready", "workbench:9", "assistant"), calls)
        assertTrue(evidence.ready)
        assertTrue(evidence.workbenchReady)
        assertTrue(evidence.assistantControllerReady)
    }

    @Test
    fun `writes only non-secret authenticated package evidence`() {
        val root = Files.createTempDirectory("packaged-auth-probe")
        val report = root.resolve("report.json")

        PackagedAuthenticatedSmokeProbe(report).write(
            PackagedAuthenticatedSmokeEvidence(
                profile = "business-desktop",
                oaLoopback = true,
                ready = true,
                identityEpoch = 7,
                workbenchReady = true,
                workbenchSections = setOf("notices", "shortcuts", "summary", "profile", "teams", "schedule"),
                navigationAllowlisted = true,
                assistantControllerReady = true,
            ),
        )

        val encoded = Files.readString(report)
        val json = Json.parseToJsonElement(encoded).jsonObject
        assertTrue(json.getValue("ready").jsonPrimitive.content.toBoolean())
        assertTrue(json.getValue("workbenchReady").jsonPrimitive.content.toBoolean())
        assertTrue(json.getValue("assistantControllerReady").jsonPrimitive.content.toBoolean())
        assertFalse(encoded.contains("accessToken", ignoreCase = true))
        assertFalse(encoded.contains("refreshToken", ignoreCase = true))
        assertFalse(encoded.contains("password", ignoreCase = true))
    }

    @Test
    fun `rejects incomplete authenticated package evidence`() {
        val report = Files.createTempDirectory("packaged-auth-probe-invalid").resolve("report.json")

        assertFailsWith<IllegalArgumentException> {
            PackagedAuthenticatedSmokeProbe(report).write(
                PackagedAuthenticatedSmokeEvidence(
                    profile = "business-desktop",
                    oaLoopback = true,
                    ready = true,
                    identityEpoch = 1,
                    workbenchReady = true,
                    workbenchSections = setOf("profile"),
                    navigationAllowlisted = true,
                    assistantControllerReady = true,
                ),
            )
        }
    }
}
