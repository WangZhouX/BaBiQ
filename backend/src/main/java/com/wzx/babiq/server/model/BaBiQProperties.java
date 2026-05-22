package com.wzx.babiq.server.model;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.util.List;

/**
 * BaBiQ 根配置。
 *
 * <p>该配置类绑定 application.yml 的 babiq.* 命名空间。Provider 列表按 D4
 * 使用 List<ModelProviderConfig>,短期记忆窗口按 D18 默认 20 条消息。</p>
 *
 * @param activeProvider 当前激活 provider id
 * @param providers 可用 provider 配置列表
 * @param memory 记忆相关配置
 */
@Validated
@ConfigurationProperties(prefix = "babiq")
public record BaBiQProperties(
        @NotBlank String activeProvider,
        @Valid List<ModelProviderConfig> providers,
        Memory memory
) {

    /**
     * 补齐缺省 memory 配置。
     *
     * @param activeProvider 当前激活 provider id
     * @param providers 可用 provider 配置列表
     * @param memory 记忆相关配置
     */
    public BaBiQProperties {
        if (memory == null) {
            memory = new Memory(new ShortTerm(20));
        }
    }

    /**
     * 记忆配置分组。
     *
     * @param shortTerm 短期记忆配置
     */
    public record Memory(ShortTerm shortTerm) {

        /**
         * 补齐缺省短期记忆配置。
         *
         * @param shortTerm 短期记忆配置
         */
        public Memory {
            if (shortTerm == null) {
                shortTerm = new ShortTerm(20);
            }
        }
    }

    /**
     * 短期记忆窗口配置。
     *
     * @param maxMessages 每个 conversationId 保留的最大消息数
     */
    public record ShortTerm(@Min(1) int maxMessages) {
    }
}
