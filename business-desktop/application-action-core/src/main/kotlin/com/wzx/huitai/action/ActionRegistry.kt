package com.wzx.huitai.action

import com.wzx.huitai.action.model.ActionError
import com.wzx.huitai.action.model.ActionErrorCode

/** 注册表查询结果。 */
sealed interface ActionResolution {
    data class Found(val action: RegisteredAction<*, *>) : ActionResolution
    data class NotFound(val error: ActionError) : ActionResolution
}

/**
 * 按动作标识和版本维护强类型注册项。
 *
 * composition root 必须先完成全部注册，再调用 [freeze]，之后才能把注册表共享给并发执行路径。
 * freeze 前的 [resolve] 仅用于启动期串行装配和校验，不提供并发安全保证。
 */
class ActionRegistry {
    private val actions = linkedMapOf<ActionKey, RegisteredAction<*, *>>()
    @Volatile
    private var frozenSnapshot: Map<ActionKey, RegisteredAction<*, *>>? = null

    val isFrozen: Boolean
        get() = frozenSnapshot != null

    /**
     * 注册一个动作；相同标识和版本重复时在启动阶段立即失败。
     *
     * @param action 已绑定 codec 的动作注册项。
     */
    fun register(action: RegisteredAction<*, *>) {
        val key = ActionKey(action.descriptor.id, action.descriptor.version)
        check(!isFrozen) { "动作注册表已冻结，不能继续注册: ${key.actionId}@${key.version}" }
        check(key !in actions) { "动作重复注册: ${key.actionId}@${key.version}" }
        actions[key] = action
    }

    /** 发布不可变查找快照；重复冻结保持同一快照。 */
    fun freeze() {
        if (frozenSnapshot == null) {
            frozenSnapshot = actions.toMap()
        }
    }

    /**
     * 查询指定动作；未指定版本时返回已注册的最高版本。
     *
     * @param actionId 动作稳定标识。
     * @param version 可选精确版本。
     */
    fun resolve(actionId: String, version: Int? = null): ActionResolution {
        val lookup = frozenSnapshot ?: actions
        val action = if (version == null) {
            lookup.asSequence()
                .filter { (key, _) -> key.actionId == actionId }
                .maxByOrNull { (key, _) -> key.version }
                ?.value
        } else {
            lookup[ActionKey(actionId, version)]
        }
        return action?.let(ActionResolution::Found) ?: ActionResolution.NotFound(
            ActionError(ActionErrorCode.ACTION_NOT_FOUND, "动作不存在: $actionId"),
        )
    }

    private data class ActionKey(val actionId: String, val version: Int)
}
