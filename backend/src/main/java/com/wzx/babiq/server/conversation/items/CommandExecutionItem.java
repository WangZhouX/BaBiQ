package com.wzx.babiq.server.conversation.items;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * 命令执行 item。
 *
 * <p>该类型描述 shell 命令的生命周期。P1-1 不真正执行命令,但先定义字段可以
 * 让协议层和桌面端提前对齐审批、输出和退出码展示格式。</p>
 *
 * @param id item 标识
 * @param type 固定为 commandExecution
 * @param command 待执行或已执行命令
 * @param status 命令状态
 * @param exitCode 进程退出码
 * @param stdout 标准输出
 * @param stderr 标准错误
 * @param durationMs 执行耗时毫秒数
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record CommandExecutionItem(
        @JsonProperty(required = true) String id,
        @JsonProperty(required = true) String type,
        @JsonProperty(required = true) String command,
        @JsonProperty(required = true) String status,
        Integer exitCode,
        String stdout,
        String stderr,
        Long durationMs
) implements ThreadItem {
}
