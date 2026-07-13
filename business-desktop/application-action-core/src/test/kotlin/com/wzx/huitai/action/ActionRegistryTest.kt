package com.wzx.huitai.action

import com.wzx.huitai.action.model.ActionDescriptor
import com.wzx.huitai.action.model.ActionError
import com.wzx.huitai.action.model.ActionErrorCode
import com.wzx.huitai.action.model.ActionIdentityScope
import com.wzx.huitai.action.model.ActionPreview
import com.wzx.huitai.action.model.ActionReplayPolicy
import com.wzx.huitai.action.model.ActionResult
import com.wzx.huitai.action.model.ActionRiskLevel
import com.wzx.huitai.action.model.ActionTarget
import com.wzx.huitai.action.model.ReconciliationPolicy
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertSame
import kotlin.test.assertFalse

class ActionRegistryTest {
    @Test
    fun `duplicate action id and version fails fast with a clear startup error`() {
        val registry = ActionRegistry()
        registry.register(registeredAction("demo.action", 1))

        val error = assertFailsWith<IllegalStateException> {
            registry.register(registeredAction("demo.action", 1))
        }

        assertEquals("动作重复注册: demo.action@1", error.message)
    }

    @Test
    fun `same action id keeps multiple versions and resolve selects exact or highest`() {
        val version1 = registeredAction("demo.action", 1)
        val version3 = registeredAction("demo.action", 3)
        val version2 = registeredAction("demo.action", 2)
        val registry = ActionRegistry().apply {
            register(version1)
            register(version3)
            register(version2)
        }

        assertSame(version3, assertIs<ActionResolution.Found>(registry.resolve("demo.action")).action)
        assertSame(version2, assertIs<ActionResolution.Found>(registry.resolve("demo.action", 2)).action)
        assertSame(version1, assertIs<ActionResolution.Found>(registry.resolve("demo.action", 1)).action)
    }

    @Test
    fun `unknown action or version returns structured action not found error`() {
        val registry = ActionRegistry().apply { register(registeredAction("demo.action", 1)) }

        listOf(
            registry.resolve("missing.action"),
            registry.resolve("demo.action", 99),
        ).forEach { resolution ->
            val error = assertIs<ActionResolution.NotFound>(resolution).error
            assertEquals(ActionErrorCode.ACTION_NOT_FOUND, error.code)
        }
    }

    @Test
    fun `registered action keeps descriptor action and codec paired`() {
        val action = CountingAction(descriptor("demo.paired", 1))
        val codec = DemoInputCodec()
        val registered = RegisteredAction(action, codec)

        assertSame(action, registered.action)
        assertSame(codec, registered.codec)
        assertSame(action.descriptor, registered.descriptor)
    }

    @Test
    fun `invalid json returns validation failed before any action method`() = runTest {
        val action = CountingAction(descriptor("demo.validated", 1))
        val registered = RegisteredAction(action, DemoInputCodec())
        val context = context()
        val invalidInput = buildJsonObject { put("value", "not-an-integer") }

        listOf(
            registered.invokePreview(invalidInput, context),
            registered.invokeExecute(invalidInput, context),
            registered.invokeReconcile(invalidInput, context, "remote-secret"),
        ).forEach { invocation ->
            val failure = assertIs<ActionInvocationResult.Failure>(invocation)
            assertEquals(ActionErrorCode.VALIDATION_FAILED, failure.error.code)
        }
        assertEquals(0, action.previewCount)
        assertEquals(0, action.executeCount)
        assertEquals(0, action.reconcileCount)
    }

