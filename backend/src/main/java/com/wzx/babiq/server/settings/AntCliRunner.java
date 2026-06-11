package com.wzx.babiq.server.settings;

import java.util.List;

/**
 * 执行短生命周期 ant CLI 命令的边界。
 */
public interface AntCliRunner {

    /**
     * 运行 ant CLI 命令。
     *
     * @param arguments 不含可执行文件名的参数
     * @return 子进程结果
     */
    AntCliResult run(List<String> arguments);
}
