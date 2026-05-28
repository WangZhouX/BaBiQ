package com.wzx.babiq.server.api.method;

import com.fasterxml.jackson.databind.JsonNode;
import com.wzx.babiq.server.api.JsonRpcMethodHandler;
import com.wzx.babiq.server.api.dto.MemoryScanResult;
import com.wzx.babiq.server.memory.LongTermMemoryPipeline;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.WebSocketSession;

/**
 * JSON-RPC `memory/scan` 处理器。
 *
 * <p>设置页“立即扫描”使用这个入口手动触发 Phase1 idle 扫描；它只负责发现并入队候选任务，
 * 不在请求线程里同步执行模型抽取，避免桌面端按钮阻塞太久。</p>
 */
@Component
public class MemoryScanHandler implements JsonRpcMethodHandler {

    /** 长期记忆后台流水线，内部负责 idle 判断、去重和任务入队。 */
    private final LongTermMemoryPipeline pipeline;

    /**
     * 创建手动扫描处理器。
     */
    public MemoryScanHandler(LongTermMemoryPipeline pipeline) {
        this.pipeline = pipeline;
    }

    @Override
    public String method() {
        return "memory/scan";
    }

    @Override
    public Object handle(JsonNode params, WebSocketSession session) {
        int queued = pipeline.scanPhase1();
        return new MemoryScanResult(queued, queued > 0 ? "QUEUED" : "NO_IDLE_THREAD");
    }
}
