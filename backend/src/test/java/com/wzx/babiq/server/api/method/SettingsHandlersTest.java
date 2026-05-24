package com.wzx.babiq.server.api.method;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.wzx.babiq.server.settings.AppSettings;
import com.wzx.babiq.server.settings.AppSettingsService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 设置 JSON-RPC handler 测试。
 *
 * <p>settings/get 和 settings/update 是桌面设置页的统一入口。这里确保 handler 只做协议层转换，
 * 真正的默认值、枚举校验和持久化全部委托给 AppSettingsService。</p>
 */
class SettingsHandlersTest {

    /** 测试用 JSON 转换器。 */
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    @DisplayName("settings/get 返回当前应用设置")
    void settings_get_should_return_current_settings() {
        AppSettingsService service = mock(AppSettingsService.class);
        when(service.get()).thenReturn(new AppSettings(
                "deepseek-official",
                "WORKSPACE_WRITE",
                "ON_REQUEST",
                "E:\\BaBiQ"));
        SettingsGetHandler handler = new SettingsGetHandler(service);

        Map<String, Object> response = responseFrom(handler.handle(null, null));

        assertThat(response)
                .containsEntry("activeProviderId", "deepseek-official")
                .containsEntry("sandboxMode", "WORKSPACE_WRITE")
                .containsEntry("approvalPolicy", "ON_REQUEST")
                .containsEntry("defaultCwd", "E:\\BaBiQ");
    }

    @Test
    @DisplayName("settings/update 写入后返回更新后的设置")
    void settings_update_should_delegate_and_return_updated_settings() {
        AppSettingsService service = mock(AppSettingsService.class);
        when(service.update(any())).thenReturn(new AppSettings(
                "deepseek-official",
                "READ_ONLY",
                "NEVER",
                "D:\\Work"));
        SettingsUpdateHandler handler = new SettingsUpdateHandler(service, objectMapper);

        Map<String, Object> response = responseFrom(handler.handle(objectMapper.valueToTree(Map.of(
                "activeProviderId", "deepseek-official",
                "sandboxMode", "READ_ONLY",
                "approvalPolicy", "NEVER",
                "defaultCwd", "D:\\Work"
        )), null));

        verify(service).update(any());
        assertThat(response)
                .containsEntry("sandboxMode", "READ_ONLY")
                .containsEntry("approvalPolicy", "NEVER")
                .containsEntry("defaultCwd", "D:\\Work");
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> responseFrom(Object payload) {
        return (Map<String, Object>) payload;
    }
}
