package com.wzx.huitai.desktop.runtime

import java.nio.file.Files

/** 测试可捕获 ProcessBuilder；生产默认直接启动参数数组对应的子进程。 */
fun interface BusinessAgentProcessStarter {
    fun start(builder: ProcessBuilder): Process
}

/**
 * 启动安装包内 Agent jar，并把进程责任转移给 [BusinessAgentRuntimeSession]。
 *
 * 任何启动/认证失败都会先终止 child，再删除未消费 token 并擦除密码；成功后还要求 token 文件
 * 已由后端单次消费，防止误接入未执行业务认证 profile 的进程。
 */
class BusinessAgentProcessLauncher(
    private val processStarter: BusinessAgentProcessStarter = BusinessAgentProcessStarter { it.start() },
    private val readinessProbe: BusinessAgentReadinessProbe,
) {
    /** 以参数数组启动子进程，认证就绪后返回可幂等关闭的会话。 */
    suspend fun launch(request: BusinessAgentLaunchRequest): BusinessAgentRuntimeSession {
        var process: Process? = null
        try {
            val builder = ProcessBuilder(request.command())
                .redirectErrorStream(true)
                .redirectOutput(ProcessBuilder.Redirect.appendTo(request.paths.agentLog.toFile()))
            builder.environment().putAll(request.environment())
            process = processStarter.start(builder)
            builder.environment().remove(BusinessAgentLaunchRequest.BACKEND_KEYSTORE_PASSWORD_ENV)
            readinessProbe.await(process, request.connectRequest)
            check(Files.notExists(request.paths.agentSessionToken)) {
                "business Agent did not consume the one-shot session token"
            }
            return BusinessAgentRuntimeSession(process, request)
        } catch (failure: Throwable) {
            process?.let(BusinessAgentRuntimeSession::terminateProcess)
            request.close()
            throw failure
        }
    }
}
