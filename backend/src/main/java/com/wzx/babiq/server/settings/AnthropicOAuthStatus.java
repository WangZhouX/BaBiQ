package com.wzx.babiq.server.settings;

/**
 * Anthropic OAuth CLI 状态。
 *
 * @param cliInstalled 是否能运行 ant CLI
 * @param loggedIn 是否能读取 OAuth access token
 * @param message 用户可读说明
 */
public record AnthropicOAuthStatus(boolean cliInstalled, boolean loggedIn, String message) {
}
