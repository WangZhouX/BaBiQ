package com.wzx.huitai.desktop.ui.settings

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.semantics.SemanticsProperties
import com.wzx.huitai.agent.conversation.BusinessProvider
import com.wzx.huitai.agent.conversation.BusinessProviderDraft
import com.wzx.huitai.agent.conversation.BusinessProviderModel
import com.wzx.huitai.agent.conversation.BusinessProviderOAuthStatus
import com.wzx.huitai.desktop.controller.BusinessProviderSettingsNotice
import com.wzx.huitai.desktop.controller.BusinessProviderSettingsNoticeLevel
import com.wzx.huitai.desktop.controller.BusinessProviderSettingsState
import com.wzx.huitai.desktop.ui.theme.HuitaiBusinessTheme
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import org.junit.Rule
import org.junit.Test

class BusinessProviderSettingsPanelTest {
    @get:Rule
    val rule = createComposeRule()

    @Test
    fun `panel renders only safe provider fields active state and key status`() {
        val marker = "sk-never-render-this-marker"
        rule.setContent {
            HuitaiBusinessTheme {
                BusinessProviderSettingsPanel(
                    state = state(
                        providers = listOf(
                            provider(
                                id = "relay",
                                active = true,
                                hasApiKey = true,
                                baseUrl = "https://relay.example.com/v1",
                                model = "kimi-k3",
                                contextWindow = 131_072,
                            ),
                        ),
                    ),
                    onCreate = { error(marker) },
                )
            }
        }

        rule.onNodeWithTag("provider-settings-panel").assertExists()
        listOf(
            "Relay",
            "relay",
            "OPENAI_COMPATIBLE",
            "api_key",
            "https://relay.example.com/v1",
            "kimi-k3",
            "131072",
            "API Key 已配置",
            "当前",
        ).forEach { rule.onNodeWithText(it).assertExists() }
        rule.onAllNodesWithText(marker, substring = true).assertCountEquals(0)
    }

    @Test
    fun `create validates required fields and nonnegative context then forwards free model text`() {
        var created: BusinessProviderDraft? = null
        rule.setContent {
            HuitaiBusinessTheme {
                BusinessProviderSettingsPanel(
                    state = state(),
                    onCreate = {
                        created = it
                        true
                    },
                )
            }
        }

        rule.onNodeWithTag("provider-add-action").performClick()
        rule.onNodeWithTag("provider-save-action").assertIsNotEnabled()
        rule.onNodeWithTag("provider-id-input").performTextReplacement("relay")
        rule.onNodeWithTag("provider-display-name-input").performTextReplacement("Relay")
        rule.onNodeWithTag("provider-model-input").performTextReplacement("kimi-k3")
        rule.onNodeWithTag("provider-context-window-input").performTextReplacement("-1")
        rule.onNodeWithTag("provider-save-action").assertIsNotEnabled()
        rule.onNodeWithTag("provider-context-window-input").performTextReplacement("131072")
        rule.onNodeWithTag("provider-base-url-input").performTextReplacement("https://relay.example.com/v1")
        rule.onNodeWithTag("provider-api-key-input").performTextReplacement("sk-once")
        rule.onNodeWithTag("provider-save-action").assertIsEnabled().performClick()
        rule.waitUntil { created != null }

        assertEquals("relay", created?.providerId)
        assertEquals("Relay", created?.displayName)
        assertEquals("kimi-k3", created?.model)
        assertEquals(131_072, created?.contextWindow)
        assertEquals("sk-once", created?.apiKey)
        rule.onNodeWithTag("provider-api-key-input").assertDoesNotExist()
    }

    @Test
    fun `edit and copy preserve safe fields keep edit id readonly and always start with blank key`() {
        val relay = provider(
            id = "relay",
            hasApiKey = true,
            baseUrl = "https://relay.example.com/v1",
            model = "kimi-k3",
            contextWindow = 131_072,
        )
        var updated: BusinessProviderDraft? = null
        rule.setContent {
            HuitaiBusinessTheme {
                BusinessProviderSettingsPanel(
                    state = state(providers = listOf(relay)),
                    onUpdate = {
                        updated = it
                        true
                    },
                )
            }
        }

        rule.onNodeWithTag("provider-edit-relay").performClick()
        assertInputText("provider-id-input", "relay")
        rule.onNodeWithTag("provider-id-input").assertIsNotEnabled()
        assertInputText("provider-display-name-input", "Relay")
        assertInputText("provider-base-url-input", "https://relay.example.com/v1")
        assertInputText("provider-model-input", "kimi-k3")
        assertInputText("provider-context-window-input", "131072")
        assertInputText("provider-api-key-input", "")
        rule.onNodeWithTag("provider-save-action").performClick()
        rule.waitUntil { updated != null }
        assertEquals("relay", updated?.providerId)
        assertEquals("kimi-k3", updated?.model)
        assertNull(updated?.apiKey)

        rule.onNodeWithTag("provider-copy-relay").performClick()
        assertInputText("provider-id-input", "relay-copy")
        rule.onNodeWithTag("provider-id-input").assertIsEnabled()
        assertInputText("provider-display-name-input", "Relay 副本")
        assertInputText("provider-api-key-input", "")
    }

