package com.wzx.babiq.desktop.protocol

import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.buildClassSerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject

/**
 * ThreadItem 镜像后端的 Thread / Turn / Item 协议模型。
 *
 * sealed interface 的好处是：when 分支处理 item 时，编译器能提醒我们是否漏掉已知类型；
 * Unknown 则保留未来协议字段，避免后端新增 item 时桌面端直接崩溃。
 */
@Serializable(with = ThreadItemSerializer::class)
sealed interface ThreadItem {
	val id: String
	val type: String

	@Serializable
	/**
	 * 用户消息协议 item。
	 *
	 * @property id 后端生成的 item id。
	 * @property type 协议类型固定为 userMessage。
	 * @property text 用户输入文本。
	 */
	data class UserMessage(
		override val id: String,
		override val type: String = "userMessage",
		val text: String,
	) : ThreadItem

	@Serializable
	/**
	 * Agent 文本协议 item。
	 *
	 * @property id 后端生成的 item id。
	 * @property type 协议类型固定为 agentMessage。
	 * @property text 完整助手回复，通常在 turn 完成时出现。
	 * @property textDelta 流式增量文本，UI 会追加到正在展示的助手消息里。
	 */
	data class AgentMessage(
		override val id: String,
		override val type: String = "agentMessage",
		val text: String? = null,
		val textDelta: String? = null,
	) : ThreadItem

	@Serializable
	/**
	 * 推理过程协议 item。
	 *
	 * @property id 后端生成的 item id。
	 * @property type 协议类型固定为 reasoning。
	 * @property text 后端暴露的推理/计划摘要文本。
	 */
	data class Reasoning(
		override val id: String,
		override val type: String = "reasoning",
		val text: String,
	) : ThreadItem

	@Serializable
	/**
	 * 命令执行协议 item。
	 *
	 * @property id 后端生成的 item id。
	 * @property type 协议类型固定为 commandExecution。
	 * @property command 实际执行的 shell 命令。
	 * @property status 命令状态，例如 running/completed/failed。
	 * @property exitCode 进程退出码，仍在运行时为空。
	 * @property stdout 标准输出摘要。
	 * @property stderr 标准错误摘要。
	 * @property durationMs 命令耗时，单位毫秒。
	 */
	data class CommandExecution(
		override val id: String,
		override val type: String = "commandExecution",
		val command: String,
		val status: String,
		val exitCode: Int? = null,
		val stdout: String? = null,
		val stderr: String? = null,
		val durationMs: Long? = null,
	) : ThreadItem

	@Serializable
	/**
	 * 文件变更协议 item。
	 *
	 * @property id 后端生成的 item id。
	 * @property type 协议类型固定为 fileChange。
	 * @property action 文件动作，例如 read/write/patch。
	 * @property path 文件路径。
	 * @property status 动作状态。
	 * @property contentPreview 可选内容预览。
	 */
	data class FileChange(
		override val id: String,
		override val type: String = "fileChange",
		val action: String,
		val path: String,
		val status: String,
		val contentPreview: String? = null,
	) : ThreadItem

	@Serializable
	/**
	 * turn 结束摘要协议 item。
	 *
	 * @property id 后端生成的 item id。
	 * @property type 协议类型固定为 turnSummary。
	 * @property status turn 结束状态。
	 * @property model 本轮实际使用的模型名。
	 * @property promptTokens 输入 token 数。
	 * @property completionTokens 输出 token 数。
	 * @property totalTokens 输入和输出 token 总数。
	 * @property toolCalls 本轮工具调用次数。
	 * @property durationMs 本轮耗时，单位毫秒。
	 */
	data class TurnSummary(
		override val id: String,
		override val type: String = "turnSummary",
		val status: String,
		val model: String,
		val promptTokens: Long = 0,
		val completionTokens: Long = 0,
		val totalTokens: Long = 0,
		val toolCalls: Int = 0,
		val durationMs: Long = 0,
	) : ThreadItem

	@Serializable
	/**
	 * 上下文压缩事件协议 item。
	 *
	 * @property id 后端生成的 item id。
	 * @property type 协议类型固定为 contextCompaction。
	 * @property compactionId 压缩审计记录 id。
	 * @property status 压缩状态，例如 SUCCESS、SKIPPED、FAILED。
	 * @property summaryId 成功时安装的短期摘要 id。
	 * @property windowOrdinal 压缩成功后的窗口序号。
	 * @property estimatedTokensBefore 压缩前上下文预估 token。
	 * @property estimatedTokensAfter 摘要预估 token。
	 * @property message 后端给 UI 的简短说明。
	 */
	data class ContextCompaction(
		override val id: String,
		override val type: String = "contextCompaction",
		val compactionId: String? = null,
		val status: String? = null,
		val summaryId: String? = null,
		val windowOrdinal: Int? = null,
		val estimatedTokensBefore: Int? = null,
		val estimatedTokensAfter: Int? = null,
		val message: String? = null,
	) : ThreadItem

