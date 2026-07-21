package com.wzx.huitai.desktop.ui.agent

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assertContentDescriptionEquals
import androidx.compose.ui.test.assertHeightIsEqualTo
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertWidthIsEqualTo
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.requestFocus
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.wzx.huitai.desktop.ui.theme.HuitaiBusinessTheme
import kotlin.math.abs
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.Rule
import org.junit.Test

class BusinessAssistantChromeTest {
    @get:Rule
    val rule = createComposeRule()

    @Test
    fun `collapsed mascot exposes a 112dp button and opens the assistant`() {
        var toggleCount = 0
        rule.setContent {
            CompositionLocalProvider(LocalDensity provides Density(1f)) {
                HuitaiBusinessTheme {
                    BusinessAssistantMascotButton(
                        expanded = false,
                        onToggle = { toggleCount += 1 },
                    )
                }
            }
        }

        val mascot = rule.onNodeWithTag(BusinessAssistantChromeTags.MASCOT)
        mascot.assertWidthIsEqualTo(112.dp)
            .assertHeightIsEqualTo(112.dp)
            .assertContentDescriptionEquals("打开小律智能助手")

        val config = mascot.fetchSemanticsNode().config
        assertEquals(Role.Button, config[SemanticsProperties.Role])
        assertEquals("小律智能助手已收回", config[SemanticsProperties.StateDescription])
        assertTrue(config.contains(SemanticsActions.OnClick))

        mascot.performClick()
        rule.runOnIdle { assertEquals(1, toggleCount) }
    }

    @Test
    fun `expanded mascot exposes collection semantics and closes the assistant`() {
        var toggleCount = 0
        rule.setContent {
            HuitaiBusinessTheme {
                BusinessAssistantMascotButton(
                    expanded = true,
                    onToggle = { toggleCount += 1 },
                )
            }
        }

        rule.onNodeWithTag(BusinessAssistantChromeTags.MASCOT)
            .assertContentDescriptionEquals("收回小律智能助手")
            .performClick()
        val config = rule.onNodeWithTag(BusinessAssistantChromeTags.MASCOT)
            .fetchSemanticsNode()
            .config
        assertEquals("小律智能助手已打开", config[SemanticsProperties.StateDescription])
        rule.runOnIdle { assertEquals(1, toggleCount) }
    }

    @Test
    fun `resize handle exposes a 12dp focusable value picker and one dp visual rail`() {
        rule.setContent {
            CompositionLocalProvider(LocalDensity provides Density(1f)) {
                HuitaiBusinessTheme {
                    Box(Modifier.requiredSize(80.dp)) {
                        BusinessAssistantResizeHandle(onResizeBy = {})
                    }
                }
            }
        }

        val handle = rule.onNodeWithTag(BusinessAssistantChromeTags.RESIZE_HANDLE)
        handle.assertWidthIsEqualTo(12.dp)
            .assertHeightIsEqualTo(80.dp)
            .assertContentDescriptionEquals("调整小律智能助手宽度")
        val config = handle.fetchSemanticsNode().config
        assertEquals(Role.ValuePicker, config[SemanticsProperties.Role])
        assertEquals("左方向键增宽，右方向键减宽", config[SemanticsProperties.StateDescription])
        assertTrue(config.contains(SemanticsActions.RequestFocus))

        handle.requestFocus().assertIsFocused()
        rule.onNodeWithTag(BusinessAssistantChromeTags.RESIZE_RAIL)
            .assertWidthIsEqualTo(1.dp)
            .assertHeightIsEqualTo(80.dp)
    }

    @Test
    fun `mouse drag reports real node deltas converted through density two`() {
        val deltas = mutableListOf<Dp>()
        rule.setContent {
            CompositionLocalProvider(LocalDensity provides Density(2f)) {
                HuitaiBusinessTheme {
                    Box(Modifier.requiredSize(80.dp)) {
                        BusinessAssistantResizeHandle(onResizeBy = { delta: Dp -> deltas += delta })
                    }
                }
            }
        }

        val handle = rule.onNodeWithTag(BusinessAssistantChromeTags.RESIZE_HANDLE)
        handle.performMouseInput {
            moveTo(center)
            press()
            moveBy(Offset(-40f, 0f))
            release()
        }
        rule.runOnIdle {
            assertApproximately(-20f, deltas.sumOf { it.value.toDouble() }.toFloat())
        }

        deltas.clear()
        handle.performMouseInput {
            moveTo(center)
            press()
            moveBy(Offset(32f, 0f))
            release()
        }
        rule.runOnIdle {
            assertApproximately(16f, deltas.sumOf { it.value.toDouble() }.toFloat())
        }
    }

    @Test
    fun `mouse drag survives callback identity replacement after recomposition`() {
        val callbackGeneration = mutableStateOf(0)
        val callbackGenerations = mutableListOf<Int>()
        val deltas = mutableListOf<Dp>()
        rule.setContent {
            CompositionLocalProvider(LocalDensity provides Density(2f)) {
                HuitaiBusinessTheme {
                    Box(Modifier.requiredSize(80.dp)) {
                        val generation = callbackGeneration.value
                        BusinessAssistantResizeHandle(
                            onResizeBy = { delta ->
                                deltas += delta
                                callbackGenerations += generation
                                if (generation == 0) {
                                    callbackGeneration.value = 1
                                }
                            },
                        )
                    }
                }
            }
        }

        val handle = rule.onNodeWithTag(BusinessAssistantChromeTags.RESIZE_HANDLE)
        handle.performMouseInput {
            moveTo(center)
            press()
            moveBy(Offset(-20f, 0f))
        }
        rule.waitForIdle()
        handle.performMouseInput {
            moveBy(Offset(-20f, 0f))
            release()
        }

        rule.runOnIdle {
            assertApproximately(-20f, deltas.sumOf { it.value.toDouble() }.toFloat())
            assertEquals(listOf(0, 1), callbackGenerations)
        }
    }

    @Test
    fun `focused resize handle supports repeatable 16dp keyboard steps`() {
        val deltas = mutableListOf<Dp>()
        val parentEvents = mutableListOf<Pair<Key, KeyEventType>>()
        rule.setContent {
            HuitaiBusinessTheme {
                Box(
                    Modifier
                        .requiredSize(80.dp)
                        .onKeyEvent { event ->
                            parentEvents += event.key to event.type
                            false
                        },
                ) {
                    BusinessAssistantResizeHandle(onResizeBy = { delta: Dp -> deltas += delta })
                }
            }
        }

        rule.onNodeWithTag(BusinessAssistantChromeTags.RESIZE_HANDLE)
            .requestFocus()
            .performKeyInput {
                keyDown(Key.DirectionLeft)
                keyUp(Key.DirectionLeft)
                keyDown(Key.DirectionLeft)
                keyUp(Key.DirectionLeft)
                keyDown(Key.DirectionRight)
                keyUp(Key.DirectionRight)
            }

        rule.runOnIdle {
            assertEquals(listOf((-16).dp, (-16).dp, 16.dp), deltas)
            assertEquals(emptyList(), parentEvents, "方向键 KeyDown 与 KeyUp 都应由分隔条消费")
        }
    }

    private fun assertApproximately(expected: Float, actual: Float) {
        assertTrue(abs(expected - actual) <= 0.25f, "expected=$expected actual=$actual")
    }
}
