package com.wzx.huitai.action

import com.wzx.huitai.action.model.ActionError
import com.wzx.huitai.action.model.ActionErrorCode

/** 注册表查询结果。 */
sealed interface ActionResolution {
    data class Found(val action: RegisteredAction<*, *>) : ActionResolution
    data class NotFound(val error: ActionError) : ActionResolution
}

/** 按动作标识和版本维护强类型注册项。 */
class ActionRegistry {
    private val actions = linkedMapOf<ActionKey, RegisteredAction<*, *>>()

    /**
     * 注册一个动作；相同标识和版本重复时在启动阶段立即失败。
     *
     * @param action 已绑定 codec 的动作注册项。
     */
    fun register(action: RegisteredAction<*, *>) {
        val key = ActionKey(action.descriptor.id, action.descriptor.version)
        check(key !in actions) { "动作重复注册: ${key.actionId}@${key.version}" }
        actions[key] = action
    }

    /**
     * 查询指定动作；未指定版本时返回已注册的最高版本。
     *
     * @param actionId 动作稳定标识。
     * @param version 可选精确版本。
     */
    fun resolve(actionId: String, version: Int? = null): ActionResolution {
        val action = if (version == null) {
            actions.asSequence()
                .filter { (key, _) -> key.actionId == actionId }
                .maxByOrNull { (key, _) -> key.version }
                ?.value
        } else {
            actions[ActionKey(actionId, version)]
        }
        return action?.let(ActionResolution::Found) ?: ActionResolution.NotFound(
            ActionError(ActionErrorCode.ACTION_NOT_FOUND, "动作不存在: $actionId"),
        )
    }

    private data class ActionKey(val actionId: String, val version: Int)
}
