package com.wzx.babiq.server.observability;

import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * 基于配置价格表估算单轮模型成本。
 */
@Component
public class CostCalculator {

    /** 模型计价通常按百万 token 给价格，这个常量用于把 token 数换算成价格单位。 */
    private static final BigDecimal ONE_MILLION = new BigDecimal("1000000");
    /** 成本金额保留的小数位，8 位足够展示低成本单轮调用。 */
    private static final int MONEY_SCALE = 8;

    /** 观测配置，里面维护不同模型的 prompt/completion 单价。 */
    private final ObservabilityProperties properties;

    public CostCalculator(ObservabilityProperties properties) {
        this.properties = properties;
    }

    /**
     * 估算指定模型的美元成本。
     *
     * @param model 模型名
     * @param promptTokens prompt token 数
     * @param completionTokens completion token 数
     * @return 估算美元成本,未知模型返回 0
     */
    public BigDecimal estimate(String model, long promptTokens, long completionTokens) {
        ObservabilityProperties.ModelPricing pricing = properties.pricing().get(model);
        if (pricing == null) {
            return BigDecimal.ZERO.setScale(MONEY_SCALE, RoundingMode.HALF_UP);
        }
        BigDecimal promptCost = tokenCost(promptTokens, pricing.promptPerMillionTokens());
        BigDecimal completionCost = tokenCost(completionTokens, pricing.completionPerMillionTokens());
        return promptCost.add(completionCost).setScale(MONEY_SCALE, RoundingMode.HALF_UP);
    }

    private BigDecimal tokenCost(long tokens, BigDecimal perMillionTokens) {
        if (perMillionTokens == null || tokens <= 0) {
            return BigDecimal.ZERO;
        }
        return BigDecimal.valueOf(tokens).multiply(perMillionTokens).divide(ONE_MILLION, MONEY_SCALE, RoundingMode.HALF_UP);
    }
}