    @Test
    fun `valid json invokes typed action without unchecked public api`() = runTest {
        val action = CountingAction(descriptor("demo.typed", 1))
        val registered = RegisteredAction(action, DemoInputCodec())
        val input = buildJsonObject { put("value", 7) }

        assertIs<ActionInvocationResult.Previewed>(registered.invokePreview(input, context()))
        assertIs<ActionInvocationResult.Executed>(registered.invokeExecute(input, context()))
        val reconciled = assertIs<ActionInvocationResult.Reconciled>(
            registered.invokeReconcile(input, context(), "remote-1"),
        )
        assertIs<ReconciliationResult.Unsupported>(reconciled.result)
        assertEquals(1, action.previewCount)
        assertEquals(1, action.executeCount)
        assertEquals(1, action.reconcileCount)
    }

    @Test
    fun `codec exceptions become validation failure without invoking action`() = runTest {
        val action = CountingAction(descriptor("demo.throwing-codec", 1))
        val registered = RegisteredAction(action, ActionInputCodec { error("secret-codec-exception") })

        val invocation = registered.invokeExecute(buildJsonObject { put("value", 7) }, context())

        val failure = assertIs<ActionInvocationResult.Failure>(invocation)
        assertEquals(ActionErrorCode.VALIDATION_FAILED, failure.error.code)
        assertFalse("secret-codec-exception" in failure.toString())
        assertEquals(0, action.executeCount)
    }

    @Test
    fun `registry boundary summaries do not expose identity or implementations`() {
        val registered = registeredAction("demo.safe-log", 1)

        assertFalse("secret" in context().toString())
        assertFalse(registered.action.toString() in registered.toString())
        assertFalse(registered.codec.toString() in registered.toString())
    }

    private fun registeredAction(id: String, version: Int): RegisteredAction<DemoInput, JsonObject> =
        RegisteredAction(CountingAction(descriptor(id, version)), DemoInputCodec())

    private fun descriptor(id: String, version: Int) = ActionDescriptor(
        id = id,
        version = version,
        title = "演示动作",
        description = "注册表测试动作",
        inputSchema = buildJsonObject { put("type", "object") },
        riskLevel = ActionRiskLevel.READ_ONLY,
        requiredPermissions = setOf("demo:read"),
        target = ActionTarget("generic-form", "read"),
        replayPolicy = ActionReplayPolicy.SAFE,
        reconciliationPolicy = ReconciliationPolicy.NONE,
    )

    private fun context() = ActionContext(
        identityScope = ActionIdentityScope(
            desktopInstanceId = "desktop-secret",
            desktopSessionId = "session-secret",
            authSessionId = "auth-secret",
            identityEpoch = 1,
            userId = "user-secret",
            tenantId = "tenant-secret",
            platformId = "platform-secret",
        ),
        pageId = "page-1",
        contextRevision = 2,
        permissions = setOf("demo:read"),
    )

    private data class DemoInput(val value: Int)

    private class DemoInputCodec : ActionInputCodec<DemoInput> {
        override fun decode(input: JsonObject): ActionInputDecodeResult<DemoInput> = try {
            ActionInputDecodeResult.Success(DemoInput(input.getValue("value").jsonPrimitive.int))
        } catch (_: Exception) {
            ActionInputDecodeResult.Failure(
                ActionError(ActionErrorCode.VALIDATION_FAILED, "value 必须是整数"),
            )
        }
    }

    private class CountingAction(
        override val descriptor: ActionDescriptor,
    ) : ApplicationAction<DemoInput, JsonObject> {
        var previewCount = 0
        var executeCount = 0
        var reconcileCount = 0

        override suspend fun preview(input: DemoInput, context: ActionContext): ActionPreview {
            previewCount += 1
            return ActionPreview("execution-${input.value}", "预览")
        }

        override suspend fun execute(input: DemoInput, context: ActionContext): ActionResult<JsonObject> {
            executeCount += 1
            return ActionResult.Success(
                executionId = "execution-${input.value}",
                output = buildJsonObject { put("value", input.value) },
            )
        }

        override suspend fun reconcile(
            input: DemoInput,
            context: ActionContext,
            remoteReference: String?,
        ): ReconciliationResult {
            reconcileCount += 1
            return super.reconcile(input, context, remoteReference)
        }
    }
}
