package com.wzx.huitai.demo.model

import com.wzx.huitai.presentation.form.FormPatch

/** 通用演示页面允许 reducer 处理的强类型事件。 */
sealed interface DemoFormEvent {
    /** 用户编辑单个字段。 */
    data class EditField(val fieldId: String, val value: String) : DemoFormEvent

    /** 安装一个只读生成、绑定当前版本的建议补丁。 */
    data class SuggestPatch(val patch: FormPatch) : DemoFormEvent

    /** 接受待处理建议中的单个字段。 */
    data class AcceptSuggestion(val fieldId: String) : DemoFormEvent

    /** 接受同一个建议补丁内的全部字段。 */
    data object AcceptAllSuggestions : DemoFormEvent

    /** 应用已经通过动作边界解码的表单补丁。 */
    data class ApplyPatch(val patch: FormPatch) : DemoFormEvent

    /** 导航到通用演示路由。 */
    data class Navigate(val route: String) : DemoFormEvent
}
