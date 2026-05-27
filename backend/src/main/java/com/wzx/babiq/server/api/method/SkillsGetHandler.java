package com.wzx.babiq.server.api.method;

import com.fasterxml.jackson.databind.JsonNode;
import com.wzx.babiq.server.api.JsonRpcMethodHandler;
import com.wzx.babiq.server.api.error.JsonRpcErrorCode;
import com.wzx.babiq.server.api.error.JsonRpcException;
import com.wzx.babiq.server.skill.SkillCatalogService;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.WebSocketSession;

/**
 * skills/get JSON-RPC handler。
 *
 * <p>按 id 显式读取 Skill 正文；读取失败映射为 INVALID_PARAMS，避免桌面端看到模糊内部异常。</p>
 */
@Component
public class SkillsGetHandler implements JsonRpcMethodHandler {

    /** Skill 应用服务。 */
    private final SkillCatalogService service;

    public SkillsGetHandler(SkillCatalogService service) {
        this.service = service;
    }

    @Override
    public String method() {
        return "skills/get";
    }

    @Override
    public Object handle(JsonNode params, WebSocketSession session) {
        String skillId = ContextStatusHandler.requiredText(params, "skillId");
        try {
            return service.get(skillId);
        } catch (IllegalArgumentException exception) {
            throw new JsonRpcException(JsonRpcErrorCode.INVALID_PARAMS, exception.getMessage());
        }
    }
}