    @Test
    fun `editor validation mirrors backend provider type auth base url and key rules`() {
        rule.setContent {
            HuitaiBusinessTheme {
                BusinessProviderSettingsPanel(state = state(), onCreate = { true })
            }
        }

        rule.onNodeWithTag("provider-add-action").performClick()
        rule.onNodeWithTag("provider-id-input").performTextReplacement("custom")
        rule.onNodeWithTag("provider-display-name-input").performTextReplacement("Custom")
        rule.onNodeWithTag("provider-model-input").performTextReplacement("kimi-k3")
        rule.onNodeWithTag("provider-save-action").assertIsNotEnabled()
        rule.onNodeWithTag("provider-api-key-input").performTextReplacement("sk-new")
        rule.onNodeWithTag("provider-save-action").assertIsNotEnabled()
        rule.onNodeWithTag("provider-base-url-input").performTextReplacement("https://relay.example.com/v1")
        rule.onNodeWithTag("provider-save-action").assertIsEnabled()

        rule.onNodeWithTag("provider-type-input").performTextReplacement("UNKNOWN")
        rule.onNodeWithTag("provider-save-action").assertIsNotEnabled()
        rule.onNodeWithTag("provider-type-input").performTextReplacement("DASHSCOPE")
        rule.onNodeWithTag("provider-base-url-input").performTextReplacement("")
        rule.onNodeWithTag("provider-save-action").assertIsEnabled()

        rule.onNodeWithTag("provider-auth-mode-input").performTextReplacement("token")
        rule.onNodeWithTag("provider-save-action").assertIsNotEnabled()
        rule.onNodeWithTag("provider-auth-mode-input").performTextReplacement("oauth_cli")
        rule.onNodeWithTag("provider-save-action").assertIsNotEnabled()
        rule.onNodeWithTag("provider-type-input").performTextReplacement("ANTHROPIC")
        rule.onNodeWithTag("provider-save-action").assertIsEnabled()
        rule.onNodeWithTag("provider-api-key-input").assertDoesNotExist()
        rule.onNodeWithText("保存后可检查或启动 OAuth 登录").assertExists()
        rule.onNodeWithTag("provider-oauth-status-action").assertDoesNotExist()
        rule.onNodeWithTag("provider-oauth-login-action").assertDoesNotExist()
    }

    @Test
    fun `edit api key may stay blank only when provider already has a stored key`() {
        val providers = listOf(
            provider("stored", hasApiKey = true, baseUrl = "https://stored.example.com/v1"),
            provider("missing", hasApiKey = false, baseUrl = "https://missing.example.com/v1"),
        )
        rule.setContent {
            HuitaiBusinessTheme {
                BusinessProviderSettingsPanel(state = state(providers = providers), onUpdate = { true })
            }
        }

        rule.onNodeWithTag("provider-edit-stored").performClick()
        rule.onNodeWithTag("provider-save-action").assertIsEnabled()
        rule.onNodeWithTag("provider-editor-cancel").performClick()
        rule.onNodeWithTag("provider-edit-missing").performClick()
        rule.onNodeWithTag("provider-save-action").assertIsNotEnabled()
        rule.onNodeWithTag("provider-api-key-input").performTextReplacement("sk-replacement")
        rule.onNodeWithTag("provider-save-action").assertIsEnabled()
        rule.onNodeWithTag("provider-editor-cancel").performClick()

        rule.onNodeWithTag("provider-edit-stored").performClick()
        rule.onNodeWithTag("provider-type-input").performTextReplacement("ANTHROPIC")
        rule.onNodeWithTag("provider-auth-mode-input").performTextReplacement("oauth_cli")
        rule.onNodeWithText("保存后可检查或启动 OAuth 登录").assertExists()
        rule.onNodeWithTag("provider-oauth-status-action").assertDoesNotExist()
        rule.onNodeWithTag("provider-oauth-login-action").assertDoesNotExist()
    }

