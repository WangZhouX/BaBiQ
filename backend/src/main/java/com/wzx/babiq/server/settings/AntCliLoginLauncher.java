package com.wzx.babiq.server.settings;

/**
 * 启动交互式 ant auth login 的边界。
 */
public interface AntCliLoginLauncher {

    /**
     * 启动登录流程。
     */
    AntCliLoginStartResult startLogin();
}
