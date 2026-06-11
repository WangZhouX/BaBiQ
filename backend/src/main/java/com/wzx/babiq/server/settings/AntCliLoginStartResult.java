package com.wzx.babiq.server.settings;

/**
 * ant auth login 启动结果。
 *
 * @param ok 是否成功启动
 * @param pid 子进程 PID，启动失败时为空
 * @param message 用户可读说明
 */
public record AntCliLoginStartResult(boolean ok, Long pid, String message) {
}
