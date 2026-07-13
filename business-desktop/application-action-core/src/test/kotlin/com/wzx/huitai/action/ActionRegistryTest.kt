package com.wzx.huitai.action

import com.wzx.huitai.action.model.ActionDescriptor
import com.wzx.huitai.action.model.ActionError
import com.wzx.huitai.action.model.ActionErrorCode
import com.wzx.huitai.action.model.ActionExecutionState
import com.wzx.huitai.action.model.ActionIdentityScope
import com.wzx.huitai.action.model.ActionPreview
import com.wzx.huitai.action.model.ActionReplayPolicy
import com.wzx.huitai.action.model.ActionResult
import com.wzx.huitai.action.model.ActionRiskLevel
import com.wzx.huitai.action.model.ActionTarget
import com.wzx.huitai.action.model.ReconciliationPolicy
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.JsonElement
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
    fun `freeze publishes an immutable lookup snapshot and rejects later registration`() {
        val version1 = registeredAction("demo.frozen", 1)
        val version2 = registeredAction("demo.frozen", 2)
        val registry = ActionRegistry().apply {
            register(version1)
            register(version2)
        }

        registry.freeze()

        assertEquals(true, registry.isFrozen)
        assertSame(version2, assertIs<ActionResolution.Found>(registry.resolve("demo.frozen")).action)
        assertSame(version1, assertIs<ActionResolution.Found>(registry.resolve("demo.frozen", 1)).action)
        val error = assertFailsWith<IllegalStateException> {
            registry.register(registeredAction("demo.late", 1))
        }
        assertEquals("动作注册表已冻结，不能继续注册: demo.late@1", error.message)
        registry.freeze()
        assertSame(version2, assertIs<ActionResolution.Found>(registry.resolve("demo.frozen")).action)
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
        val inputCodec = DemoInputCodec()
        val outputCodec = DemoOutputCodec()
        val registered = RegisteredAction(action, inputCodec, outputCodec)

        assertSame(action, registered.action)
        assertSame(inputCodec, registered.inputCodec)
        assertSame(outputCodec, registered.outputCodec)
        assertSame(action.descriptor, registered.descriptor)
    }

    @Test
    fun `invalid json returns validation failed before any action method`() = runTest {
        val action = CountingAction(descriptor("demo.validated", 1))
        val registered = RegisteredAction(action, DemoInputCodec(), DemoOutputCodec())
        val context = context()
        val invalidInput = buildJsonObject { put("value", "not-an-integer") }

        listOf(
            registered.invokePreview(invalidInput, context),
            registered.invokeExecute(invalidInput, context),
            registered.invokeReconcile(invalidInput, context, "remote-secret", "execution-invalid"),
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
        val registered = RegisteredAction(action, DemoInputCodec(), DemoOutputCodec())
        val input = buildJsonObject { put("value", 7) }

        assertIs<ActionInvocationResult.Previewed>(registered.invokePreview(input, context()))
        val executed = assertIs<ActionInvocationResult.Executed>(registered.invokeExecute(input, context()))
        val success = assertIs<ActionResult.Success<JsonElement>>(executed.result)
        assertEquals(buildJsonObject { put("value", 7); put("secret", "secret-output") }, success.output)
        assertEquals(buildJsonObject { put("value", 7); put("secret", "redacted-output") }, success.redactedOutput)
        val reconciled = assertIs<ActionInvocationResult.Reconciled>(
            registered.invokeReconcile(input, context(), "remote-1", "execution-7"),
        )
        assertEquals("execution-7", reconciled.executionId)
        assertIs<ReconciliationResult.Unsupported>(reconciled.result)
        assertEquals(1, action.previewCount)
        assertEquals(1, action.executeCount)
        assertEquals(1, action.reconcileCount)
    }

    @Test
    fun `codec exceptions become validation failure without invoking action`() = runTest {
        val action = CountingAction(descriptor("demo.throwing-codec", 1))
        val registered = RegisteredAction(
            action,
            ActionInputCodec { error("secret-codec-exception") },
            DemoOutputCodec(),
        )

        val invocation = registered.invokeExecute(buildJsonObject { put("value", 7) }, context())

        val failure = assertIs<ActionInvocationResult.Failure>(invocation)
        assertEquals(ActionErrorCode.VALIDATION_FAILED, failure.error.code)
        assertFalse("secret-codec-exception" in failure.toString())
        assertEquals(0, action.executeCount)
    }

    @Test
    fun `output codec exceptions preserve succeeded terminal and report redacted encoding failure`() = runTest {
        val action = CountingAction(descriptor("demo.throwing-output", 1))
        val registered = RegisteredAction(
            action,
            DemoInputCodec(),
            ActionOutputCodec<DemoOutput> { error("secret-output-codec-exception") },
        )

        val invocation = registered.invokeExecute(buildJsonObject { put("value", 7) }, context())

        val failure = assertIs<ActionInvocationResult.OutputEncodingFailed>(invocation)
        assertEquals("execution-7", failure.executionId)
        assertEquals(ActionExecutionState.SUCCEEDED, failure.terminalState)
        assertEquals(ActionErrorCode.PROTOCOL_ERROR, failure.error.code)
        assertFalse("secret-output-codec-exception" in failure.toString())
        assertFalse("secret-output" in failure.toString())
        assertEquals(1, action.executeCount)
    }

    @Test
    fun `input codec cancellation propagates without invoking action`() = runTest {
        val action = CountingAction(descriptor("demo.input-cancel", 1))
        val registered = RegisteredAction(
            action,
            ActionInputCodec { throw CancellationException("secret-input-cancel") },
            DemoOutputCodec(),
        )

        assertFailsWith<CancellationException> {
            registered.invokeExecute(buildJsonObject { put("value", 7) }, context())
        }
        assertEquals(0, action.executeCount)
    }

    @Test
    fun `output codec cancellation propagates after action succeeds`() = runTest {
        val action = CountingAction(descriptor("demo.output-cancel", 1))
        val registered = RegisteredAction(
            action,
            DemoInputCodec(),
            ActionOutputCodec<DemoOutput> { throw CancellationException("secret-output-cancel") },
        )

        assertFailsWith<CancellationException> {
            registered.invokeExecute(buildJsonObject { put("value", 7) }, context())
        }
        assertEquals(1, action.executeCount)
    }

    @Test
    fun `registry boundary summaries do not expose identity or implementations`() {
        val registered = registeredAction("demo.safe-log", 1)

        assertFalse("secret" in context().toString())
        assertFalse(registered.action.toString() in registered.toString())
        assertFalse(registered.inputCodec.toString() in registered.toString())
        assertFalse(registered.outputCodec.toString() in registered.toString())
    }

    private fun registeredAction(id: String, version: Int): RegisteredAction<DemoInput, DemoOutput> =
        RegisteredAction(CountingAction(descriptor(id, version)), DemoInputCodec(), DemoOutputCodec())

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

    private data class DemoOutput(val value: Int, val secret: String)

    private class DemoInputCodec : ActionInputCodec<DemoInput> {
        override fun decode(input: JsonObject): ActionInputDecodeResult<DemoInput> = try {
            ActionInputDecodeResult.Success(DemoInput(input.getValue("value").jsonPrimitive.int))
        } catch (_: Exception) {
            ActionInputDecodeResult.Failure(
                ActionError(ActionErrorCode.VALIDATION_FAILED, "value 必须是整数"),
            )
        }
    }

    private class DemoOutputCodec : ActionOutputCodec<DemoOutput> {
        override fun encode(output: DemoOutput): JsonElement = buildJsonObject {
            put("value", output.value)
            put("secret", output.secret)
        }
    }

    private class CountingAction(
        override val descriptor: ActionDescriptor,
    ) : ApplicationAction<DemoInput, DemoOutput> {
        var previewCount = 0
        var executeCount = 0
        var reconcileCount = 0

        override suspend fun preview(input: DemoInput, context: ActionContext): ActionPreview {
            previewCount += 1
            return ActionPreview("execution-${input.value}", "预览")
        }

        override suspend fun execute(input: DemoInput, context: ActionContext): ActionResult<DemoOutput> {
            executeCount += 1
            return ActionResult.Success(
                executionId = "execution-${input.value}",
                output = DemoOutput(input.value, "secret-output"),
                redactedOutput = DemoOutput(input.value, "redacted-output"),
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
