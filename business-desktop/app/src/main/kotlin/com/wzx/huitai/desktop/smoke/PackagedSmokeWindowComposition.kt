package com.wzx.huitai.desktop.smoke

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import com.wzx.huitai.desktop.ui.brand.BusinessBrandResources

/**
 * Runs only after the Window content has committed successfully. Placing this after the real shell
 * in the Window content ensures the report cannot be used as a pre-window startup shortcut.
 */
@Composable
fun PackagedSmokeWindowCompositionEffect(
    coordinator: PackagedSmokeCompositionCoordinator?,
    assistantInitiallyCollapsed: Boolean,
    productName: String,
    onFailure: (Throwable) -> Unit,
    onFinished: () -> Unit,
) {
    LaunchedEffect(coordinator) {
        if (coordinator == null) return@LaunchedEffect
        try {
            coordinator.onWindowCompositionCommitted(
                buildPackagedSmokeUiReadiness(
                    assistantInitiallyCollapsed = assistantInitiallyCollapsed,
                    productName = productName,
                ),
            )
        } catch (failure: Throwable) {
            onFailure(failure)
        } finally {
            onFinished()
        }
    }
}

internal fun buildPackagedSmokeUiReadiness(
    assistantInitiallyCollapsed: Boolean,
    productName: String,
    decodeLogo: () -> Unit = { BusinessBrandResources.logoImageBitmap() },
    decodeMascot: () -> Unit = { BusinessBrandResources.mascotImageBitmap() },
): PackagedSmokeUiReadiness = PackagedSmokeUiReadiness(
    windowComposed = true,
    brandLogoDecoded = runCatching(decodeLogo).isSuccess,
    mascotDecoded = runCatching(decodeMascot).isSuccess,
    topNavigationComposed = true,
    assistantInitiallyCollapsed = assistantInitiallyCollapsed,
    productName = productName,
)
