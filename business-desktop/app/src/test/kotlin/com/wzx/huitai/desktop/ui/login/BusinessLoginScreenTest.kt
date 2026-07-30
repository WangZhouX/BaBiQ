package com.wzx.huitai.desktop.ui.login

import androidx.compose.foundation.layout.requiredSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.assertWidthIsEqualTo
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.requestFocus
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.wzx.huitai.desktop.auth.BusinessLoginErrorCode
import com.wzx.huitai.desktop.auth.BusinessLoginMessage
import com.wzx.huitai.desktop.auth.BusinessLoginState
import com.wzx.huitai.desktop.auth.BusinessSliderState
import com.wzx.huitai.desktop.auth.BusinessTenantCandidateState
import com.wzx.huitai.desktop.ui.theme.HuitaiBusinessTheme
import com.wzx.huitai.desktop.auth.BusinessTenantCandidate
import java.net.URI
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.test.assertFailsWith
import org.junit.Rule
import org.junit.Test

class BusinessLoginScreenTest {
    @get:Rule
    val rule = createComposeRule()

    @Test
    fun `wide login uses 68 percent artwork while narrow login hides it`() {
        rule.setContent {
            CompositionLocalProvider(LocalDensity provides Density(1f)) {
                HuitaiBusinessTheme {
                    BusinessLoginScreen(
                        state = completeState(),
                        serviceAgreementUrl = SERVICE_URL,
                        privacyPolicyUrl = PRIVACY_URL,
                        modifier = Modifier.requiredSize(1_500.dp, 900.dp),
                    )
                }
            }
        }

        rule.onNodeWithTag(BusinessLoginTags.ILLUSTRATION).assertWidthIsEqualTo(1_020.dp)
        rule.onNodeWithTag(BusinessLoginTags.FORM).assertWidthIsEqualTo(480.dp)
        rule.onNodeWithText("粤ICP备2024355224号-1").assertExists()

        rule.setContent {
            CompositionLocalProvider(LocalDensity provides Density(1f)) {
                HuitaiBusinessTheme {
                    BusinessLoginScreen(
                        state = completeState(),
                        serviceAgreementUrl = SERVICE_URL,
                        privacyPolicyUrl = PRIVACY_URL,
                        modifier = Modifier.requiredSize(800.dp, 900.dp),
                    )
                }
            }
        }

        rule.onNodeWithTag(BusinessLoginTags.ILLUSTRATION).assertDoesNotExist()
        rule.onNodeWithTag(BusinessLoginTags.FORM).assertWidthIsEqualTo(800.dp)

        rule.setContent {
            CompositionLocalProvider(LocalDensity provides Density(1f)) {
                HuitaiBusinessTheme {
                    BusinessLoginScreen(
                        state = completeState(),
                        serviceAgreementUrl = SERVICE_URL,
                        privacyPolicyUrl = PRIVACY_URL,
                        modifier = Modifier.requiredSize(1_100.dp, 768.dp),
                    )
                }
            }
        }
        rule.onNodeWithTag(BusinessLoginTags.ILLUSTRATION).assertDoesNotExist()
    }

    @Test
    fun `login exposes required semantics fixed copy and no unavailable entry points`() {
        rule.setContent {
            HuitaiBusinessTheme {
                BusinessLoginScreen(
                    state = completeState(
                        error = BusinessLoginMessage(BusinessLoginErrorCode.INVALID_CREDENTIALS),
                    ),
                    serviceAgreementUrl = SERVICE_URL,
                    privacyPolicyUrl = PRIVACY_URL,
                    modifier = Modifier.requiredSize(900.dp, 700.dp),
                )
            }
        }

        listOf(
            BusinessLoginTags.ROOT,
            BusinessLoginTags.ACCOUNT,
            BusinessLoginTags.PASSWORD,
            BusinessLoginTags.REMEMBER,
            BusinessLoginTags.AGREEMENT,
            BusinessLoginTags.SLIDER,
            BusinessLoginTags.SUBMIT,
            BusinessLoginTags.ERROR,
        ).forEach { tag -> rule.onNodeWithTag(tag).assertExists() }
        rule.onNodeWithTag(BusinessLoginTags.REMEMBER).assertIsOn()
        rule.onNodeWithTag(BusinessLoginTags.ERROR).assertTextContains("账号或密码错误")
        rule.onNodeWithText("欢迎登录").assertExists()
        rule.onNodeWithText("翔鸟律智-法律智能平台").assertExists()
        listOf("短信登录", "注册", "忘记密码", "微信登录", "律所入驻").forEach { copy ->
            rule.onNodeWithText(copy, substring = true).assertDoesNotExist()
        }
    }

