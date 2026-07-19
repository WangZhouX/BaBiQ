package com.wzx.babiq.server.api.method;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.wzx.babiq.server.api.error.JsonRpcErrorCode;
import com.wzx.babiq.server.api.error.JsonRpcException;
import com.wzx.babiq.server.model.BaBiQProperties;
import com.wzx.babiq.server.model.ModelProviderConfig;
import com.wzx.babiq.server.model.ModelProviderRegistry;
import com.wzx.babiq.server.model.ProviderType;
import com.wzx.babiq.server.settings.AppSettingsService;
import com.wzx.babiq.server.settings.ProviderSettingsService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentCaptor.forClass;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Provider 设置 JSON-RPC handler 测试。
 *
 * <p>这些 handler 是桌面设置页的协议边界：它们只负责参数校验和 DTO 转换，
 * 不能回显 API Key，也不能直接访问 MyBatis Mapper。</p>
 */
class ProviderSettingsHandlersTest {

    /** 测试用 JSON 转换器，和生产 handler 使用相同 Jackson 行为。 */
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    @DisplayName("provider/create 缺少必填字段时返回 INVALID_PARAMS")
    void provider_create_should_reject_missing_required_fields() {
        ProviderCreateHandler handler = new ProviderCreateHandler(mock(ProviderSettingsService.class), objectMapper);

        assertThatThrownBy(() -> handler.handle(objectMapper.valueToTree(Map.of(
                "providerId", "p1",
                "type", "OPENAI_COMPATIBLE"
        )), null))
                .isInstanceOfSatisfying(JsonRpcException.class, exception ->
                        assertThat(exception.errorCode()).isEqualTo(JsonRpcErrorCode.INVALID_PARAMS));
    }

    @Test
    @DisplayName("provider/create 保存后不回显 API Key")
    void provider_create_should_not_echo_api_key() {
        ProviderSettingsService service = mock(ProviderSettingsService.class);
        when(service.create(any())).thenReturn(providerView("p1", true, "OPENAI_COMPATIBLE", "api_key"));
        ProviderCreateHandler handler = new ProviderCreateHandler(service, objectMapper);

        Map<String, Object> response = responseFrom(handler.handle(objectMapper.valueToTree(Map.of(
                "providerId", "p1",
                "displayName", "Provider 1",
                "type", "OPENAI_COMPATIBLE",
                "baseUrl", "https://relay.example.com/v1",
                "model", "gpt-4o-mini",
                "apiKey", "sk-secret"
        )), null));

        assertThat(response)
                .containsEntry("id", "p1")
                .containsEntry("hasApiKey", true);
        assertThat(response).doesNotContainKey("apiKey");
    }

    @Test
    @DisplayName("provider/create 支持 Anthropic OAuth CLI 无 API Key 和无 Base URL")
    void provider_create_should_accept_anthropic_oauth_cli_without_api_key_or_base_url() {
        ProviderSettingsService service = mock(ProviderSettingsService.class);
        when(service.create(any())).thenReturn(providerView("anthropic-oauth", false, "ANTHROPIC", "oauth_cli"));
        ProviderCreateHandler handler = new ProviderCreateHandler(service, objectMapper);

        Map<String, Object> response = responseFrom(handler.handle(objectMapper.valueToTree(Map.of(
                "providerId", "anthropic-oauth",
                "displayName", "Claude OAuth",
                "type", "ANTHROPIC",
                "authMode", "oauth_cli",
                "model", "claude-sonnet-4-6"
        )), null));

        var captor = forClass(ProviderSettingsService.ProviderDraft.class);
        verify(service).create(captor.capture());
        assertThat(captor.getValue().authMode()).isEqualTo("oauth_cli");
        assertThat(captor.getValue().apiKey()).isNull();
        assertThat(captor.getValue().baseUrl()).isNull();
        assertThat(response)
                .containsEntry("type", "ANTHROPIC")
                .containsEntry("authMode", "oauth_cli")
                .containsEntry("baseUrl", "")
                .containsEntry("hasApiKey", false);
        assertThat(response).doesNotContainKey("apiKey");
    }

    @Test
    @DisplayName("provider/list 只返回 service 暴露的非敏感视图")
    void provider_list_should_return_provider_views() {
        ProviderSettingsService service = mock(ProviderSettingsService.class);
        when(service.listEnabled()).thenReturn(List.of(providerView("p1", true, "OPENAI_COMPATIBLE", "api_key")));
        ProviderListHandler handler = new ProviderListHandler(service);

        Map<String, Object> response = responseFrom(handler.handle(null, null));

        assertThat(response).containsKey("providers");
        assertThat(response.toString()).doesNotContain("sk-");
    }

    @Test
    @DisplayName("provider/delete 委托服务禁用 Provider")
    void provider_delete_should_delegate_to_service() {
        ProviderSettingsService service = mock(ProviderSettingsService.class);
        when(service.delete("p1"))
                .thenReturn(new ProviderSettingsService.ProviderDeleteResult("p1", "p2"));
        ProviderDeleteHandler handler = new ProviderDeleteHandler(service);

        Map<String, Object> response = responseFrom(handler.handle(objectMapper.valueToTree(Map.of(
                "providerId", "p1"
        )), null));

        verify(service).delete("p1");
        assertThat(response)
                .containsEntry("ok", true)
                .containsEntry("providerId", "p1")
                .containsEntry("activeProviderId", "p2");
    }