    @Test
    fun `save awaits result keeps key while running preserves safe draft on failure and dismisses only on success`() {
        val stateHolder = mutableStateOf(state())
        var result = CompletableDeferred<Boolean>()
        var captured: BusinessProviderDraft? = null
        rule.setContent {
            HuitaiBusinessTheme {
                BusinessProviderSettingsPanel(
                    state = stateHolder.value,
                    onCreate = { draft ->
                        captured = draft
                        val saved = result.await()
                        if (!saved) {
                            stateHolder.value = stateHolder.value.copy(
                                notice = BusinessProviderSettingsNotice(
                                    "PROVIDER_SETTINGS_FAILED",
                                    "Provider ID 已存在",
                                    BusinessProviderSettingsNoticeLevel.ERROR,
                                ),
                            )
                        }
                        saved
                    },
                )
            }
        }

        rule.onNodeWithTag("provider-add-action").performClick()
        rule.onNodeWithTag("provider-id-input").performTextReplacement("relay")
        rule.onNodeWithTag("provider-display-name-input").performTextReplacement("Relay Draft")
        rule.onNodeWithTag("provider-base-url-input").performTextReplacement("https://relay.example.com/v1")
        rule.onNodeWithTag("provider-model-input").performTextReplacement("kimi-k3")
        rule.onNodeWithTag("provider-api-key-input").performTextReplacement("sk-in-flight")
        rule.onNodeWithTag("provider-save-action").performClick()
        rule.waitUntil { captured != null }
        rule.onNodeWithTag("provider-save-action").assertExists().assertIsNotEnabled()
        assertInputText("provider-api-key-input", "sk-in-flight")

        result.complete(false)
        rule.waitForIdle()
        rule.onNodeWithTag("provider-save-action").assertExists()
        assertInputText("provider-api-key-input", "")
        assertInputText("provider-display-name-input", "Relay Draft")
        assertInputText("provider-base-url-input", "https://relay.example.com/v1")
        assertInputText("provider-model-input", "kimi-k3")
        rule.onNodeWithText("Provider ID 已存在").assertExists()

        rule.runOnIdle { stateHolder.value = stateHolder.value.copy(operationsEnabled = false) }
        rule.onNodeWithTag("provider-save-action").assertExists().assertIsNotEnabled()
        assertInputText("provider-model-input", "kimi-k3")
        rule.runOnIdle { stateHolder.value = stateHolder.value.copy(operationsEnabled = true) }

        result = CompletableDeferred()
        captured = null
        rule.onNodeWithTag("provider-api-key-input").performTextReplacement("sk-retry")
        rule.onNodeWithTag("provider-save-action").performClick()
        rule.waitUntil { captured?.apiKey == "sk-retry" }
        result.complete(true)
        rule.waitForIdle()
        rule.onNodeWithTag("provider-save-action").assertDoesNotExist()
    }

    @Test
    fun `row refresh oauth and delete confirmation forward every controller action`() {
        val oauth = provider(
            id = "claude",
            type = "ANTHROPIC",
            authMode = "oauth_cli",
            model = "claude-sonnet-4-6",
        )
        val calls = mutableListOf<String>()
        val stateHolder = mutableStateOf(
            state(
                providers = listOf(oauth),
                oauthStatus = mapOf(
                    "claude" to BusinessProviderOAuthStatus(
                        providerType = "ANTHROPIC",
                        authMode = "oauth_cli",
                        cliInstalled = true,
                        loggedIn = false,
                        message = "未登录",
                    ),
                ),
                notice = BusinessProviderSettingsNotice(
                    "SAFE_NOTICE",
                    "安全提示",
                    BusinessProviderSettingsNoticeLevel.INFO,
                ),
            ),
        )
        rule.setContent {
            HuitaiBusinessTheme {
                BusinessProviderSettingsPanel(
                    state = stateHolder.value,
                    onRefresh = { calls += "refresh" },
                    onDelete = { calls += "delete:$it" },
                    onTest = { calls += "test:$it" },
                    onSetActive = { providerId, modelId -> calls += "active:$providerId:$modelId" },
                    onOAuthStatus = { calls += "oauth-status:$it" },
                    onOAuthLogin = { calls += "oauth-login:$it" },
                )
            }
        }

        rule.onNodeWithText("安全提示").assertExists()
        rule.onNodeWithTag("provider-refresh-action").performClick()
        rule.onNodeWithTag("provider-test-claude").performClick()
        rule.onNodeWithTag("provider-activate-claude").performClick()
        rule.onNodeWithTag("provider-edit-claude").performClick()
        rule.onNodeWithTag("provider-api-key-input").assertDoesNotExist()
        rule.onNodeWithText("未登录").assertExists()
        rule.onNodeWithTag("provider-oauth-status-action").performClick()
        rule.onNodeWithTag("provider-oauth-login-action").performClick()
        rule.runOnIdle { stateHolder.value = stateHolder.value.copy(operationsEnabled = false) }
        rule.onNodeWithTag("provider-oauth-status-action").assertIsNotEnabled()
        rule.onNodeWithTag("provider-oauth-login-action").assertIsNotEnabled()
        rule.runOnIdle { stateHolder.value = stateHolder.value.copy(operationsEnabled = true) }
        rule.onNodeWithTag("provider-editor-cancel").performClick()
        rule.onNodeWithTag("provider-copy-claude").performClick()
        rule.onNodeWithText("保存后可检查或启动 OAuth 登录").assertExists()
        rule.onNodeWithTag("provider-oauth-status-action").assertDoesNotExist()
        rule.onNodeWithTag("provider-oauth-login-action").assertDoesNotExist()
        rule.onNodeWithTag("provider-editor-cancel").performClick()
        rule.onNodeWithTag("provider-delete-claude").performClick()
        rule.onNodeWithTag("provider-delete-confirm").performClick()

        assertEquals(
            listOf(
                "refresh",
                "test:claude",
                "active:claude:claude-sonnet-4-6",
                "oauth-status:claude",
                "oauth-login:claude",
                "delete:claude",
            ),
            calls,
        )
    }

