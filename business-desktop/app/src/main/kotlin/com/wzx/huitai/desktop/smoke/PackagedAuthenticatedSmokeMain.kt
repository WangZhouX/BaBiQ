package com.wzx.huitai.desktop.smoke

import com.wzx.huitai.agent.business.workbench.BusinessWorkbenchSectionStatus
import com.wzx.huitai.desktop.app.BusinessDesktopCompositionRoot
import com.wzx.huitai.desktop.app.BusinessDesktopProductionConfiguration
import com.wzx.huitai.desktop.app.ProductionBusinessDesktopCompositionFactory
import com.wzx.huitai.desktop.app.ProductionUiComponents
import com.wzx.huitai.desktop.auth.BusinessAccessGateState
import com.wzx.huitai.desktop.auth.BusinessSliderState
import com.wzx.huitai.desktop.workbench.BusinessWorkbenchLoadState
import java.nio.file.Path
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

/**
 * Diagnostic-only entry point invoked directly with the extracted package runtime and classpath.
 * The normal desktop launcher never calls this class and no production UI auto-login switch exists.
 */
fun main(args: Array<String>) = runBlocking {
    require(args.size == 3) {
        "usage: <report-path> <bundled-backend-jar> <isolated-home>"
    }
    val environment = System.getenv()
    val oaBaseUrl = environment.required("HUITAI_OA_BASE_URL")
    val account = environment.required("HUITAI_DESKTOP_AUTH_SMOKE_ACCOUNT")
    val password = environment.required("HUITAI_DESKTOP_AUTH_SMOKE_PASSWORD")
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    var root: BusinessDesktopCompositionRoot? = null
    try {
        root = BusinessDesktopCompositionRoot.start(
            ProductionBusinessDesktopCompositionFactory(
                configuration = BusinessDesktopProductionConfiguration(
                    home = Path.of(args[2]),
                    backendJar = Path.of(args[1]),
                ),
                parentScope = scope,
            ),
        )
        val components = requireNotNull(root.runtimeView?.production) {
            "authenticated packaged smoke requires production components"
        }
        val evidence = PackagedAuthenticatedSmokeWorkflow(
            oaBaseUrl = oaBaseUrl,
            runtime = ProductionPackagedAuthenticatedSmokeRuntime(
                components = components,
                account = account,
                password = password,
            ),
        ).run()
        PackagedAuthenticatedSmokeProbe(Path.of(args[0])).write(evidence)
    } finally {
        root?.shutdown()
        scope.cancel()
    }
}

private class ProductionPackagedAuthenticatedSmokeRuntime(
    private val components: ProductionUiComponents,
    private val account: String,
    private val password: String,
) : PackagedAuthenticatedSmokeRuntime {
    override suspend fun authenticate() {
        components.loginController.updateAccount(account)
        components.loginController.updatePassword(password)
        components.loginController.updateAgreement(true)
        components.loginController.submit()
        check(components.loginController.state.value.slider == BusinessSliderState.REQUESTED) {
            "authenticated packaged smoke login validation failed: " +
                components.loginController.state.value.error?.code
        }
        components.loginController.completeSlider(success = true)
        check(components.loginController.state.value.error == null) {
            "authenticated packaged smoke login failed: " +
                components.loginController.state.value.error?.code
        }
    }

    override suspend fun awaitReady(): Long {
        val snapshot = withTimeout(60_000) {
            components.identityRegistry.snapshot.first { it.gate == BusinessAccessGateState.READY }
        }
        return requireNotNull(snapshot.identity).identityEpoch
    }

    override suspend fun loadWorkbench(identityEpoch: Long): PackagedWorkbenchCheck {
        withTimeout(60_000) {
            components.workbenchController.load(identityEpoch)
        }
        val state = components.workbenchController.state.value
        require(state.loadState == BusinessWorkbenchLoadState.READY) {
            "authenticated packaged smoke workbench did not reach READY"
        }
        val snapshot = requireNotNull(state.snapshot) {
            "authenticated packaged smoke workbench snapshot is missing"
        }
        val sections = buildSet {
            if (isVerifiedWorkbenchSectionStatus(snapshot.notices.status)) add("notices")
            if (isVerifiedWorkbenchSectionStatus(snapshot.shortcuts.status)) add("shortcuts")
            if (isVerifiedWorkbenchSectionStatus(snapshot.summary.status)) add("summary")
            if (isVerifiedWorkbenchSectionStatus(snapshot.profile.status)) add("profile")
            if (isVerifiedWorkbenchSectionStatus(snapshot.teams.status)) add("teams")
            if (isVerifiedWorkbenchSectionStatus(snapshot.schedule.status)) add("schedule")
        }
        val navigationAllowlisted = state.navigation.isNotEmpty() &&
            state.navigation.all { it.path in ALLOWED_NAVIGATION_PATHS }
        return PackagedWorkbenchCheck(sections, navigationAllowlisted)
    }

    override suspend fun verifyAssistantController(): Boolean {
        withTimeout(60_000) {
            components.conversationController.refreshProviders()
        }
        return true
    }

    private companion object {
        val ALLOWED_NAVIGATION_PATHS = setOf(
            "/",
            "/lawoa",
            "/bpm",
            "/approval",
            "/case",
            "/administration",
            "/management",
            "/customer",
            "/cost",
            "/consultant",
            "/lawyer-admin",
            "/tools",
            "/team",
        )
    }
}

internal fun isVerifiedWorkbenchSectionStatus(status: BusinessWorkbenchSectionStatus): Boolean =
    status == BusinessWorkbenchSectionStatus.OK || status == BusinessWorkbenchSectionStatus.EMPTY

private fun Map<String, String>.required(name: String): String =
    get(name)?.takeIf(String::isNotBlank) ?: error("$name is required")