	@Serializable
	/**
	 * 子 Agent 委派协议 item。
	 *
	 * 后端在主 Agent 调用 explorer 等子 Agent 时推送这个 item：它不是普通工具输出，
	 * 而是一段“父 Agent -> 子 Agent”的执行轨迹摘要。桌面端既会把它渲染成聊天流中的折叠卡片，
	 * 也会放进右侧运行面板，帮助用户区分主 Agent 思考、子 Agent 探索和底层只读工具调用。
	 *
	 * @property id 后端生成并在同一次委派中复用的 item id。
	 * @property type 协议类型固定为 agentDelegation。
	 * @property delegationId 后端生成的委派业务 id，用于和工具调用审计记录关联。
	 * @property parentAgent 父 Agent 名称，当前通常是 babiq_agent。
	 * @property childAgent 子 Agent 名称，P6-1 首个内置值是 explorer。
	 * @property status 委派状态，常见值为 running/completed/failed。
	 * @property mode 委派安全模式，P6-1 使用 READ_ONLY_TOOL 表示只读工具子 Agent。
	 * @property summary 给用户看的短摘要，由后端在开始、工具调用、完成或失败时更新。
	 * @property toolCallCount 子 Agent 内部已完成的工具调用次数；为空表示后端暂未统计。
	 * @property tokenEstimate 子 Agent 期间新增 token 估算；为空表示本轮还没有用量信息。
	 */
	data class AgentDelegation(
		override val id: String,
		override val type: String = "agentDelegation",
		val delegationId: String,
		val parentAgent: String,
		val childAgent: String,
		val status: String,
		val mode: String,
		val summary: String? = null,
		val toolCallCount: Int? = null,
		val tokenEstimate: Int? = null,
	) : ThreadItem

	@Serializable
	/**
	 * 斜杠命令创建的工作容器协议 item。
	 *
	 * 工作容器只表示“一个可复用的编排/团队任务槽”，不代表已经开始执行。
	 * 它必须放在右侧运行详情里，避免把 `/编排`、`/团队` 这类控制语法混入主聊天历史。
	 */
	data class WorkUnit(
		override val id: String,
		override val type: String = "workUnit",
		val workUnitId: String,
		val kind: String,
		val name: String,
		val status: String,
		val currentGoalId: String? = null,
		val currentGoal: String? = null,
		val goalCount: Int = 0,
		val removed: Boolean = false,
	) : ThreadItem

	@Serializable
	/**
	 * 多 Agent 流程编排协议 item。
	 *
	 * P6-2 后端用它把 Sequential/Parallel/Routing 流程的整体状态和节点状态推给桌面端。
	 * 它是运行详情状态，不进入主聊天正文；主聊天只保留父 Agent 最终结论，避免流程节点中间输出污染对话历史。
	 *
	 * @property id 后端生成并在同一流程运行中复用的 item id。
	 * @property type 协议类型固定为 orchestration。
	 * @property orchestrationId 流程运行 id，用于关联后端 `bq_orchestrations` 审计记录。
	 * @property title 用户可读流程标题，通常来自整体任务。
	 * @property topology 流程拓扑，值为 sequential、parallel 或 routing。
	 * @property status 流程状态，常见值为 running、completed、failed。
	 * @property summary 流程整体短摘要或失败原因。
	 * @property approved true 表示流程已通过运行前整体审批。
	 * @property frozen true 表示拓扑、节点、工具和写入范围已冻结，后续执行不能改规格。
	 * @property nodes 当前节点状态列表，按后端稳定顺序展示。
	 */
	data class Orchestration(
		override val id: String,
		override val type: String = "orchestration",
		val orchestrationId: String,
		val title: String,
		val topology: String,
		val status: String,
		val summary: String? = null,
		val approved: Boolean? = null,
		val frozen: Boolean? = null,
		val structureJson: String? = null,
		val nodes: List<OrchestrationNode> = emptyList(),
	) : ThreadItem

	@Serializable
	/**
	 * 流程编排中的单个节点状态。
	 *
	 * @property nodeId 节点协议 id，用于稳定渲染列表。
	 * @property name 节点 ASCII 技术名，和后端 Agent name 对应。
	 * @property displayName 节点展示名，优先使用中文。
	 * @property status 节点状态：pending、running、completed、failed。
	 * @property mode 节点安全模式：READ_ONLY_TOOL 或 WORKSPACE_TOOL。
	 * @property task 节点任务描述。
	 * @property model 节点模型名；为空表示继承父 Agent。
	 * @property toolCallCount 节点聚合工具调用次数。
	 * @property tokenEstimate 节点 token 粗估。
	 * @property summary 节点短摘要。
	 */
	data class OrchestrationNode(
		val nodeId: String,
		val name: String,
		val displayName: String? = null,
		val status: String,
		val mode: String,
		val task: String? = null,
		val model: String? = null,
		val toolCallCount: Int? = null,
		val tokenEstimate: Int? = null,
		val summary: String? = null,
	)

