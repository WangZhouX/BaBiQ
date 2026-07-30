package com.wzx.huitai.desktop.ui.login

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.wzx.huitai.desktop.auth.config.BusinessLegalLinksConfigurationErrorCode
import com.wzx.huitai.desktop.auth.config.BusinessLegalLinksConfigurationException
import com.wzx.huitai.desktop.security.LocalCredentialStoreUnavailableException
import com.wzx.huitai.security.secret.SecretStoreException

enum class BusinessBootstrapFailureCode {
    CONFIG_UNAVAILABLE,
    CONFIG_INVALID,
    LOCAL_KEYSTORE_UNAVAILABLE,
}

object BusinessBootstrapFailureTags {
    const val ROOT = "business-bootstrap-failure-root"
    const val CODE = "business-bootstrap-failure-code"
}

/**
 * 只映射允许展示的稳定错误码；不向 UI 或日志转发路径、异常正文和 KeyStore 细节。
 */
internal fun classifyBusinessBootstrapFailure(failure: Throwable): BusinessBootstrapFailureCode? {
    var current: Throwable? = failure
    val visited = hashSetOf<Throwable>()
    while (current != null && visited.add(current)) {
        when (current) {
            is BusinessLegalLinksConfigurationException -> return when (current.code) {
                BusinessLegalLinksConfigurationErrorCode.CONFIG_UNAVAILABLE ->
                    BusinessBootstrapFailureCode.CONFIG_UNAVAILABLE
                BusinessLegalLinksConfigurationErrorCode.CONFIG_INVALID ->
                    BusinessBootstrapFailureCode.CONFIG_INVALID
            }
            is LocalCredentialStoreUnavailableException,
            is SecretStoreException,
            -> return BusinessBootstrapFailureCode.LOCAL_KEYSTORE_UNAVAILABLE
        }
        current = current.cause
    }
    return null
}

@Composable
fun BusinessBootstrapFailureScreen(
    code: BusinessBootstrapFailureCode,
    modifier: Modifier = Modifier,
) {
    val message = when (code) {
        BusinessBootstrapFailureCode.CONFIG_UNAVAILABLE -> "登录配置不可用，请检查桌面端配置后重新启动。"
        BusinessBootstrapFailureCode.CONFIG_INVALID -> "登录配置无效，请修正桌面端配置后重新启动。"
        BusinessBootstrapFailureCode.LOCAL_KEYSTORE_UNAVAILABLE ->
            "本机安全存储不可用，请检查 KeyStore 配置后重新启动。"
    }
    Column(
        modifier = modifier
            .fillMaxSize()
            .testTag(BusinessBootstrapFailureTags.ROOT)
            .padding(48.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            text = "翔鸟律智桌面端无法安全启动",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
        )
        Text(
            text = code.name,
            color = MaterialTheme.colorScheme.error,
            modifier = Modifier
                .padding(top = 16.dp)
                .testTag(BusinessBootstrapFailureTags.CODE),
        )
        Text(
            text = message,
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.padding(top = 12.dp),
        )
    }
}
