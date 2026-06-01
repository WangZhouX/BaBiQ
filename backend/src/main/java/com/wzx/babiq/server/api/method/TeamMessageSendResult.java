package com.wzx.babiq.server.api.method;

import com.wzx.babiq.server.conversation.items.TeamMessageItem;

/**
 * team/message/send 的返回对象。
 *
 * @param item 已写入团队时间线并可直接交给桌面端 reducer 的 teamMessage item
 */
public record TeamMessageSendResult(TeamMessageItem item) {
}
