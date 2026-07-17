package com.wzx.babiq.server.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wzx.babiq.server.api.error.JsonRpcErrorCode;
import com.wzx.babiq.server.application.api.BusinessJsonRpcAccessPolicy;
import org.springframework.beans.factory.ObjectProvider;
import org.junit.jupiter.api.Test;
import org.springframework.web.socket.WebSocketSession;

import java.util.AbstractSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 验证一个处理器可安全承载多个 JSON-RPC method，且注册冲突在启动阶段失败。 */
class JsonRpcMultiMethodHandlerTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void dispatcherPassesTheMatchedMethodToMultiMethodHandler() {
        AtomicReference<String> receivedMethod = new AtomicReference<>();
        JsonRpcMultiMethodHandler handler = multiHandler(Set.of("application/catalog/register", "application/catalog/update"),
                (method, params, session) -> {
                    receivedMethod.set(method);
                    return Map.of("handled", method);
                });
        JsonRpcDispatcher dispatcher = new JsonRpcDispatcher(List.of(handler), objectMapper);

        JsonRpcMessage message = dispatcher.dispatch(new JsonRpcMessage.Request(
                "2.0", 1L, "application/catalog/update", Map.of("catalogEpoch", 2)), null);

        JsonRpcMessage.Response response = (JsonRpcMessage.Response) message;
        assertThat(receivedMethod).hasValue("application/catalog/update");
        assertThat(response.result()).isEqualTo(Map.of("handled", "application/catalog/update"));
    }

    @Test
    void singleAndMultiMethodNameConflictFailsRegistration() {
        JsonRpcMethodHandler single = singleHandler("application/catalog/register");
        JsonRpcMultiMethodHandler multi = multiHandler(
                Set.of("application/catalog/register", "application/catalog/update"), returningMethod());

        assertThatThrownBy(() -> new JsonRpcDispatcher(List.of(single, multi), objectMapper))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("application/catalog/register");
    }

    @Test
    void twoMultiHandlersWithSameMethodFailRegistration() {
        JsonRpcMultiMethodHandler first = multiHandler(
                Set.of("application/identity/bind", "application/identity/update"), returningMethod());
        JsonRpcMultiMethodHandler second = multiHandler(
                Set.of("application/identity/update", "application/context/publish"), returningMethod());

        assertThatThrownBy(() -> new JsonRpcDispatcher(List.of(first, second), objectMapper))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("application/identity/update");
    }

    @Test
    void duplicateMethodInsideOneMultiHandlerFailsRegistration() {
        Set<String> malformedMethods = new AbstractSet<>() {
            @Override
            public Iterator<String> iterator() {
                return List.of("application/action/status", "application/action/status").iterator();
            }

            @Override
            public int size() {
                return 2;
            }
        };
        JsonRpcMultiMethodHandler handler = multiHandler(malformedMethods, returningMethod());

        assertThatThrownBy(() -> new JsonRpcDispatcher(List.of(handler), objectMapper))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("application/action/status");
    }

    @Test
    void emptyMultiMethodSetFailsRegistration() {
        JsonRpcMultiMethodHandler handler = multiHandler(Set.of(), returningMethod());

        assertThatThrownBy(() -> new JsonRpcDispatcher(List.of(handler), objectMapper))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("至少声明一个");
    }

    @Test
    void blankMultiMethodNameFailsRegistration() {
        JsonRpcMultiMethodHandler handler = multiHandler(Set.of(" "), returningMethod());

        assertThatThrownBy(() -> new JsonRpcDispatcher(List.of(handler), objectMapper))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("不能为空");
    }

    @Test
    void businessAccessPolicyHidesDeniedMethodsAndDoesNotInvokeHandler() {
        AtomicBoolean invoked = new AtomicBoolean();
        JsonRpcMethodHandler handler = new JsonRpcMethodHandler() {
            @Override
            public String method() {
                return "application/action/request";
            }

            @Override
            public Object handle(JsonNode params, WebSocketSession session) {
                invoked.set(true);
                return Map.of("unexpected", true);
            }
        };
        BusinessJsonRpcAccessPolicy policy = mock(BusinessJsonRpcAccessPolicy.class);
        when(policy.isAllowed("application/action/request", "ws-1")).thenReturn(false);
        @SuppressWarnings("unchecked")
        ObjectProvider<BusinessJsonRpcAccessPolicy> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(policy);
        WebSocketSession session = mock(WebSocketSession.class);
        when(session.getId()).thenReturn("ws-1");
        JsonRpcDispatcher dispatcher = new JsonRpcDispatcher(List.of(handler), objectMapper, provider);

        JsonRpcMessage message = dispatcher.dispatch(new JsonRpcMessage.Request(
                "2.0", 7L, "application/action/request", Map.of()), session);

        JsonRpcMessage.ErrorResponse error = (JsonRpcMessage.ErrorResponse) message;
        assertThat(error.error().code()).isEqualTo(JsonRpcErrorCode.METHOD_NOT_FOUND.code());
        assertThat(invoked).isFalse();
    }

    private JsonRpcMethodHandler singleHandler(String method) {
        return new JsonRpcMethodHandler() {
            @Override
            public String method() {
                return method;
            }

            @Override
            public Object handle(JsonNode params, WebSocketSession session) {
                return Map.of("handled", method);
            }
        };
    }

    private JsonRpcMultiMethodHandler multiHandler(Set<String> methods, MultiInvocation invocation) {
        return new JsonRpcMultiMethodHandler() {
            @Override
            public Set<String> methods() {
                return methods;
            }

            @Override
            public Object handle(String method, JsonNode params, WebSocketSession session) throws Exception {
                return invocation.handle(method, params, session);
            }
        };
    }

    private MultiInvocation returningMethod() {
        return (method, params, session) -> Map.of("handled", method);
    }

    @FunctionalInterface
    private interface MultiInvocation {
        Object handle(String method, JsonNode params, WebSocketSession session) throws Exception;
    }
}
