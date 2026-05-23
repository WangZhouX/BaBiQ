package com.wzx.babiq.server.observability;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.math.BigDecimal;
import java.util.Map;

/**
 * BaBiQ 可观测性配置。
 *
 * @param pricing 按模型名配置的百万 token 价格表
 */
@ConfigurationProperties(prefix = "babiq.observability")
public record ObservabilityProperties(Map<String, ModelPricing> pricing) {

    public ObservabilityProperties {
        pricing = pricing == null ? Map.of() : Map.copyOf(pricing);
    }

    /**
     * 单模型价格配置。
     *
     * @param promptPerMillionTokens prompt 每百万 token 美元价格
     * @param completionPerMillionTokens completion 每百万 token 美元价格
     */
    public record ModelPricing(BigDecimal promptPerMillionTokens, BigDecimal completionPerMillionTokens) {
    }
}
