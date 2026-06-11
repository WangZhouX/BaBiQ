package com.wzx.babiq.server.settings;

/**
 * ant CLI 子进程执行结果。
 *
 * @param exitCode 进程退出码
 * @param stdout 标准输出
 * @param stderr 标准错误
 */
public record AntCliResult(int exitCode, String stdout, String stderr) {
}