    @Test
    fun `generation and disconnect clear only local key while preserving safe draft and disabling actions`() {
        val relay = provider(id = "relay", hasApiKey = true, model = "kimi-k3")
        val stateHolder = mutableStateOf(state(providers = listOf(relay)))
        var updated: BusinessProviderDraft? = null
        rule.setContent {
            HuitaiBusinessTheme {
                BusinessProviderSettingsPanel(
                    state = stateHolder.value,
                    onUpdate = {
                        updated = it
                        true
                    },
                )
            }
        }

        rule.onNodeWithTag("provider-edit-relay").performClick()
        rule.onNodeWithTag("provider-model-input").performTextReplacement("custom-model")
        rule.onNodeWithTag("provider-api-key-input").performTextReplacement("sk-local-only")
        rule.runOnIdle {
            stateHolder.value = stateHolder.value.copy(connectionGeneration = 2)
        }
        assertInputText("provider-api-key-input", "")
        assertInputText("provider-model-input", "custom-model")

        rule.onNodeWithTag("provider-api-key-input").performTextReplacement("sk-local-only-again")
        rule.runOnIdle {
            stateHolder.value = stateHolder.value.copy(operationsEnabled = false)
        }
        assertInputText("provider-api-key-input", "")
        assertInputText("provider-model-input", "custom-model")
        rule.onNodeWithTag("provider-save-action").assertIsNotEnabled()
        rule.onNodeWithTag("provider-refresh-action").assertIsNotEnabled()
        rule.onNodeWithTag("provider-test-relay").assertIsNotEnabled()
        rule.onNodeWithTag("provider-activate-relay").assertIsNotEnabled()
        rule.onNodeWithTag("provider-delete-relay").assertIsNotEnabled()
        assertNull(updated)
        assertTrue(!stateHolder.value.toString().contains("sk-local-only"))
    }

    private fun state(
        providers: List<BusinessProvider> = emptyList(),
        oauthStatus: Map<String, BusinessProviderOAuthStatus> = emptyMap(),
        notice: BusinessProviderSettingsNotice? = null,
    ) = BusinessProviderSettingsState(
        providers = providers,
        oauthStatus = oauthStatus,
        notice = notice,
        operationsEnabled = true,
        connectionGeneration = 1,
    )

    private fun provider(
        id: String,
        active: Boolean = false,
        hasApiKey: Boolean = false,
        type: String = "OPENAI_COMPATIBLE",
        authMode: String = "api_key",
        baseUrl: String = "",
        model: String = "model-1",
        contextWindow: Int = 0,
    ) = BusinessProvider(
        id = id,
        displayName = if (id == "relay") "Relay" else id.replaceFirstChar(Char::uppercase),
        models = listOf(BusinessProviderModel(model, model, active)),
        authMode = authMode,
        hasApiKey = hasApiKey,
        active = active,
        type = type,
        baseUrl = baseUrl,
        model = model,
        contextWindow = contextWindow,
    )

    private fun assertInputText(tag: String, expected: String) {
        val input = rule.onNodeWithTag(tag).fetchSemanticsNode().config[SemanticsProperties.InputText]
        assertEquals(expected, input.text)
    }
}