    @Test
    @DisplayName("provider/delete 把服务参数错误映射为不含原异常链的 INVALID_PARAMS")
    void provider_delete_should_map_service_validation_failure_safely() {
        String sensitiveMarker = "sk-fake-sensitive-marker";
        ProviderSettingsService service = mock(ProviderSettingsService.class);
        doThrow(new IllegalArgumentException(sensitiveMarker)).when(service).delete("missing");
        ProviderDeleteHandler handler = new ProviderDeleteHandler(service);

        Throwable failure = catchThrowable(() -> handler.handle(objectMapper.valueToTree(Map.of(
                "providerId", "missing"
        )), null));

        assertThat(failure).isInstanceOfSatisfying(JsonRpcException.class, exception -> {
            assertThat(exception.errorCode()).isEqualTo(JsonRpcErrorCode.INVALID_PARAMS);
            assertThat(exception.getMessage()).isEqualTo("Provider 删除请求无效");
            assertThat(exception.getCause()).isNull();
            assertThat(exception.toString()).doesNotContain(sensitiveMarker);
        });
    }

    @Test
    @DisplayName("provider/test 失败响应只返回固定安全文案")
    void provider_test_should_not_echo_internal_failure_message() {
        String sensitiveMarker = "sk-fake-sensitive-marker";
        ProviderSettingsService service = mock(ProviderSettingsService.class);
        when(service.testConnection("p1")).thenReturn(new ProviderSettingsService.ProviderTestResult(
                false, "p1", sensitiveMarker));
        ProviderTestHandler handler = new ProviderTestHandler(service);

        Map<String, Object> response = responseFrom(handler.handle(objectMapper.valueToTree(Map.of(
                "providerId", "p1"
        )), null));

        assertThat(response)
                .containsEntry("ok", false)
                .containsEntry("providerId", "p1")
                .containsEntry("message", "Provider 配置检查失败");
        assertThat(response.toString()).doesNotContain(sensitiveMarker);
    }

    @Test
    @DisplayName("provider/update 把 enabled 不变量错误映射为安全 INVALID_PARAMS")
    void provider_update_should_map_enabled_validation_failure_safely() {
        String sensitiveMarker = "sk-fake-sensitive-marker";
        ProviderSettingsService service = mock(ProviderSettingsService.class);
        doThrow(new IllegalArgumentException("enabled " + sensitiveMarker)).when(service).update(any());
        ProviderUpdateHandler handler = new ProviderUpdateHandler(service, objectMapper);

        Throwable failure = catchThrowable(() -> handler.handle(objectMapper.valueToTree(Map.of(
                "providerId", "p1",
                "displayName", "Provider 1",
                "type", "OPENAI_COMPATIBLE",
                "baseUrl", "https://relay.example.com/v1",
                "model", "gpt-4o-mini",
                "enabled", false
        )), null));

        assertThat(failure).isInstanceOfSatisfying(JsonRpcException.class, exception -> {
            assertThat(exception.errorCode()).isEqualTo(JsonRpcErrorCode.INVALID_PARAMS);
            assertThat(exception.getMessage()).isEqualTo("Provider 更新请求无效");
            assertThat(exception.getCause()).isNull();
            assertThat(exception.toString()).doesNotContain(sensitiveMarker);
        });
    }

    @Test
    @DisplayName("provider/set-active 作为新协议别名持久化 active provider")
    void provider_set_active_should_delegate_to_legacy_handler() {
        AppSettingsService appSettingsService = mock(AppSettingsService.class);
        ModelProviderRegistry registry = new ModelProviderRegistry(new BaBiQProperties(
                "p1",
                List.of(new ModelProviderConfig(
                        "p1",
                        "Provider 1",
                        ProviderType.OPENAI_COMPATIBLE,
                        "gpt-4o-mini",
                        "sk-test",
                        "https://relay.example.com/v1",
                        128000)),
                null));
        ProviderSetActiveHandler handler = new ProviderSetActiveHandler(
                new ProvidersSetActiveHandler(registry, appSettingsService));

        Map<String, Object> response = responseFrom(handler.handle(objectMapper.valueToTree(Map.of(
                "providerId", "p1"
        )), null));

        assertThat(handler.method()).isEqualTo("provider/set-active");
        assertThat(response).containsEntry("ok", true);
        verify(appSettingsService).update(any());
    }

    private static ProviderSettingsService.ProviderView providerView(String id,
                                                                     boolean hasApiKey,
                                                                     String type,
                                                                     String authMode) {
        return new ProviderSettingsService.ProviderView(
                id,
                "Provider 1",
                type,
                authMode,
                type.equals("ANTHROPIC") ? "" : "https://relay.example.com/v1",
                type.equals("ANTHROPIC") ? "claude-sonnet-4-6" : "gpt-4o-mini",
                128000,
                true,
                hasApiKey,
                true,
                null);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> responseFrom(Object payload) {
        return (Map<String, Object>) payload;
    }
}
