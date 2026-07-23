package com.wzx.huitai.desktop.ui.login

import java.awt.Desktop
import java.net.URI

fun interface BusinessDesktopBrowser {
    fun browse(uri: URI)
}

object SystemBusinessDesktopBrowser : BusinessDesktopBrowser {
    override fun browse(uri: URI) {
        check(Desktop.isDesktopSupported()) { "Desktop integration is unavailable" }
        val desktop = Desktop.getDesktop()
        check(desktop.isSupported(Desktop.Action.BROWSE)) { "Desktop browse is unavailable" }
        desktop.browse(uri)
    }
}

enum class BusinessExternalLinkErrorCode {
    AGREEMENT_OPEN_FAILED,
}

sealed interface BusinessExternalLinkOpenResult {
    data class Opened(val uri: URI) : BusinessExternalLinkOpenResult

    data class Failed(
        val code: BusinessExternalLinkErrorCode = BusinessExternalLinkErrorCode.AGREEMENT_OPEN_FAILED,
    ) : BusinessExternalLinkOpenResult
}

class BusinessExternalLinkOpener(
    serviceAgreementUrl: String,
    privacyPolicyUrl: String,
    private val browser: BusinessDesktopBrowser = SystemBusinessDesktopBrowser,
) {
    private val serviceAgreementUri = validatedHttpsUri(serviceAgreementUrl)
    private val privacyPolicyUri = validatedHttpsUri(privacyPolicyUrl)

    fun openServiceAgreement(): BusinessExternalLinkOpenResult = open(serviceAgreementUri)

    fun openPrivacyPolicy(): BusinessExternalLinkOpenResult = open(privacyPolicyUri)

    private fun open(uri: URI): BusinessExternalLinkOpenResult = try {
        browser.browse(uri)
        BusinessExternalLinkOpenResult.Opened(uri)
    } catch (_: Exception) {
        BusinessExternalLinkOpenResult.Failed()
    }

    private fun validatedHttpsUri(raw: String): URI {
        require(raw == raw.trim() && raw.isNotEmpty()) { "Agreement URL must not be blank" }
        val uri = URI(raw)
        require(!uri.isOpaque) { "Agreement URL must be hierarchical" }
        require(uri.scheme.equals("https", ignoreCase = true)) { "Agreement URL must use HTTPS" }
        require(!uri.host.isNullOrBlank()) { "Agreement URL must have a host" }
        require(uri.userInfo == null) { "Agreement URL must not contain user-info" }
        require(uri.query == null) { "Agreement URL must not contain a query" }
        require(uri.fragment == null) { "Agreement URL must not contain a fragment" }
        return uri
    }
}
