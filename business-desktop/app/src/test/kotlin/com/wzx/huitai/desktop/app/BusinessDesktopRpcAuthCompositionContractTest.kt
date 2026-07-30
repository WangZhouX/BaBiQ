package com.wzx.huitai.desktop.app

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class BusinessDesktopRpcAuthCompositionContractTest {
    @Test
    fun production_login_is_composed_from_server_owned_auth_rpc_client() {
        val source = java.io.File("src/main/kotlin/com/wzx/huitai/desktop/app/BusinessDesktopCompositionRoot.kt").readText()
        assertTrue(source.contains("BusinessAuthRpcClient(rpc)"))
        assertTrue(source.contains("BusinessRpcAuthenticationOperations"))
        assertTrue(source.contains("BusinessAuthenticationLifecycle(rpcAuthentication, lifecycle.state, scope)"))
        assertTrue(source.contains("onAuthStateChanged = rpcAuthentication::reconcileAuthStateChanged"))
        assertTrue(source.contains("onAuthenticationExpiredState = {"))
        assertTrue(source.contains("desktopCoordinator?.onAuthenticationExpired()"))
        assertTrue(source.contains("onMembershipExpiredState = {"))
        assertTrue(source.contains("desktopCoordinator?.onMembershipExpired()"))
        assertTrue(source.contains("onRecovering = {"))
        assertTrue(source.contains("desktopCoordinator?.clearWorkspace()"))
        assertFalse(source.contains("onSignedOutRequested"))
        assertTrue(!source.contains("ApplicationIdentityClient"))
        assertTrue(!source.contains("ApplicationCatalogClient"))
        assertTrue(!source.contains("ApplicationContextClient"))
        assertTrue(!source.contains("BusinessOaConfiguration"))
        assertTrue(!source.contains("application/identity/"))
        assertTrue(!source.contains("application/catalog/"))
        assertTrue(!source.contains("application/context/"))
    }

    @Test
    fun production_confirms_auth_protocol_then_cleans_legacy_aliases_before_login_initialization() {
        val source = java.io.File("src/main/kotlin/com/wzx/huitai/desktop/app/BusinessDesktopCompositionRoot.kt").readText()
        val protocolProbe = source.indexOf("rpcAuthentication.prepareStartup()")
        val cleanup = source.indexOf("val cleanup = legacyCredentialCleanup")
        val loginInitialization = source.indexOf("loginController.initialize()")
        val lifecycleStart = source.indexOf("authenticationLifecycle.start()")

        assertTrue(protocolProbe >= 0, "production composition must invoke the startup auth protocol probe")
        assertTrue(cleanup > protocolProbe, "legacy cleanup must run only after the auth protocol responds")
        assertTrue(loginInitialization > cleanup, "legacy cleanup must precede remembered-account initialization")
        assertTrue(lifecycleStart > loginInitialization, "authentication lifecycle must start after cleanup and login initialization")
    }
}
