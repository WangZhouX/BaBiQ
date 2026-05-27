package com.wzx.babiq.server.api.method;

import com.fasterxml.jackson.databind.JsonNode;
import com.wzx.babiq.server.api.JsonRpcMethodHandler;
import com.wzx.babiq.server.skill.SkillCatalogService;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.WebSocketSession;

/**
 * skills/list JSON-RPC handler。
 */
@Component
public class SkillsListHandler implements JsonRpcMethodHandler {

    /** Skill 应用服务。 */
    private final SkillCatalogService service;

    public SkillsListHandler(SkillCatalogService service) {
        this.service = service;
    }

    @Override
    public String method() {
        return "skills/list";
    }

    @Override
    public Object handle(JsonNode params, WebSocketSession session) {
        return service.list();
    }
}
