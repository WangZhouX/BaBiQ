package com.wzx.babiq.server.observability;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class CostCalculatorTest {

    @Test
    void estimate_should_use_configured_per_million_token_rates() {
        ObservabilityProperties properties = new ObservabilityProperties(Map.of(
                "qwen-plus", new ObservabilityProperties.ModelPricing(
                        new BigDecimal("0.20"), new BigDecimal("0.60"))));
        CostCalculator calculator = new CostCalculator(properties);

        BigDecimal cost = calculator.estimate("qwen-plus", 1000L, 2000L);

        assertThat(cost).isEqualByComparingTo("0.00140000");
    }

    @Test
    void estimate_should_return_zero_for_unknown_model() {
        CostCalculator calculator = new CostCalculator(new ObservabilityProperties(Map.of()));

        assertThat(calculator.estimate("ghost", 1000L, 2000L)).isEqualByComparingTo("0.00000000");
    }
}