	@Serializable
	/**
	 * 团队协作整体状态协议 item。
	 *
	 * P6-3 的 coordinate_team 工具会推送这个 item，它描述 supervisor 图里的团队整体状态。
	 * 桌面端把它放进右侧运行详情，不进入聊天正文；这样用户能看到团队成员和轮次，
	 * 但不会把子成员的中间消息混入主对话历史。
	 *
	 * @property id 后端生成并在同一团队运行中复用的 item id。
	 * @property type 协议类型固定为 team。
	 * @property teamId 团队运行 id，用于 direct message 和持久化审计关联。
	 * @property title 用户可读标题，通常来自 coordinate_team 的任务摘要。
	 * @property status 团队状态：pending、running、completed、failed。
	 * @property summary 团队整体短摘要；为空表示还没有可展示结论。
	 * @property approved true 表示团队规格已经通过运行前整体审批。
	 * @property frozen true 表示成员、工具和写入范围已冻结，执行期不能再改。
	 * @property currentAgent 当前或最近一次被 supervisor 调度的成员。
	 * @property round 当前 supervisor 调度轮数。
	 * @property maxRounds 本次团队运行允许的最大调度轮数。
	 * @property members 成员聚合状态列表，按后端稳定顺序展示。
	 */
	data class Team(
		override val id: String,
		override val type: String = "team",
		val teamId: String,
		val title: String,
		val status: String,
		val summary: String? = null,
		val approved: Boolean? = null,
		val frozen: Boolean? = null,
		val currentAgent: String? = null,
		val round: Int? = null,
		val maxRounds: Int? = null,
		val members: List<TeamMember> = emptyList(),
	) : ThreadItem

	@Serializable
	/**
	 * 团队协作成员聚合状态。
	 *
	 * @property memberId 成员协议 id，用于稳定列表 key。
	 * @property name 成员 ASCII 技术名，和后端 Agent name 对应。
	 * @property displayName 中文展示名；为空时 UI 回退到 name。
	 * @property status 成员状态：pending、running、completed、failed。
	 * @property mode 成员工具权限模式，例如 READ_ONLY_TOOL 或 WORKSPACE_TOOL。
	 * @property task 分配给该成员的任务描述。
	 * @property toolCallCount 成员已完成工具调用次数；为空表示后端暂未统计。
	 * @property tokenEstimate 成员 token 粗估；为空表示暂未统计。
	 * @property summary 成员短摘要。
	 */
	data class TeamMember(
		val memberId: String,
		val name: String,
		val displayName: String? = null,
		val status: String,
		val mode: String,
		val task: String? = null,
		val toolCallCount: Int? = null,
		val tokenEstimate: Int? = null,
		val summary: String? = null,
	)

	@Serializable
	/**
	 * 团队协作消息时间线协议 item。
	 *
	 * 它展示 supervisor 路由、成员摘要和用户直发 teammate 的消息。和 Team 一样，
	 * 它只属于运行详情，不会进入主聊天正文或下一轮上下文。
	 *
	 * @property id 后端生成的协议 item id。
	 * @property type 协议类型固定为 teamMessage。
	 * @property messageId 团队消息 id，用于幂等 upsert。
	 * @property teamId 所属团队运行 id。
	 * @property fromAgent 发送方：user、supervisor 或成员名。
	 * @property toAgent 接收方：supervisor、成员名或 all。
	 * @property messageType 消息类型：route、member_summary、direct_user、system。
	 * @property content 消息正文或短摘要。
	 * @property round 所属调度轮数；直发消息可为空。
	 * @property createdAt 创建时间，ISO-8601 文本。
	 */
	data class TeamMessage(
		override val id: String,
		override val type: String = "teamMessage",
		val messageId: String,
		val teamId: String,
		val fromAgent: String,
		val toAgent: String,
		val messageType: String,
		val content: String,
		val round: Int? = null,
		val createdAt: String? = null,
	) : ThreadItem