    @Test
    fun `password visibility is local and Enter follows the same submit path as click`() {
        val submits = mutableListOf<String>()
        rule.setContent {
            HuitaiBusinessTheme {
                BusinessLoginScreen(
                    state = completeState(),
                    serviceAgreementUrl = SERVICE_URL,
                    privacyPolicyUrl = PRIVACY_URL,
                    onSubmit = { submits += "submit" },
                    modifier = Modifier.requiredSize(900.dp, 700.dp),
                )
            }
        }

        assertTrue(
            rule.onNodeWithTag(BusinessLoginTags.PASSWORD)
                .fetchSemanticsNode().config.contains(SemanticsProperties.Password),
        )
        assertEquals(
            "密码已隐藏",
            rule.onNodeWithTag(BusinessLoginTags.PASSWORD)
                .fetchSemanticsNode().config[SemanticsProperties.StateDescription],
        )
        rule.onNodeWithTag(BusinessLoginTags.PASSWORD_VISIBILITY).performClick()
        assertEquals(
            "密码已显示",
            rule.onNodeWithTag(BusinessLoginTags.PASSWORD)
                .fetchSemanticsNode().config[SemanticsProperties.StateDescription],
        )

        rule.onNodeWithTag(BusinessLoginTags.SUBMIT).performClick()
        rule.onNodeWithTag(BusinessLoginTags.PASSWORD).requestFocus().performKeyInput {
            pressKey(Key.Enter)
        }

        rule.runOnIdle { assertEquals(listOf("submit", "submit"), submits) }
    }

    @Test
    fun `incomplete form disables submit and requested slider is the only completion path`() {
        val state = mutableStateOf(BusinessLoginState())
        var submits = 0
        var completions = 0
        rule.setContent {
            HuitaiBusinessTheme {
                BusinessLoginScreen(
                    state = state.value,
                    serviceAgreementUrl = SERVICE_URL,
                    privacyPolicyUrl = PRIVACY_URL,
                    onAccountChange = { state.value = state.value.copy(account = it) },
                    onPasswordChange = { state.value = state.value.copy(password = it) },
                    onAgreementChange = { state.value = state.value.copy(agreementAccepted = it) },
                    onSubmit = {
                        submits += 1
                        state.value = state.value.copy(slider = BusinessSliderState.REQUESTED)
                    },
                    onSliderCompleted = {
                        completions += 1
                        state.value = state.value.copy(slider = BusinessSliderState.IDLE)
                    },
                    modifier = Modifier.requiredSize(900.dp, 700.dp),
                )
            }
        }

        rule.onNodeWithTag(BusinessLoginTags.SUBMIT).assertIsNotEnabled()
        rule.onNodeWithTag(BusinessLoginTags.ACCOUNT).performTextReplacement("13800008888")
        rule.onNodeWithTag(BusinessLoginTags.PASSWORD).performTextReplacement("Password1")
        rule.onNodeWithTag(BusinessLoginTags.AGREEMENT).performClick()
        rule.onNodeWithTag(BusinessLoginTags.SUBMIT).assertIsEnabled().performClick()
        rule.runOnIdle {
            assertEquals(1, submits)
            assertEquals(0, completions)
        }

        rule.onNodeWithTag(BusinessLoginTags.SUBMIT).assertIsNotEnabled()
        rule.onNodeWithTag(BusinessLoginTags.SLIDER_CONTROL)
            .performSemanticsAction(SemanticsActions.SetProgress) { setProgress ->
                setProgress(0.5f)
            }
        rule.runOnIdle { assertEquals(0, completions) }
        rule.onNodeWithTag(BusinessLoginTags.SLIDER_CONTROL)
            .performSemanticsAction(SemanticsActions.SetProgress) { setProgress ->
                setProgress(1f)
            }
        rule.runOnIdle { assertEquals(1, completions) }
    }

