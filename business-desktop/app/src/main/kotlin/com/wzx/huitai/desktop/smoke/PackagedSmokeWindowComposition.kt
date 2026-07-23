package com.wzx.huitai.desktop.smoke

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.withFrameNanos
import com.wzx.huitai.desktop.ui.brand.BusinessBrandResources
import java.util.concurrent.atomic.AtomicBoolean

internal data class PackagedSmokeUiCompositionSnapshot(
    val windowComposed: Boolean,
    val loginGateComposed: Boolean,
    val shellComposed: Boolean,
)

/** Signals emitted by the real signed-out Window and login-gate composition paths. */
class PackagedSmokeUiCompositionSignals {
    private val windowComposed = AtomicBoolean(false)
    private val loginGateComposed = AtomicBoolean(false)
    private val shellComposed = AtomicBoolean(false)

    fun markWindowComposed() {
        windowComposed.set(true)
    }

    fun markLoginGateComposed() {
        loginGateComposed.set(true)
    }

    fun markShellComposed() {
        shellComposed.set(true)
    }

    internal fun snapshot(): PackagedSmokeUiCompositionSnapshot = PackagedSmokeUiCompositionSnapshot(
        windowComposed = windowComposed.get(),
        loginGateComposed = loginGateComposed.get(),
        shellComposed = shellComposed.get(),
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
    enabled: Boolean,
    productName: String,
    onFailure: (Throwable) -> Unit,
    onFinished: () -> Unit,
) {
    LaunchedEffect(coordinator, enabled) {
        if (coordinator == null || !enabled) return@LaunchedEffect
        try {
            publishPackagedSmokeAfterCommittedFrame(
                coordinator = coordinator,
                compositionSignals = compositionSignals,
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
    productName: String,
    awaitFrame: suspend () -> Unit,
    decodeLogo: () -> Unit = { BusinessBrandResources.logoImageBitmap() },
): Boolean {
    awaitFrame()
    return coordinator.onWindowCompositionCommitted(
        buildPackagedSmokeUiReadiness(
            composition = compositionSignals.snapshot(),
            productName = productName,
            decodeLogo = decodeLogo,
        ),
    )
}

internal fun buildPackagedSmokeUiReadiness(
    composition: PackagedSmokeUiCompositionSnapshot,
    productName: String,
    decodeLogo: () -> Unit = { BusinessBrandResources.logoImageBitmap() },
): PackagedSmokeUiReadiness {
    decodeLogo()
    return PackagedSmokeUiReadiness(
        windowComposed = composition.windowComposed,
        loginGateComposed = composition.loginGateComposed,
        businessShellHiddenWhileSignedOut = composition.loginGateComposed && !composition.shellComposed,
        brandLogoDecoded = true,
        productName = productName,
    )
}
