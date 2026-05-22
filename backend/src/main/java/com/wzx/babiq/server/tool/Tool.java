package com.wzx.babiq.server.tool;

/**
 * 工具标记接口。
 *
 * <p>所有可被 Agent 调用的工具都实现它，ToolRegistry 通过 Spring 注入收集。</p>
 */
public interface Tool {

    /**
     * 工具名称，必须与 @Tool 注解和拦截器白名单保持一致。
     *
     * @return 工具名
     */
    String name();
}