    @Test
    fun `multi tenant dialog requires explicit enabled selection and masks unnamed tenant`() {
        val enabled = tenant("tenant-enabled", "翔鸟律所", status = 0)
        val pending = tenant("tenant-secret-5678", null, status = 1)
        val selected = mutableListOf<BusinessTenantCandidate>()
        var cancelled = false
        rule.setContent {
            HuitaiBusinessTheme {
                BusinessLoginScreen(
                    state = completeState(
                        tenantCandidates = listOf(
                            BusinessTenantCandidateState(enabled, enabled = true),
                            BusinessTenantCandidateState(pending, enabled = false),
                        ),
                    ),
                    serviceAgreementUrl = SERVICE_URL,
                    privacyPolicyUrl = PRIVACY_URL,
                    onTenantSelected = { selected += it },
                    onTenantSelectionCancelled = { cancelled = true },
                    modifier = Modifier.requiredSize(900.dp, 700.dp),
                )
            }
        }

        rule.onNodeWithTag(BusinessLoginTags.TENANT_DIALOG).assertExists()
        rule.onNodeWithTag(BusinessLoginTags.tenantOption(0)).assertIsEnabled()
        rule.onNodeWithTag(BusinessLoginTags.tenantOption(1)).assertIsNotEnabled()
        rule.onNodeWithText("租户 ****5678").assertExists()
        rule.onNodeWithText(pending.candidateId).assertDoesNotExist()
        rule.onNodeWithText("入驻中，暂不可选择").assertExists()
        rule.onNodeWithTag(BusinessLoginTags.tenantOption(1)).performClick()
        rule.runOnIdle { assertTrue(selected.isEmpty()) }
        rule.onNodeWithTag(BusinessLoginTags.tenantOption(0)).performClick()
        rule.runOnIdle { assertEquals(listOf(enabled), selected) }
        rule.onNodeWithTag(BusinessLoginTags.TENANT_CANCEL).performClick()
        rule.runOnIdle { assertTrue(cancelled) }
    }

    @Test
    fun `tenant identifiers never enter merged or unmerged semantics`() {
        val tenantId = "tenant-sensitive-2026"
        val candidate = tenant(tenantId, null, status = 0)
        rule.setContent {
            HuitaiBusinessTheme {
                BusinessLoginScreen(
                    state = completeState(
                        tenantCandidates = listOf(
                            BusinessTenantCandidateState(candidate, enabled = true),
                        ),
                    ),
                    serviceAgreementUrl = SERVICE_URL,
                    privacyPolicyUrl = PRIVACY_URL,
                    modifier = Modifier.requiredSize(900.dp, 700.dp),
                )
            }
        }

        assertSemanticsDoesNotContain(tenantId, useUnmergedTree = false)
        assertSemanticsDoesNotContain(tenantId, useUnmergedTree = true)
    }

    @Test
    fun `short unnamed tenant identifier is never rendered in full`() {
        val tenantId = "A9"
        val candidate = tenant(tenantId, null, status = 0)
        rule.setContent {
            HuitaiBusinessTheme {
                BusinessLoginScreen(
                    state = completeState(
                        tenantCandidates = listOf(
                            BusinessTenantCandidateState(candidate, enabled = true),
                        ),
                    ),
                    serviceAgreementUrl = SERVICE_URL,
                    privacyPolicyUrl = PRIVACY_URL,
                    modifier = Modifier.requiredSize(900.dp, 700.dp),
                )
            }
        }

        rule.onNodeWithText("租户 ****").assertExists()
        rule.onNodeWithText(tenantId, substring = true).assertDoesNotExist()
        assertSemanticsDoesNotContain(tenantId, useUnmergedTree = false)
        assertSemanticsDoesNotContain(tenantId, useUnmergedTree = true)
    }

