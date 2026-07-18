package com.wzx.huitai.demo.model

import com.wzx.huitai.presentation.context.AvailableAction
import com.wzx.huitai.presentation.context.FieldContext
import com.wzx.huitai.presentation.context.FieldSensitivity
import com.wzx.huitai.presentation.context.PageContextSnapshot
import com.wzx.huitai.presentation.context.PageMode
import com.wzx.huitai.presentation.context.ValidationSummary
import com.wzx.huitai.presentation.screen.AgentAwareScreen
import com.wzx.huitai.presentation.screen.BusinessScreenContract
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.JsonPrimitive

/** 单个 typed event 在页面临界区内计算出的不可变迁移结果。 */
internal data class DemoDispatchResult(
    /** 本事件进入 reducer 时的状态。 */
    val before: DemoFormState,
    /** 本事件完成 reducer 迁移后的状态。 */
    val after: DemoFormState,
) {
    /** 本事件是否实际改变页面状态。 */
    val stateChanged: Boolean
        get() = before != after
}

/**
 * 通用七字段演示页面模型。
 *
 * 用户操作与 Agent 动作都只能通过 [dispatch] 进入同一个 reducer。
 */
class DemoScreenModel(
    initialState: DemoFormState = DemoFormState(),
    private val reducer: DemoFormReducer = DemoFormReducer(),
) : BusinessScreenContract<DemoFormState, DemoFormEvent>, AgentAwareScreen {
    private val mutableState = MutableStateFlow(initialState)
    private val dispatchMonitor = Any()

    /** 当前不可变页面状态。 */
    override val state: StateFlow<DemoFormState> = mutableState.asStateFlow()

    /** 将强类型事件交给纯 reducer。 */
    override fun dispatch(event: DemoFormEvent) {
        dispatchWithResult(event)
    }

    /** 在所有页面写入共用的临界区内计算并发布一个 typed event 的精确迁移。 */
    internal fun dispatchWithResult(event: DemoFormEvent): DemoDispatchResult = synchronized(dispatchMonitor) {
        reduceAndPublish(event)
    }

    /** 仅当页面标识和 revision 仍与已确认上下文一致时，原子派发 typed event。 */
    internal fun dispatchWithExpectedContext(
        event: DemoFormEvent,
        expectedPageId: String,
        expectedRevision: Long,
    ): DemoDispatchResult? = synchronized(dispatchMonitor) {
        val current = mutableState.value
        if (expectedPageId != DemoFormState.PAGE_ID || current.revision != expectedRevision) {
            null
        } else {
            reduceAndPublish(event)
        }
    }

    /** 与所有 dispatch 共用临界区，读取将被远端动作使用的单一不可变快照。 */
    internal fun readWithRevision(): DemoFormState = synchronized(dispatchMonitor) {
        mutableState.value
    }

    /** 从同一次状态读取生成字段值与 revision 完全一致的页面快照。 */
    override fun pageContext(): PageContextSnapshot {
        val current = state.value
        return PageContextSnapshot(
            snapshotId = "demo-form-${current.revision}",
            pageId = DemoFormState.PAGE_ID,
            pageTitle = "通用资料演示",
            route = current.route,
            revision = current.revision,
            mode = PageMode.EDIT,
            fields = fields(current.values),
            availableActions = ACTIONS,
            validationSummary = validation(current),
        )
    }

    private fun fields(values: DemoFormValues): List<FieldContext> = listOf(
        field(DemoFormState.FIELD_NAME, "资料名称", values.name, required = true),
        field(DemoFormState.FIELD_TYPE, "资料类型", values.type, required = true),
        field(DemoFormState.FIELD_CONTACT, "联系人", values.contact),
        field(DemoFormState.FIELD_AMOUNT, "金额", values.amount),
        field(DemoFormState.FIELD_DATE, "日期", values.date),
        field(DemoFormState.FIELD_STATUS, "状态", values.status, required = true),
        field(DemoFormState.FIELD_DETAILS, "详细说明", values.details),
    )

    private fun field(
        id: String,
        label: String,
        value: String,
        required: Boolean = false,
    ): FieldContext = FieldContext(
        id = id,
        label = label,
        type = "string",
        value = JsonPrimitive(value),
        editable = true,
        required = required,
        sensitivity = FieldSensitivity.INTERNAL,
    )

    private fun validation(state: DemoFormState): ValidationSummary {
        val messages = buildList {
            if (state.values.name.isBlank()) add("资料名称不能为空")
            if (state.values.type.isBlank()) add("资料类型不能为空")
            if (state.values.status.isBlank()) add("状态不能为空")
        }
        return ValidationSummary(valid = messages.isEmpty(), messages = messages)
    }

    private fun reduceAndPublish(event: DemoFormEvent): DemoDispatchResult {
        val before = mutableState.value
        val after = reducer.reduce(before, event)
        mutableState.value = after
        return DemoDispatchResult(before, after)
    }

    private companion object {
        val ACTIONS = listOf(
            action("page.navigate", "页面导航"),
            action("page.read_context", "读取页面上下文"),
            action("form.read_state", "读取表单状态"),
            action("form.preview_patch", "预览表单补丁"),
            action("form.apply_patch", "应用表单补丁"),
            action("demo.save_draft", "保存草稿"),
            action("demo.submit", "提交资料"),
        )

        fun action(id: String, title: String): AvailableAction = AvailableAction(
            id = id,
            title = title,
            description = "通用框架演示动作",
            enabled = true,
        )
    }
}
