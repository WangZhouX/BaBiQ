package com.wzx.huitai.desktop.ui.agent

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import java.awt.Cursor

private val ResizeHandleWidth = 12.dp
private val ResizeRailWidth = 1.dp
private val KeyboardResizeStep = 16.dp

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun BusinessAssistantResizeHandle(
    onResizeBy: (dragDeltaX: Dp) -> Unit,
    modifier: Modifier = Modifier,
) {
    val density = LocalDensity.current
    val latestOnResizeBy = rememberUpdatedState(onResizeBy)
    Box(
        modifier = modifier
            .width(ResizeHandleWidth)
            .fillMaxHeight()
            .testTag(BusinessAssistantChromeTags.RESIZE_HANDLE)
            .semantics {
                contentDescription = "调整小律智能助手宽度"
                stateDescription = "左方向键增宽，右方向键减宽"
                role = Role.ValuePicker
            }
            .pointerHoverIcon(PointerIcon(Cursor.getPredefinedCursor(Cursor.E_RESIZE_CURSOR)))
            .pointerInput(density) {
                detectDragGestures(
                    orientationLock = Orientation.Horizontal,
                    shouldAwaitTouchSlop = { false },
                    onDrag = { change, dragAmount ->
                        change.consume()
                        if (dragAmount.x != 0f) {
                            latestOnResizeBy.value(with(density) { dragAmount.x.toDp() })
                        }
                    },
                )
            }
            .onKeyEvent { event ->
                when (event.key) {
                    Key.DirectionLeft -> {
                        if (event.type == KeyEventType.KeyDown) {
                            latestOnResizeBy.value(-KeyboardResizeStep)
                        }
                        event.type == KeyEventType.KeyDown || event.type == KeyEventType.KeyUp
                    }

                    Key.DirectionRight -> {
                        if (event.type == KeyEventType.KeyDown) {
                            latestOnResizeBy.value(KeyboardResizeStep)
                        }
                        event.type == KeyEventType.KeyDown || event.type == KeyEventType.KeyUp
                    }

                    else -> false
                }
            }
            .focusable(),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .width(ResizeRailWidth)
                .fillMaxHeight()
                .background(MaterialTheme.colorScheme.outlineVariant)
                .testTag(BusinessAssistantChromeTags.RESIZE_RAIL),
        )
    }
}