    @Test
    fun `external opener accepts only configured HTTPS agreement links`() {
        val browsed = mutableListOf<URI>()
        val opener = BusinessExternalLinkOpener(
            serviceAgreementUrl = SERVICE_URL,
            privacyPolicyUrl = PRIVACY_URL,
            browser = BusinessDesktopBrowser { browsed += it },
        )

        assertIs<BusinessExternalLinkOpenResult.Opened>(opener.openServiceAgreement())
        assertIs<BusinessExternalLinkOpenResult.Opened>(opener.openPrivacyPolicy())
        assertEquals(listOf(URI(SERVICE_URL), URI(PRIVACY_URL)), browsed)
        assertFailsWith<IllegalArgumentException> {
            BusinessExternalLinkOpener(
                serviceAgreementUrl = "http://huitaikeji.cn/agreement.html",
                privacyPolicyUrl = PRIVACY_URL,
                browser = BusinessDesktopBrowser {},
            )
        }
        assertFailsWith<IllegalArgumentException> {
            BusinessExternalLinkOpener(
                serviceAgreementUrl = "$SERVICE_URL?redirect=https://attacker.example",
                privacyPolicyUrl = PRIVACY_URL,
                browser = BusinessDesktopBrowser {},
            )
        }
    }

    @Test
    fun `browse failure writes stable error and leaves login usable`() {
        val opener = BusinessExternalLinkOpener(
            serviceAgreementUrl = SERVICE_URL,
            privacyPolicyUrl = PRIVACY_URL,
            browser = BusinessDesktopBrowser { throw IllegalStateException("desktop unavailable") },
        )
        var submits = 0
        rule.setContent {
            HuitaiBusinessTheme {
                BusinessLoginScreen(
                    state = completeState(),
                    serviceAgreementUrl = SERVICE_URL,
                    privacyPolicyUrl = PRIVACY_URL,
                    onOpenServiceAgreement = opener::openServiceAgreement,
                    onOpenPrivacyPolicy = opener::openPrivacyPolicy,
                    onSubmit = { submits += 1 },
                    modifier = Modifier.requiredSize(900.dp, 700.dp),
                )
            }
        }

        rule.onNodeWithTag(BusinessLoginTags.SERVICE_AGREEMENT).performClick()
        rule.onNodeWithTag(BusinessLoginTags.ERROR).assertTextContains("AGREEMENT_OPEN_FAILED")
        rule.onNodeWithTag(BusinessLoginTags.SUBMIT).performClick()
        rule.runOnIdle { assertEquals(1, submits) }
    }

    private fun completeState(
        error: BusinessLoginMessage? = null,
        tenantCandidates: List<BusinessTenantCandidateState> = emptyList(),
    ) = BusinessLoginState(
        account = "13800008888",
        password = "Password1",
        remember = true,
        agreementAccepted = true,
        tenantCandidates = tenantCandidates,
        error = error,
    )

    private fun tenant(
        tenantId: String,
        tenantName: String?,
        status: Int,
    ) = BusinessTenantCandidate(
        candidateId = tenantId,
        platformId = 2,
        name = tenantName,
        tenantEnterStatus = status,
    )

    private fun assertSemanticsDoesNotContain(
        sensitiveValue: String,
        useUnmergedTree: Boolean,
    ) {
        val exposed = rule.onAllNodes(
            matcher = SemanticsMatcher("all nodes") { true },
            useUnmergedTree = useUnmergedTree,
        ).fetchSemanticsNodes().any { node ->
            node.config.toString().contains(sensitiveValue)
        }
        assertFalse(exposed, "tenant identifier leaked through Compose semantics")
    }

    private companion object {
        const val SERVICE_URL = "https://huitaikeji.cn/agreement.html"
        const val PRIVACY_URL = "https://huitaikeji.cn/privacy.html"
    }
}
