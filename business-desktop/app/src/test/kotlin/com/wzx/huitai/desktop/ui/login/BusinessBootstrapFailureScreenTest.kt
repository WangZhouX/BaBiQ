package com.wzx.huitai.desktop.ui.login

import androidx.compose.foundation.layout.requiredSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import com.wzx.huitai.desktop.auth.config.BusinessOaConfigurationErrorCode
import com.wzx.huitai.desktop.auth.config.BusinessOaConfigurationException
import com.wzx.huitai.desktop.security.LocalCredentialStoreUnavailableException
import com.wzx.huitai.desktop.ui.shell.BusinessUiTags
import com.wzx.huitai.desktop.ui.theme.HuitaiBusinessTheme
import com.wzx.huitai.security.secret.SecretStoreException
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.Rule
import org.junit.Test

class BusinessBootstrapFailureScreenTest {
    @get:Rule
    val rule = createComposeRule()

    @Test
    fun `configuration and keystore startup failures map to stable public codes`() {
        assertEquals(
            BusinessBootstrapFailureCode.CONFIG_UNAVAILABLE,
            classifyBusinessBootstrapFailure(
                BusinessOaConfigurationException(BusinessOaConfigurationErrorCode.CONFIG_UNAVAILABLE),
            ),
        )
        assertEquals(
            BusinessBootstrapFailureCode.CONFIG_INVALID,
            classifyBusinessBootstrapFailure(
                IllegalStateException(
                    "wrapper",
                    BusinessOaConfigurationException(BusinessOaConfigurationErrorCode.CONFIG_INVALID),
                ),
            ),
        )
        assertEquals(
            BusinessBootstrapFailureCode.LOCAL_KEYSTORE_UNAVAILABLE,
            classifyBusinessBootstrapFailure(LocalCredentialStoreUnavailableException()),
        )
        assertEquals(
            BusinessBootstrapFailureCode.LOCAL_KEYSTORE_UNAVAILABLE,
            classifyBusinessBootstrapFailure(SecretStoreException("do not display this detail")),
        )
        assertNull(classifyBusinessBootstrapFailure(IllegalStateException("unrelated")))
    }

    @Test
    fun `bootstrap failure screen is fail closed and displays no business or login controls`() {
        rule.setContent {
            HuitaiBusinessTheme {
                BusinessBootstrapFailureScreen(
                    code = BusinessBootstrapFailureCode.LOCAL_KEYSTORE_UNAVAILABLE,
                    modifier = Modifier.requiredSize(900.dp, 700.dp),
                )
            }
        }

        rule.onNodeWithTag(BusinessBootstrapFailureTags.ROOT).assertExists()
        rule.onNodeWithTag(BusinessBootstrapFailureTags.CODE)
            .assertTextContains("LOCAL_KEYSTORE_UNAVAILABLE")
        rule.onNodeWithTag(BusinessUiTags.CONTENT).assertDoesNotExist()
        rule.onNodeWithTag(BusinessLoginTags.ROOT).assertDoesNotExist()
    }

    @Test
    fun `main opens fail closed bootstrap window before returning from classified startup failure`() {
        val source = Path.of(
            "src", "main", "kotlin", "com", "wzx", "huitai", "desktop", "Main.kt",
        ).toFile().readText()
        val startupCatch = source.indexOf("catch (failure: Exception)")
        val classifier = source.indexOf("classifyBusinessBootstrapFailure(failure)", startupCatch)
        val window = source.indexOf("openBusinessBootstrapFailureWindow(", classifier)
        val returnIndex = source.indexOf("return", window)

        assertTrue(startupCatch >= 0)
        assertTrue(classifier > startupCatch)
        assertTrue(window > classifier)
        assertTrue(returnIndex > window)
    }
}
