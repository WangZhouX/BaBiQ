package com.wzx.huitai.demo.action

import com.wzx.huitai.action.ActionContext
import com.wzx.huitai.action.ActionInputDecodeResult
import com.wzx.huitai.action.ActionInvocationResult
import com.wzx.huitai.action.model.ActionErrorCode
import com.wzx.huitai.action.model.ActionIdentityScope
import com.wzx.huitai.action.model.ActionReplayPolicy
import com.wzx.huitai.action.model.ActionRiskLevel
import com.wzx.huitai.action.model.ReconciliationPolicy
import com.wzx.huitai.action.model.ActionResult
import com.wzx.huitai.demo.gateway.FakeHuitaiGateway
import com.wzx.huitai.demo.model.DemoFormEvent
import com.wzx.huitai.demo.model.DemoFormState
import com.wzx.huitai.demo.model.DemoScreenModel
import com.wzx.huitai.presentation.form.FieldChange
import com.wzx.huitai.presentation.form.FormPatch
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class DemoActionCatalogTest {
    @Test
    fun `目录恰好注册七个动作及其固定风险与重放策略`() {
        val catalog = DemoActionCatalog(DemoScreenModel(), FakeHuitaiGateway())

        val actual = catalog.actions.associate { action ->
            action.descriptor.id to Triple(
                action.descriptor.riskLevel,
                action.descriptor.replayPolicy,
                action.descriptor.reconciliationPolicy,
            )
        }

        assertEquals(
            mapOf(
                "page.navigate" to Triple(
                    ActionRiskLevel.REVERSIBLE_WRITE,
                    ActionReplayPolicy.SAFE,
                    ReconciliationPolicy.NONE,
                ),
                "page.read_context" to Triple(
                    ActionRiskLevel.READ_ONLY,
                    ActionReplayPolicy.SAFE,
                    ReconciliationPolicy.NONE,
                ),
                "form.read_state" to Triple(
                    ActionRiskLevel.READ_ONLY,
                    ActionReplayPolicy.SAFE,
                    ReconciliationPolicy.NONE,
                ),
                "form.preview_patch" to Triple(
                    ActionRiskLevel.READ_ONLY,
                    ActionReplayPolicy.SAFE,
                    ReconciliationPolicy.NONE,
                ),
                "form.apply_patch" to Triple(
                    ActionRiskLevel.REVERSIBLE_WRITE,
                    ActionReplayPolicy.SAFE,
                    ReconciliationPolicy.NONE,
                ),
                "demo.save_draft" to Triple(
                    ActionRiskLevel.REVERSIBLE_WRITE,
                    ActionReplayPolicy.IDEMPOTENCY_KEY_REQUIRED,
                    ReconciliationPolicy.QUERY_REMOTE,
                ),
                "demo.submit" to Triple(
                    ActionRiskLevel.HIGH_RISK,
                    ActionReplayPolicy.NEVER,
                    ReconciliationPolicy.QUERY_REMOTE,
                ),
            ),
            actual,
        )
    }

    @Test
    fun `七个输入codec都拒绝未知字段和错误类型`() {
        val catalog = DemoActionCatalog(DemoScreenModel(), FakeHuitaiGateway())
        val inputs = validInputs()

        catalog.actions.forEach { registered ->
            val valid = inputs.getValue(registered.descriptor.id)
            val unknown = JsonObject(valid + ("unexpected" to JsonPrimitive(true)))
            val wrongType = JsonObject(valid + ("executionId" to JsonPrimitive(7)))

            assertIs<ActionInputDecodeResult.Failure>(registered.inputCodec.decode(unknown))
            assertIs<ActionInputDecodeResult.Failure>(registered.inputCodec.decode(wrongType))
        }

        val byId = catalog.actions.associateBy { it.descriptor.id }
        assertIs<ActionInputDecodeResult.Failure>(
            byId.getValue("page.navigate").inputCodec.decode(
                buildJsonObject {
                    put("executionId", "preview-1")
                    put("route", 7)
                },
            ),
        )
        listOf("form.preview_patch", "form.apply_patch").forEach { actionId ->
            assertIs<ActionInputDecodeResult.Failure>(
                byId.getValue(actionId).inputCodec.decode(
                    buildJsonObject {
                        put("executionId", "preview-1")
                        put("patch", "not-an-object")
                    },
                ),
            )
        }
    }

    @Test
    fun `七个动作预览都不修改页面或假远端`() = runTest {
        val screen = DemoScreenModel()
        val gateway = FakeHuitaiGateway()
        val catalog = DemoActionCatalog(screen, gateway)
        val before = screen.state.value

        catalog.actions.forEach { registered ->
            val result = registered.invokePreview(
                validInputs().getValue(registered.descriptor.id),
                context(before),
            )
            assertIs<ActionInvocationResult.Previewed>(result)
        }

        assertEquals(before, screen.state.value)
        assertEquals(0, gateway.draftWriteCount)
        assertEquals(0, gateway.submissionWriteCount)
        assertEquals(0, gateway.draftQueryCount)
        assertEquals(0, gateway.submissionQueryCount)
    }

    @Test
    fun `页面模型从同一不可变状态生成七字段上下文`() {
        val state = DemoFormState(revision = 9)
        val screen = DemoScreenModel(state)

        val snapshot = screen.pageContext()

        assertEquals(state.revision, snapshot.revision)
        assertEquals(state.route, snapshot.route)
        assertEquals(7, snapshot.fields.size)
        assertEquals(
            listOf("资料名称", "资料类型", "联系人", "金额", "日期", "状态", "详细说明"),
            snapshot.fields.map { it.label },
        )
    }

    @Test
    fun `保存草稿在确认后页面版本变化时拒绝且不调用远端`() = runTest {
        val screen = DemoScreenModel()
        val gateway = FakeHuitaiGateway()
        val registered = DemoActionCatalog(screen, gateway).actions
            .single { it.descriptor.id == "demo.save_draft" }
        val approvedContext = context(screen.state.value)
        screen.dispatch(DemoFormEvent.EditField(DemoFormState.FIELD_NAME, "确认后编辑"))

        val invocation = registered.invokeExecute(
            buildJsonObject { put("executionId", "stale-draft") },
            approvedContext,
        )

        val failure = assertIs<ActionResult.Failure>(
            assertIs<ActionInvocationResult.Executed>(invocation).result,
        )
        assertEquals(ActionErrorCode.CONTEXT_STALE, failure.error.code)
        assertEquals(0, gateway.draftRequestCount)
        assertEquals(0, gateway.draftWriteCount)
    }

    @Test
    fun `提交在审批后页面版本变化时拒绝且不调用远端`() = runTest {
        val screen = DemoScreenModel()
        val gateway = FakeHuitaiGateway()
        val registered = DemoActionCatalog(screen, gateway).actions
            .single { it.descriptor.id == "demo.submit" }
        val approvedContext = context(screen.state.value)
        screen.dispatch(DemoFormEvent.EditField(DemoFormState.FIELD_STATUS, "审批后编辑"))

        val invocation = registered.invokeExecute(
            buildJsonObject { put("executionId", "stale-submit") },
            approvedContext,
        )

        val failure = assertIs<ActionResult.Failure>(
            assertIs<ActionInvocationResult.Executed>(invocation).result,
        )
        assertEquals(ActionErrorCode.CONTEXT_STALE, failure.error.code)
        assertEquals(0, gateway.submissionRequestCount)
        assertEquals(0, gateway.submissionWriteCount)
    }

    @Test
    fun `页面导航上下文过期时拒绝且不派发导航事件`() = runTest {
        val screen = DemoScreenModel()
        val registered = DemoActionCatalog(screen, FakeHuitaiGateway()).actions
            .single { it.descriptor.id == "page.navigate" }
        val staleContext = context(screen.state.value)
        screen.dispatch(DemoFormEvent.EditField(DemoFormState.FIELD_STATUS, "用户编辑"))

        val invocation = registered.invokeExecute(
            buildJsonObject {
                put("executionId", "stale-navigation")
                put("route", "/demo/stale")
            },
            staleContext,
        )

        val failure = assertIs<ActionResult.Failure>(
            assertIs<ActionInvocationResult.Executed>(invocation).result,
        )
        assertEquals(ActionErrorCode.CONTEXT_STALE, failure.error.code)
        assertEquals(DemoFormState.DEFAULT_ROUTE, screen.state.value.route)
        assertEquals(2, screen.state.value.revision)
    }

    @Test
    fun `表单补丁预览拒绝其他页面的补丁`() = runTest {
        val screen = DemoScreenModel()
        val registered = DemoActionCatalog(screen, FakeHuitaiGateway()).actions
            .single { it.descriptor.id == "form.preview_patch" }
        val wrongPagePatch = FormPatch(
            pageId = "other.page",
            baseRevision = screen.state.value.revision,
            changes = listOf(
                FieldChange(
                    fieldId = DemoFormState.FIELD_NAME,
                    previousValue = JsonPrimitive(screen.state.value.values.name),
                    newValue = JsonPrimitive("其他页面值"),
                    reason = "错误页面",
                    confidence = 1.0,
                ),
            ),
        )

        val invocation = registered.invokeExecute(
            buildJsonObject {
                put("executionId", "wrong-page-preview")
                put("patch", Json.parseToJsonElement(Json.encodeToString(wrongPagePatch)).jsonObject)
            },
            context(screen.state.value),
        )

        val failure = assertIs<ActionResult.Failure>(
            assertIs<ActionInvocationResult.Executed>(invocation).result,
        )
        assertEquals(ActionErrorCode.VALIDATION_FAILED, failure.error.code)
    }

    @Test
    fun `应用补丁在context页面错误时拒绝且状态零变更`() = runTest {
        val initial = DemoFormState()
        val screen = DemoScreenModel(initial)
        val registered = DemoActionCatalog(screen, FakeHuitaiGateway()).actions
            .single { it.descriptor.id == "form.apply_patch" }
        val patch = singleFieldPatch(initial, DemoFormState.FIELD_NAME, "不应写入")

        val invocation = registered.invokeExecute(
            applyPatchInput("wrong-context-page", patch),
            context(initial).copy(pageId = "other.page"),
        )

        val failure = assertIs<ActionResult.Failure>(
            assertIs<ActionInvocationResult.Executed>(invocation).result,
        )
        assertEquals(ActionErrorCode.CONTEXT_STALE, failure.error.code)
        assertEquals(initial, screen.state.value)
    }

    @Test
    fun `应用补丁在context版本与patch和页面不一致时零变更`() = runTest {
        val initial = DemoFormState()
        val screen = DemoScreenModel(initial)
        val registered = DemoActionCatalog(screen, FakeHuitaiGateway()).actions
            .single { it.descriptor.id == "form.apply_patch" }
        val patch = singleFieldPatch(initial, DemoFormState.FIELD_STATUS, "不应写入")

        val invocation = registered.invokeExecute(
            applyPatchInput("wrong-context-revision", patch),
            context(initial).copy(contextRevision = initial.revision + 1),
        )

        val failure = assertIs<ActionResult.Failure>(
            assertIs<ActionInvocationResult.Executed>(invocation).result,
        )
        assertEquals(ActionErrorCode.CONTEXT_STALE, failure.error.code)
        assertEquals(initial, screen.state.value)
    }

    @Test
    fun `应用补丁在context与patch页面版本一致时成功`() = runTest {
        val initial = DemoFormState()
        val screen = DemoScreenModel(initial)
        val registered = DemoActionCatalog(screen, FakeHuitaiGateway()).actions
            .single { it.descriptor.id == "form.apply_patch" }
        val patch = singleFieldPatch(initial, DemoFormState.FIELD_NAME, "正常写入")

        val invocation = registered.invokeExecute(
            applyPatchInput("valid-context-patch", patch),
            context(initial),
        )

        assertIs<ActionResult.Success<*>>(
            assertIs<ActionInvocationResult.Executed>(invocation).result,
        )
        assertEquals("正常写入", screen.state.value.values.name)
        assertEquals(initial.revision + 1, screen.state.value.revision)
    }

    @Test
    fun `agent preview patch atomically installs a suggestion without changing submitted values`() = runTest {
        val screen = DemoScreenModel()
        val before = screen.state.value
        val patch = singleFieldPatch(before, DemoFormState.FIELD_CONTACT, "Agent suggestion")
        val registered = DemoActionCatalog(screen, FakeHuitaiGateway()).actions
            .single { it.descriptor.id == "form.preview_patch" }

        val invocation = registered.invokeExecute(
            buildJsonObject {
                put("executionId", "agent-preview-installs")
                put("patch", Json.parseToJsonElement(Json.encodeToString(patch)).jsonObject)
            },
            context(before),
        )

        assertIs<ActionResult.Success<*>>(assertIs<ActionInvocationResult.Executed>(invocation).result)
        assertEquals(before.values, screen.state.value.values)
        assertEquals(before.revision, screen.state.value.revision)
        assertEquals(patch, screen.state.value.suggestionPatch)
    }

    private fun validInputs(): Map<String, JsonObject> {
        val state = DemoFormState()
        val patch = FormPatch(
            pageId = DemoFormState.PAGE_ID,
            baseRevision = state.revision,
            changes = listOf(
                FieldChange(
                    fieldId = DemoFormState.FIELD_NAME,
                    previousValue = JsonPrimitive(state.values.name),
                    newValue = JsonPrimitive("更新名称"),
                    reason = "演示变更",
                    confidence = 0.8,
                ),
            ),
        )
        val patchObject = Json.parseToJsonElement(Json.encodeToString(patch)).jsonObject
        val executionOnly = buildJsonObject { put("executionId", "preview-1") }
        return mapOf(
            "page.navigate" to buildJsonObject {
                put("executionId", "preview-1")
                put("route", "/demo/next")
            },
            "page.read_context" to executionOnly,
            "form.read_state" to executionOnly,
            "form.preview_patch" to buildJsonObject {
                put("executionId", "preview-1")
                put("patch", patchObject)
            },
            "form.apply_patch" to buildJsonObject {
                put("executionId", "preview-1")
                put("patch", patchObject)
            },
            "demo.save_draft" to executionOnly,
            "demo.submit" to executionOnly,
        )
    }

    private fun singleFieldPatch(
        state: DemoFormState,
        fieldId: String,
        value: String,
    ): FormPatch = FormPatch(
        pageId = DemoFormState.PAGE_ID,
        baseRevision = state.revision,
        changes = listOf(
            FieldChange(
                fieldId = fieldId,
                previousValue = JsonPrimitive(state.values.valueOf(fieldId)),
                newValue = JsonPrimitive(value),
                reason = "动作上下文验证",
                confidence = 1.0,
            ),
        ),
    )

    private fun applyPatchInput(executionId: String, patch: FormPatch): JsonObject = buildJsonObject {
        put("executionId", executionId)
        put("patch", Json.parseToJsonElement(Json.encodeToString(patch)).jsonObject)
    }

    private fun context(state: DemoFormState): ActionContext = ActionContext(
        identityScope = ActionIdentityScope(
            desktopInstanceId = "desktop-1",
            desktopSessionId = "session-1",
            authSessionId = "auth-1",
            identityEpoch = 1,
            userId = "user-1",
            tenantId = "tenant-1",
            platformId = "platform-1",
        ),
        pageId = DemoFormState.PAGE_ID,
        contextRevision = state.revision,
        permissions = setOf("demo.write", "demo.submit"),
    )
}
