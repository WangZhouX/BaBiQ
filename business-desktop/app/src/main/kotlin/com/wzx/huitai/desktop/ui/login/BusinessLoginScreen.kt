package com.wzx.huitai.desktop.ui.login

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.wzx.huitai.desktop.auth.BusinessLoginState
import com.wzx.huitai.desktop.auth.BusinessSliderState
import com.wzx.huitai.desktop.auth.BusinessTenantCandidateState
import com.wzx.huitai.integration.oa.auth.OaTenantCandidate
import org.jetbrains.skia.Image as SkiaImage

object BusinessLoginTags {
    const val ROOT = "business-login-root"
    const val ILLUSTRATION = "business-login-illustration"
    const val FORM = "business-login-form"
    const val ACCOUNT = "business-login-account"
    const val PASSWORD = "business-login-password"
    const val PASSWORD_VISIBILITY = "business-login-password-visibility"
    const val REMEMBER = "business-login-remember"
    const val AGREEMENT = "business-login-agreement"
    const val SERVICE_AGREEMENT = "business-login-service-agreement"
    const val PRIVACY_POLICY = "business-login-privacy-policy"
    const val SLIDER = "business-login-slider"
    const val SLIDER_DIALOG = "business-login-slider-dialog"
    const val SLIDER_CONTROL = "business-login-slider-control"
    const val SLIDER_CANCEL = "business-login-slider-cancel"
    const val SUBMIT = "business-login-submit"
    const val ERROR = "business-login-error"
    const val TENANT_DIALOG = "business-login-tenant-dialog"
    const val TENANT_CANCEL = "business-login-tenant-cancel"

    fun tenantOption(index: Int): String = "business-login-tenant-option-$index"
}

@Composable
fun BusinessLoginScreen(
    state: BusinessLoginState,
    serviceAgreementUrl: String,
    privacyPolicyUrl: String,
    modifier: Modifier = Modifier,
    onAccountChange: (String) -> Unit = {},
    onPasswordChange: (String) -> Unit = {},
    onRememberChange: (Boolean) -> Unit = {},
    onAgreementChange: (Boolean) -> Unit = {},
    onSubmit: () -> Unit = {},
    onSliderCompleted: () -> Unit = {},
    onSliderDismissed: () -> Unit = {},
    onTenantSelected: (OaTenantCandidate) -> Unit = {},
    onTenantSelectionCancelled: () -> Unit = {},
    onOpenServiceAgreement: (() -> BusinessExternalLinkOpenResult)? = null,
    onOpenPrivacyPolicy: (() -> BusinessExternalLinkOpenResult)? = null,
) {
    val defaultLinkOpener = remember(serviceAgreementUrl, privacyPolicyUrl) {
        BusinessExternalLinkOpener(serviceAgreementUrl, privacyPolicyUrl)
    }
    var externalLinkFailure by remember {
        mutableStateOf<BusinessExternalLinkErrorCode?>(null)
    }
    val openServiceAgreement = onOpenServiceAgreement ?: defaultLinkOpener::openServiceAgreement
    val openPrivacyPolicy = onOpenPrivacyPolicy ?: defaultLinkOpener::openPrivacyPolicy
    val reportOpenResult: (BusinessExternalLinkOpenResult) -> Unit = { result ->
        externalLinkFailure = (result as? BusinessExternalLinkOpenResult.Failed)?.code
    }

    BoxWithConstraints(
        modifier = modifier
            .fillMaxSize()
            .background(LOGIN_BACKGROUND)
            .testTag(BusinessLoginTags.ROOT),
    ) {
        val showIllustration = maxWidth > WIDE_LAYOUT_THRESHOLD
        val illustrationWidth = minOf(maxWidth * ILLUSTRATION_FRACTION, maxWidth - MIN_FORM_WIDTH)
        Row(Modifier.fillMaxSize()) {
            if (showIllustration) {
                LoginIllustration(
                    modifier = Modifier
                        .fillMaxHeight()
                        .width(illustrationWidth)
                        .testTag(BusinessLoginTags.ILLUSTRATION),
                )
            }
            LoginForm(
                state = state,
                externalLinkFailure = externalLinkFailure,
                onAccountChange = onAccountChange,
                onPasswordChange = onPasswordChange,
                onRememberChange = onRememberChange,
                onAgreementChange = onAgreementChange,
                onSubmit = onSubmit,
                onSliderCompleted = onSliderCompleted,
                onSliderDismissed = onSliderDismissed,
                onOpenServiceAgreement = { reportOpenResult(openServiceAgreement()) },
                onOpenPrivacyPolicy = { reportOpenResult(openPrivacyPolicy()) },
                modifier = Modifier
                    .fillMaxHeight()
                    .weight(1f)
                    .testTag(BusinessLoginTags.FORM),
            )
        }
    }

    if (state.tenantCandidates.isNotEmpty()) {
        TenantSelectionDialog(
            candidates = state.tenantCandidates,
            onSelected = onTenantSelected,
            onCancelled = onTenantSelectionCancelled,
        )
    }
}