	@Serializable
	/**
	 * 计划可视化协议 item。
	 *
	 * 后端 `update_plan` 工具会用完整覆盖语义反复推送同一个 item：第一次是 `item/added`，
	 * 后续更新是 `item/updated`。桌面端只把它放入右侧运行面板，不渲染成聊天气泡，避免计划状态污染对话正文。
	 *
	 * @property id 后端生成并在同一 turn 内复用的计划 item id。
	 * @property type 协议类型固定为 plan。
	 * @property goal 可选目标说明；为空表示模型只提供了步骤，没有额外目标标题。
	 * @property steps 当前完整步骤列表；每次更新都会替换上一版步骤。
	 * @property reasoning 可选的计划说明文本，用于右侧面板解释为什么这么拆分。
	 */
	data class Plan(
		override val id: String,
		override val type: String = "plan",
		val goal: String? = null,
		val steps: List<PlanStep> = emptyList(),
		val reasoning: String? = null,
	) : ThreadItem

	@Serializable
	/**
	 * 计划步骤协议模型。
	 *
	 * @property order 后端给出的展示顺序，从 1 开始；UI 仍按列表顺序渲染，order 仅用于辅助展示和调试。
	 * @property description 稳定的步骤描述，适合在 pending/completed 状态展示。
	 * @property status 步骤状态：pending、in_progress 或 completed；未知值会在 UI 层按 pending 处理。
	 * @property activeForm in_progress 状态下的动态文案，例如“正在运行测试”，用于比静态描述更贴近当前动作。
	 */
	data class PlanStep(
		val order: Int,
		val description: String,
		val status: String,
		val activeForm: String? = null,
	)

	/**
	 * 未知协议 item。
	 *
	 * @property id 尽量从 raw 中读取的 item id。
	 * @property type 后端传来的未知类型。
	 * @property raw 完整原始 JSON，便于协议升级时排查。
	 */
	data class Unknown(
		override val id: String,
		override val type: String,
		val raw: JsonObject,
	) : ThreadItem
}

/**
 * 后端 item 用 type 字段区分具体形态，kotlinx.serialization 默认不会自动按这个字段分派。
 * 因此这里写一个很薄的自定义 serializer：只读一次原始 JsonObject，再根据 type 选择目标 data class。
 */
object ThreadItemSerializer : KSerializer<ThreadItem> {
	override val descriptor: SerialDescriptor = buildClassSerialDescriptor("ThreadItem")

	override fun deserialize(decoder: Decoder): ThreadItem {
		val jsonDecoder = decoder as? JsonDecoder
			?: throw SerializationException("ThreadItem 只能从 JSON 解码")
		val raw = jsonDecoder.decodeJsonElement().jsonObject
		return when (val type = raw.requiredText("type")) {
			// 已知 P1 类型转成强类型对象，UI 和 reducer 不需要手写 JsonObject 取字段。
			"userMessage" -> jsonDecoder.json.decodeFromJsonElement(ThreadItem.UserMessage.serializer(), raw)
			"agentMessage" -> jsonDecoder.json.decodeFromJsonElement(ThreadItem.AgentMessage.serializer(), raw)
			"reasoning" -> jsonDecoder.json.decodeFromJsonElement(ThreadItem.Reasoning.serializer(), raw)
			"commandExecution" -> jsonDecoder.json.decodeFromJsonElement(ThreadItem.CommandExecution.serializer(), raw)
			"fileChange" -> jsonDecoder.json.decodeFromJsonElement(ThreadItem.FileChange.serializer(), raw)
			"turnSummary" -> jsonDecoder.json.decodeFromJsonElement(ThreadItem.TurnSummary.serializer(), raw)
			"contextCompaction" -> jsonDecoder.json.decodeFromJsonElement(ThreadItem.ContextCompaction.serializer(), raw)
			"agentDelegation" -> jsonDecoder.json.decodeFromJsonElement(ThreadItem.AgentDelegation.serializer(), raw)
			"workUnit" -> jsonDecoder.json.decodeFromJsonElement(ThreadItem.WorkUnit.serializer(), raw)
			"orchestration" -> jsonDecoder.json.decodeFromJsonElement(ThreadItem.Orchestration.serializer(), raw)
			"team" -> jsonDecoder.json.decodeFromJsonElement(ThreadItem.Team.serializer(), raw)
			"teamMessage" -> jsonDecoder.json.decodeFromJsonElement(ThreadItem.TeamMessage.serializer(), raw)
			"plan" -> jsonDecoder.json.decodeFromJsonElement(ThreadItem.Plan.serializer(), raw)
			// 未知类型不丢弃，交给运行详情面板展示 raw JSON，方便后续协议扩展排查。
			else -> ThreadItem.Unknown(
				id = raw.optionalText("id") ?: "unknown",
				type = type,
				raw = raw,
			)
		}
	}

	override fun serialize(encoder: Encoder, value: ThreadItem) {
		throw SerializationException("桌面端当前只需要解码 ThreadItem")
	}
}
