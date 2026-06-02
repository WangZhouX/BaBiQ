package com.wzx.babiq.server.workunit;

import com.wzx.babiq.server.observability.TurnObservationContext;
import org.springframework.ai.chat.model.ToolContext;

/**
 * WorkUnit 在 Spring AI ToolContext 中使用的上下文键。
 */
public final class WorkUnitContextKeys {

    public static final String GOAL_ID = "babiq.workUnit.goalId";

    private WorkUnitContextKeys() {
    }

    public static String goalId(ToolContext toolContext) {
        Object value = toolContext == null ? null : toolContext.getContext().get(GOAL_ID);
        if (value instanceof String text && !text.isBlank()) {
            return text;
        }
        Object observation = toolContext == null ? null : toolContext.getContext().get(TurnObservationContext.METADATA_KEY);
        if (observation instanceof TurnObservationContext context) {
            return context.workUnitGoalId();
        }
        return null;
    }
}
