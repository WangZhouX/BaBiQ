package com.wzx.babiq.server.api.method;

import com.wzx.babiq.server.model.ModelProviderConfig;

/**
 * 桌面端可选 Provider 策略。
 *
 * <p>P1-4 先把模型选择做成“读取后端配置并切换 active provider”,但本地 Llama3/Ollama
 * 还缺少进程探测、健康检查和用户可理解的失败提示。把这个限制集中在策略类里,
 * 后续启用本地模型时只需要改这一处,不会让多个 JSON-RPC handler 散落同一条规则。</p>
 */
final class ProviderSelectionPolicy {

    private static final String LOCAL_LLAMA3_PROVIDER_ID = "ollama-local";

    private ProviderSelectionPolicy() {
    }

    /**
     * 判断 provider 是否可以在桌面端模型选择器中展示和选择。
     *
     * @param providerConfig provider 配置
     * @return true 表示当前 P1-4 桌面端可以展示并切换到该 provider
     */
    static boolean isSelectable(ModelProviderConfig providerConfig) {
        return providerConfig != null && isSelectable(providerConfig.id());
    }

    /**
     * 判断 provider id 是否属于当前可选范围。
     *
     * @param providerId provider 唯一标识
     * @return false 表示当前版本暂不暴露给桌面端
     */
    static boolean isSelectable(String providerId) {
        return !LOCAL_LLAMA3_PROVIDER_ID.equals(providerId);
    }

    /**
     * 返回不可选 provider 的用户可读说明。
     *
     * @param providerId provider 唯一标识
     * @return 错误说明
     */
    static String unsupportedMessage(String providerId) {
        if (LOCAL_LLAMA3_PROVIDER_ID.equals(providerId)) {
            return "本地 Llama3 暂未接入桌面端选择器,请先使用云端或中转 provider";
        }
        return "当前 provider 暂不支持在桌面端选择: " + providerId;
    }
}
