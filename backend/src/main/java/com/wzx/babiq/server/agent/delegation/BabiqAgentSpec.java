package com.wzx.babiq.server.agent.delegation;

import java.util.List;

/**
 * BaBiQ 内置子 Agent 的不可变规格。
 *
 * <p>规格只描述子 Agent 能做什么，不携带本轮 cwd、emitter 或 token 等运行态。
 * 运行态由 {@link SubAgentRuntimeFactory} 按 turn 装配，避免跨会话共享状态。</p>
 *
 * @param name function calling 使用的 ASCII 技术名，必须稳定且不能包含中文
 * @param displayName 桌面端展示名称，面向用户可读
 * @param description 工具描述，供模型判断何时委派
 * @param systemPrompt 子 Agent 专用 system prompt，必须包含只读和不可信数据边界
 * @param toolNames 子 Agent 可见工具白名单，P6-1 explorer 只能包含真实存在的只读工具
 * @param modelPolicy 子 Agent 模型策略，默认继承父 Agent 当前 provider/model
 * @param delegationMode 子 Agent 委派模式，P6-1 固定为只读工具委派
 */
public record BabiqAgentSpec(
        String name,
        String displayName,
        String description,
        String systemPrompt,
        List<String> toolNames,
        ModelPolicy modelPolicy,
        BabiqAgentMode delegationMode
) {

    /**
     * 创建规格时复制工具列表，防止调用方后续修改白名单。
     */
    public BabiqAgentSpec {
        toolNames = toolNames == null ? List.of() : List.copyOf(toolNames);
        modelPolicy = modelPolicy == null ? ModelPolicy.inherit() : modelPolicy;
        delegationMode = delegationMode == null ? BabiqAgentMode.READ_ONLY_TOOL : delegationMode;
    }

    /**
     * 子 Agent 模型选择策略。
     *
     * <p>当前只需要 inherit 与 provider override 两种形态；后续如果加入 per-agent 配置 UI，
     * 可以继续扩展而不改变 BabiqAgentSpec 的调用方。</p>
     *
     * @param providerId 指定 provider 时传入；为空表示继承父 Agent 当前 provider
     */
    public record ModelPolicy(String providerId) {

        /** 子 Agent 使用父 Agent 当前 active provider/model。 */
        public static ModelPolicy inherit() {
            return new ModelPolicy(null);
        }

        /** 子 Agent 使用指定 provider，由 ChatClientFactory 统一解析模型。 */
        public static ModelPolicy provider(String providerId) {
            return new ModelPolicy(providerId);
        }

        /** 是否继承父 Agent 当前 provider。 */
        public boolean inherited() {
            return providerId == null || providerId.isBlank();
        }
    }
}
