package com.wzx.huitai.desktop.smoke

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.withFrameNanos
import com.wzx.huitai.desktop.ui.brand.BusinessBrandResources
import java.util.concurrent.atomic.AtomicBoolean

internal data class PackagedSmokeUiCompositionSnapshot(
    val windowComposed: Boolean,
    val shellComposed: Boolean,
    val topNavigationComposed: Boolean,
)

/** Signals emitted by the real Window, shell, and top-navigation composition paths. */
class PackagedSmokeUiCompositionSignals {
    private val windowComposed = AtomicBoolean(false)
    private val shellComposed = AtomicBoolean(false)
    private val topNavigationComposed = AtomicBoolean(false)

    fun markWindowComposed() {
        windowComposed.set(true)
    }

    fun markShellComposed() {
        shellComposed.set(true)
    }

    fun markTopNavigationComposed() {
        topNavigationComposed.set(true)
    }

    internal fun snapshot(): PackagedSmokeUiCompositionSnapshot = PackagedSmokeUiCompositionSnapshot(
        windowComposed = windowComposed.get(),
        shellComposed = shellComposed.get(),
        topNavigationComposed = topNavigationComposed.get(),
    )
}

/**
 * Runs only after the Window content has committed successfully. Placing this after the real shell
 * in the Window content ensures the report cannot be used as a pre-window startup shortcut.
 */
@Composable
fun PackagedSmokeWindowCompositionEffect(
    coordinator: PackagedSmokeCompositionCoordinator?,
    compositionSignals: PackagedSmokeUiCompositionSignals,
    assistantInitiallyCollapsed: Boolean,
    productName: String,
    onFailure: (Throwable) -> Unit,
    onFinished: () -> Unit,
) {
    LaunchedEffect(coordinator) {
        if (coordinator == null) return@LaunchedEffect
        try {
            publishPackagedSmokeAfterCommittedFrame(
                coordinator = coordinator,
                compositionSignals = compositionSignals,
                assistantInitiallyCollapsed = assistantInitiallyCollapsed,
                productName = productName,
                awaitFrame = { withFrameNanos { } },
            )
        } catch (failure: Throwable) {
            onFailure(failure)
        } finally {
            onFinished()
        }
    }
}

internal suspend fun publishPackagedSmokeAfterCommittedFrame(
    coordinator: PackagedSmokeCompositionCoordinator,
    compositionSignals: PackagedSmokeUiCompositionSignals,
    assistantInitiallyCollapsed: Boolean,
    productName: String,
    awaitFrame: suspend () -> Unit,
    decodeLogo: () -> Unit = { BusinessBrandResources.logoImageBitmap() },
    decodeMascot: () -> Unit = { BusinessBrandResources.mascotImageBitmap() },
): Boolean {
    awaitFrame()
    return coordinator.onWindowCompositionCommitted(
        buildPackagedSmokeUiReadiness(
            composition = compositionSignals.snapshot(),
            assistantInitiallyCollapsed = assistantInitiallyCollapsed,
            productName = productName,
            decodeLogo = decodeLogo,
            decodeMascot = decodeMascot,
        ),
    )
}

internal fun buildPackagedSmokeUiReadiness(
    composition: PackagedSmokeUiCompositionSnapshot,
    assistantInitiallyCollapsed: Boolean,
    productName: String,
    decodeLogo: () -> Unit = { BusinessBrandResources.logoImageBitmap() },
    decodeMascot: () -> Unit = { BusinessBrandResources.mascotImageBitmap() },
): PackagedSmokeUiReadiness = PackagedSmokeUiReadiness(
    windowComposed = composition.windowComposed,
    shellComposed = composition.shellComposed,
    brandLogoDecoded = runCatching(decodeLogo).isSuccess,
    mascotDecoded = runCatching(decodeMascot).isSuccess,
    topNavigationComposed = composition.topNavigationComposed,
    assistantInitiallyCollapsed = assistantInitiallyCollapsed,
    productName = productName,
)
