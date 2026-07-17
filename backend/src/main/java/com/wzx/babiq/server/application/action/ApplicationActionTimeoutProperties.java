package com.wzx.babiq.server.application.action;

import com.wzx.babiq.server.application.config.BusinessDesktopModeProperties;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.time.Duration;

/** Agent 侧等待桌面动作各阶段回报的有界超时。 */
@Component
@ConditionalOnProperty(prefix = "babiq.business", name = "enabled", havingValue = "true")
public record ApplicationActionTimeoutProperties(
        Duration acceptTimeout,
        Duration previewTimeout,
        Duration approvalTimeout,
        Duration executeTimeout,
        Duration reconciliationGraceTimeout
) {

    public ApplicationActionTimeoutProperties {
        requirePositive(acceptTimeout, "acceptTimeout");
        requirePositive(previewTimeout, "previewTimeout");
        requirePositive(approvalTimeout, "approvalTimeout");
        requirePositive(executeTimeout, "executeTimeout");
        requirePositive(reconciliationGraceTimeout, "reconciliationGraceTimeout");
    }

    @Autowired
    public ApplicationActionTimeoutProperties(BusinessDesktopModeProperties properties) {
        this(
                properties.acceptTimeout(),
                properties.previewTimeout(),
                properties.approvalTimeout(),
                properties.executeTimeout(),
                properties.reconciliationGraceTimeout());
    }

    private static void requirePositive(Duration value, String name) {
        if (value == null || value.isZero() || value.isNegative()) {
            throw new IllegalArgumentException(name + " must be positive");
        }
    }
}
