package com.wzx.babiq.server.api.method;

import com.fasterxml.jackson.databind.JsonNode;
import com.wzx.babiq.server.api.JsonRpcMethodHandler;
import com.wzx.babiq.server.api.dto.RunRecoveryStatusResult;
import com.wzx.babiq.server.recovery.RecoveryReport;
import com.wzx.babiq.server.recovery.TurnRecoveryService;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.WebSocketSession;

/**
 * run/recovery/status 方法处理器。
 *
 * <p>该接口把最近一次启动恢复报告暴露给桌面端，帮助用户理解为什么某些历史 turn
 * 被标记为 interrupted 或 expired。</p>
 */
@Component
public class RunRecoveryStatusHandler implements JsonRpcMethodHandler {

    /** 恢复服务，持有最近一次恢复报告。 */
    private final TurnRecoveryService recoveryService;

    /**
     * 创建 run/recovery/status handler。
     *
     * @param recoveryService turn 恢复服务
     */
    public RunRecoveryStatusHandler(TurnRecoveryService recoveryService) {
        this.recoveryService = recoveryService;
    }

    @Override
    public String method() {
        return "run/recovery/status";
    }

    @Override
    public Object handle(JsonNode params, WebSocketSession session) {
        RecoveryReport report = recoveryService.lastReport();
        return new RunRecoveryStatusResult(
                report.lastRecoveredAt() == null ? null : report.lastRecoveredAt().toString(),
                report.interruptedTurns(),
                report.expiredTurns(),
                report.expiredApprovals());
    }
}
