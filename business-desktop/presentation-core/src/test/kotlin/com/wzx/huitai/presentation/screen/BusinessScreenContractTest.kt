package com.wzx.huitai.presentation.screen

import com.wzx.huitai.presentation.context.PageContextSnapshot
import com.wzx.huitai.presentation.context.FieldContext
import com.wzx.huitai.presentation.context.FieldSensitivity
import com.wzx.huitai.presentation.context.PageMode
import com.wzx.huitai.presentation.context.ValidationSummary
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotSame

class BusinessScreenContractTest {
    @Test
    fun `页面以StateFlow暴露不可变状态且事件只经过纯reducer`() {
        val initial = TestState(value = "before", revision = 1)
        val reducer = ScreenReducer<TestState, TestEvent> { state, event ->
            when (event) {
                is TestEvent.Replace -> state.copy(value = event.value, revision = state.revision + 1)
            }
        }
        val screen = TestScreen(initial, reducer)

        val firstReduction = reducer.reduce(initial, TestEvent.Replace("after"))
        val secondReduction = reducer.reduce(initial, TestEvent.Replace("after"))
        screen.dispatch(TestEvent.Replace("after"))

        val exposed: StateFlow<TestState> = screen.state
        assertEquals(firstReduction, secondReduction)
        assertEquals(TestState(value = "before", revision = 1), initial)
        assertNotSame(initial, exposed.value)
        assertEquals(TestState(value = "after", revision = 2), exposed.value)
    }

    @Test
    fun `pageContext与当前不可变状态使用同一revision`() {
        val reducer = ScreenReducer<TestState, TestEvent> { state, event ->
            when (event) {
                is TestEvent.Replace -> state.copy(value = event.value, revision = state.revision + 1)
            }
        }
        val screen = TestScreen(TestState(value = "before", revision = 11), reducer)

        screen.dispatch(TestEvent.Replace("after"))

        val stateAtPublication = screen.state.value
        val context = screen.pageContext()
        assertEquals(stateAtPublication.revision, context.revision)
        assertEquals(stateAtPublication.value, context.fields.single().value?.toString()?.trim('"'))
    }

    private data class TestState(
        val value: String,
        val revision: Long,
    )

    private sealed interface TestEvent {
        data class Replace(val value: String) : TestEvent
    }

    private class TestScreen(
        initial: TestState,
        private val reducer: ScreenReducer<TestState, TestEvent>,
    ) : BusinessScreenContract<TestState, TestEvent>, AgentAwareScreen {
        private val mutableState = MutableStateFlow(initial)

        override val state: StateFlow<TestState> = mutableState

        override fun dispatch(event: TestEvent) {
            mutableState.value = reducer.reduce(mutableState.value, event)
        }

        override fun pageContext(): PageContextSnapshot {
            val current = state.value
            return PageContextSnapshot(
                snapshotId = "snapshot-${current.revision}",
                pageId = "test-page",
                pageTitle = "测试页面",
                route = "/test",
                revision = current.revision,
                mode = PageMode.EDIT,
                fields = listOf(
                    FieldContext(
                        id = "value",
                        label = "值",
                        type = "string",
                        value = JsonPrimitive(current.value),
                        editable = true,
                        required = true,
                        sensitivity = FieldSensitivity.PUBLIC,
                    ),
                ),
                validationSummary = ValidationSummary(valid = true),
            )
        }
    }
}
