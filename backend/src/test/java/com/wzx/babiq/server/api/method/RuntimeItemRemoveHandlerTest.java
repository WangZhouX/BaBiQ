package com.wzx.babiq.server.api.method;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.wzx.babiq.server.api.dto.RuntimeItemRemoveResult;
import com.wzx.babiq.server.api.error.JsonRpcErrorCode;
import com.wzx.babiq.server.api.error.JsonRpcException;
import com.wzx.babiq.server.conversation.ConversationApplicationService;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RuntimeItemRemoveHandlerTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void handle_should_soft_remove_runtime_item() {
        ConversationApplicationService service = mock(ConversationApplicationService.class);
        when(service.removeRuntimeItem("it_team_1", "team"))
                .thenReturn(new RuntimeItemRemoveResult("it_team_1", "team", "removed", true));

        Object result = new RuntimeItemRemoveHandler(service)
                .handle(objectMapper.valueToTree(Map.of("itemId", "it_team_1", "type", "team")), null);

        assertThat(result).isEqualTo(new RuntimeItemRemoveResult("it_team_1", "team", "removed", true));
        verify(service).removeRuntimeItem("it_team_1", "team");
    }

    @Test
    void missing_item_id_should_throw_invalid_params() {
        RuntimeItemRemoveHandler handler = new RuntimeItemRemoveHandler(mock(ConversationApplicationService.class));

        assertThatThrownBy(() -> handler.handle(objectMapper.valueToTree(Map.of("type", "team")), null))
                .isInstanceOfSatisfying(JsonRpcException.class, exception ->
                        assertThat(exception.errorCode()).isEqualTo(JsonRpcErrorCode.INVALID_PARAMS));
    }
}