@Composable
private fun LoginIllustration(modifier: Modifier = Modifier) {
    Box(modifier) {
        Image(
            bitmap = BusinessLoginAssets.background,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            alignment = Alignment.Center,
            modifier = Modifier.fillMaxSize(),
        )
        Row(
            modifier = Modifier
                .align(Alignment.TopStart)
                .padding(24.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Image(
                bitmap = BusinessLoginAssets.logo,
                contentDescription = "翔鸟律智",
                contentScale = ContentScale.Fit,
                modifier = Modifier.size(42.dp),
            )
            Text(
                text = "翔鸟律智",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                color = Color(0xFF1F2937),
            )
        }
        Text(
            text = "粤ICP备2024355224号-1",
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(24.dp),
            color = Color(0xFF64748B),
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

@Composable
private fun LoginForm(
    state: BusinessLoginState,
    externalLinkFailure: BusinessExternalLinkErrorCode?,
    onAccountChange: (String) -> Unit,
    onPasswordChange: (String) -> Unit,
    onRememberChange: (Boolean) -> Unit,
    onAgreementChange: (Boolean) -> Unit,
    onSubmit: () -> Unit,
    onSliderCompleted: () -> Unit,
    onSliderDismissed: () -> Unit,
    onOpenServiceAgreement: () -> Unit,
    onOpenPrivacyPolicy: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var passwordVisible by remember { mutableStateOf(false) }
    val submitEnabled = state.account.isNotBlank() &&
        state.password.isNotBlank() &&
        state.agreementAccepted &&
        !state.submitting &&
        state.slider == BusinessSliderState.IDLE
    val errorText = state.error?.message
        ?: state.notice?.message
        ?: externalLinkFailure?.name
        ?: "\u00A0"

    Box(modifier = modifier.background(Color.White)) {
        Column(
            modifier = Modifier
                .align(Alignment.Center)
                .widthIn(max = FORM_CONTENT_WIDTH)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 32.dp, vertical = 28.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text(
                text = "欢迎登录",
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
                color = Color(0xFF1F2937),
            )
            Text(
                text = "翔鸟律智-法律智能平台",
                style = MaterialTheme.typography.titleMedium,
                color = Color(0xFF6B7280),
            )
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = state.account,
                onValueChange = onAccountChange,
                label = { Text("账号") },
                placeholder = { Text("请输入手机号或邮箱") },
                singleLine = true,
                enabled = !state.submitting,
                modifier = Modifier
                    .fillMaxWidth()
                    .semantics { contentDescription = "登录账号" }
                    .testTag(BusinessLoginTags.ACCOUNT),
            )
            OutlinedTextField(
                value = state.password,
                onValueChange = onPasswordChange,
                label = { Text("密码") },
                placeholder = { Text("请输入密码") },
                singleLine = true,
                enabled = !state.submitting,
                visualTransformation = if (passwordVisible) {
                    VisualTransformation.None
                } else {
                    PasswordVisualTransformation()
                },
                trailingIcon = {
                    TextButton(
                        onClick = { passwordVisible = !passwordVisible },
                        modifier = Modifier
                            .semantics {
                                contentDescription = if (passwordVisible) "隐藏密码" else "显示密码"
                            }
                            .testTag(BusinessLoginTags.PASSWORD_VISIBILITY),
                    ) {
                        Text(if (passwordVisible) "隐藏" else "显示")
                    }
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .onPreviewKeyEvent { event ->
                        if (
                            event.key == Key.Enter &&
                            event.type == KeyEventType.KeyUp &&
                            submitEnabled
                        ) {
                            onSubmit()
                            true
                        } else {
                            false
                        }
                    }
                    .semantics {
                        contentDescription = "登录密码"
                        stateDescription = if (passwordVisible) "密码已显示" else "密码已隐藏"
                    }
                    .testTag(BusinessLoginTags.PASSWORD),
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Checkbox(
                    checked = state.remember,
                    onCheckedChange = onRememberChange,
                    enabled = !state.submitting,
                    modifier = Modifier.testTag(BusinessLoginTags.REMEMBER),
                )
                Text("记住密码")
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Checkbox(
                    checked = state.agreementAccepted,
                    onCheckedChange = onAgreementChange,
                    enabled = !state.submitting,
                    modifier = Modifier.testTag(BusinessLoginTags.AGREEMENT),
                )
                Text("我已阅读并同意")
                TextButton(
                    onClick = onOpenServiceAgreement,
                    modifier = Modifier.testTag(BusinessLoginTags.SERVICE_AGREEMENT),
                ) {
                    Text("服务协议")
                }
                Text("和")
                TextButton(
                    onClick = onOpenPrivacyPolicy,
                    modifier = Modifier.testTag(BusinessLoginTags.PRIVACY_POLICY),
                ) {
                    Text("隐私政策")
                }
            }
            BusinessSliderVerification(
                state = state.slider,
                onCompleted = onSliderCompleted,
                onDismissed = onSliderDismissed,
            )
            Text(
                text = errorText,
                color = if (errorText == "\u00A0") Color.Transparent else MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier
                    .fillMaxWidth()
                    .semantics { contentDescription = "登录错误提示" }
                    .testTag(BusinessLoginTags.ERROR),
            )
            Button(
                onClick = onSubmit,
                enabled = submitEnabled,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp)
                    .testTag(BusinessLoginTags.SUBMIT),
            ) {
                Text(if (state.submitting) "登录中…" else "登录")
            }
        }
    }
}

@Composable
private fun TenantSelectionDialog(
    candidates: List<BusinessTenantCandidateState>,
    onSelected: (OaTenantCandidate) -> Unit,
    onCancelled: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onCancelled,
        title = { Text("选择登录租户") },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                candidates.forEachIndexed { index, option ->
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        tonalElevation = 1.dp,
                    ) {
                        Button(
                            onClick = { onSelected(option.candidate) },
                            enabled = option.enabled,
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag(BusinessLoginTags.tenantOption(index)),
                        ) {
                            Column(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalAlignment = Alignment.Start,
                            ) {
                                Text(tenantLabel(option.candidate))
                                if (!option.enabled) {
                                    Text(
                                        text = "入驻中，暂不可选择",
                                        style = MaterialTheme.typography.bodySmall,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(
                onClick = onCancelled,
                modifier = Modifier.testTag(BusinessLoginTags.TENANT_CANCEL),
            ) {
                Text("取消")
            }
        },
        modifier = Modifier.testTag(BusinessLoginTags.TENANT_DIALOG),
    )
}

private fun tenantLabel(candidate: OaTenantCandidate): String =
    candidate.tenantName?.trim()?.takeIf(String::isNotEmpty)
        ?: candidate.tenantId.let { tenantId ->
            if (tenantId.length <= MASKED_TENANT_SUFFIX_LENGTH) {
                "租户 ****"
            } else {
                "租户 ****${tenantId.takeLast(MASKED_TENANT_SUFFIX_LENGTH)}"
            }
        }

private object BusinessLoginAssets {
    val background: ImageBitmap by lazy { decode("/brand/login_bg.png") }
    val logo: ImageBitmap by lazy { decode("/brand/xiangniao-law-logo.gif") }

    private fun decode(path: String): ImageBitmap {
        val bytes = requireNotNull(BusinessLoginAssets::class.java.getResourceAsStream(path)) {
            "Missing packaged login resource: $path"
        }.use { it.readBytes() }
        return SkiaImage.makeFromEncoded(bytes).use { it.toComposeImageBitmap() }
    }
}

private val WIDE_LAYOUT_THRESHOLD = 1_100.dp
private val MIN_FORM_WIDTH = 462.dp
private val FORM_CONTENT_WIDTH = 462.dp
private val LOGIN_BACKGROUND = Color(0xFFF4F8FF)
private const val ILLUSTRATION_FRACTION = 0.68f
private const val MASKED_TENANT_SUFFIX_LENGTH = 4
