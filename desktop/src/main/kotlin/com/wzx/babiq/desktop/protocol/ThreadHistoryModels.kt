package com.wzx.babiq.desktop.protocol

import kotlinx.serialization.Serializable

/**
 * thread/list 响应模型。
 *
 * @property threads 后端按更新时间倒序返回的最近会话摘要。
 * @property nextCursor 下一页游标；P2-2 后端先返回 null，字段保留给未来分页。
 */
@Serializable
data class ThreadListResult(
	val threads: List<ThreadSummaryInfo> = emptyList(),
	val nextCursor: String? = null,
)

/**
 * 最近会话摘要。
 *
 * @property threadId 后端会话 id，点击侧边栏时用它调用 thread/load。
 * @property title 会话标题，当前由后端生成。
 * @property cwd 会话绑定的工作目录。
 * @property providerId 会话默认 Provider 快照，可为空。
 * @property model 会话默认模型快照，可为空。
 * @property status 会话状态，例如 active 或 archived。
 * @property lastTurnStatus 最近一轮 turn 状态；没有 turn 时为空。
 * @property updatedAt 后端 ISO 时间字符串，UI 暂时原样展示或做轻量裁剪。
 * @property messageCount 当前会话保存的 item 数量。
 */
@Serializable
data class ThreadSummaryInfo(
	val threadId: String,
	val title: String,
	val cwd: String,
	val providerId: String? = null,
	val model: String? = null,
	val status: String = "active",
	val lastTurnStatus: String? = null,
	val updatedAt: String,
	val messageCount: Long = 0,
)

/**
 * thread/load 响应里的会话元信息。
 *
 * @property threadId 会话 id。
 * @property title 会话标题。
 * @property cwd 会话绑定的工作目录。
 * @property status 会话状态。
 */
@Serializable
data class ThreadMetaInfo(
	val threadId: String,
	val title: String,
	val cwd: String,
	val status: String,
)

/**
 * thread/load 响应模型。
 *
 * <p>items 复用 ThreadItem，因此历史恢复和实时事件走同一套 reducer 转换逻辑。</p>
 *
 * @property thread 会话元信息。
 * @property items 按 sequence_no 正序返回的历史 item。
 * @property latestSummary 当前页最新 turnSummary，可能为空。
 * @property nextBeforeItemId 加载更早历史时使用的游标。
 */
@Serializable
data class ThreadLoadResult(
	val thread: ThreadMetaInfo,
	val items: List<ThreadItem> = emptyList(),
	val latestSummary: ThreadItem.TurnSummary? = null,
	val nextBeforeItemId: String? = null,
)

/**
 * thread/archive 响应模型。
 *
 * @property ok true 表示后端已接受归档请求。
 * @property threadId 被归档的会话 id。
 * @property archived true 表示该会话已进入软归档状态。
 */
@Serializable
data class ThreadArchiveResult(
	val ok: Boolean = false,
	val threadId: String,
	val archived: Boolean = false,
)
