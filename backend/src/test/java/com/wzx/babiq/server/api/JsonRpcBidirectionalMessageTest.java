package com.wzx.babiq.server.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wzx.babiq.server.application.action.ApplicationOutboundJsonRpcClient;
import com.wzx.babiq.server.application.action.ApplicationOutboundRequestTracker;
import com.wzx.babiq.server.application.action.PendingApplicationActions;
import com.wzx.babiq.server.application.auth.BusinessDesktopConnectionRegistry;
import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.atomic.AtomicReference;
import java.util.Arrays;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 验证同一 WebSocket 同时承载入站 request/notification 和出站 response correlation。 */
class JsonRpcBidirectionalMessageTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
    private final ApplicationOutboundRequestTracker tracker = new ApplicationOutboundRequestTracker(scheduler);
    private final PendingApplicationActions pendingActions = mock(PendingApplicationActions.class);
    private final ApplicationOutboundJsonRpcClient outboundClient = mock(ApplicationOutboundJsonRpcClient.class);
    private JsonRpcDispatcher dispatcher;
    private ExposedHandler handler;
    private WebSocketSession session;
    private AtomicReference<TextMessage> sent;

    @BeforeEach
    void setUp() throws IOException {
        dispatcher = mock(JsonRpcDispatcher.class);
        handler = new ExposedHandler(dispatcher, objectMapper, tracker, pendingActions, outboundClient);
        session = mock(WebSocketSession.class);
        sent = new AtomicReference<>();
        when(session.getId()).thenReturn("ws-1");
        when(session.getAttributes()).thenReturn(Map.of());
        org.mockito.Mockito.doAnswer(invocation -> {
            sent.set(invocation.getArgument(0));
            return null;
        }).when(session).sendMessage(any(TextMessage.class));
    }

    @AfterEach
    void shutDownScheduler() {
        scheduler.shutdownNow();
    }

    @Test
    void clientRequestEntersDispatcherAndWritesExactlyOneResponse() throws Exception {
        when(dispatcher.dispatch(any(JsonRpcMessage.Request.class), eq(session)))
                .thenReturn(JsonRpcMessage.Response.ok(10L, Map.of("ok", true)));

        handler.handle(session, """
                {"jsonrpc":"2.0","id":10,"method":"thread/create","params":{"cwd":"."}}
                """);

        verify(dispatcher).dispatch(any(JsonRpcMessage.Request.class), eq(session));
        verify(session).sendMessage(any(TextMessage.class));
        JsonNode response = objectMapper.readTree(sent.get().getPayload());
        assertThat(response.path("id").longValue()).isEqualTo(10L);
        assertThat(response.path("result").path("ok").booleanValue()).isTrue();
    }

    @Test
    void clientNotificationRoutesOnlyThroughNotificationDispatcherAndWritesNoResponse() throws Exception {
        when(dispatcher.dispatchNotification(any(JsonRpcMessage.Notification.class), eq(session))).thenReturn(true);

        handler.handle(session, """
                {"jsonrpc":"2.0","method":"application/action/running","params":{"executionId":"execution-1"}}
                """);

        verify(dispatcher).dispatchNotification(any(JsonRpcMessage.Notification.class), eq(session));
        verify(dispatcher, never()).dispatch(any(), any());
        verify(session, never()).sendMessage(any(TextMessage.class));
    }

    @Test
    void successAndErrorResponsesCompleteOutboundCorrelationWithoutDispatcherInvocation() throws Exception {
        var success = tracker.register("ws-1", 20L, Duration.ofSeconds(1));
        var error = tracker.register("ws-1", 21L, Duration.ofSeconds(1));

        handler.handle(session, """
                {"jsonrpc":"2.0","id":20,"result":{"state":"accepted"}}
                """);
        handler.handle(session, """
                {"jsonrpc":"2.0","id":21,"error":{"code":-32602,"message":"Invalid request"}}
                """);

        assertThat(success.join()).isInstanceOf(JsonRpcMessage.Response.class);
        assertThat(error.join()).isInstanceOf(JsonRpcMessage.ErrorResponse.class);
        verifyNoRequestDispatchOrResponseWrite();
    }

    @Test
    void malformedNullIdResponseIsIgnoredWithoutDispatchOrResponseWrite() throws Exception {
        handler.handle(session, """
                {"jsonrpc":"2.0","result":{"state":"accepted"}}
                """);

        assertThat(tracker.pendingCount()).isZero();
        verifyNoRequestDispatchOrResponseWrite();
    }

    @Test
    void malformedJsonUsesFixedErrorWithoutEchoingPayloadInResponseOrLogs() throws Exception {
        String sensitivePayload = "oaPasswordMustNotLeak";
        Logger logger = (Logger) LoggerFactory.getLogger(JsonRpcWebSocketHandler.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            handler.handle(session, sensitivePayload);
        } finally {
            logger.detachAppender(appender);
            appender.stop();
        }

        JsonNode response = objectMapper.readTree(sent.get().getPayload());
        assertThat(response.path("error").path("code").intValue()).isEqualTo(-32700);
        assertThat(response.path("error").path("message").asText())
                .isEqualTo("Malformed JSON")
                .doesNotContain(sensitivePayload);
        assertThat(appender.list.stream()
                .map(ILoggingEvent::getFormattedMessage)
                .collect(Collectors.joining("\n")))
                .doesNotContain(sensitivePayload);
    }

    @Test
    void closedSessionSendIsDroppedWithoutLeakingFailureMessage() throws Exception {
        String sensitiveFailure = "closed E:\\clients\\secret-case-token.txt";
        when(dispatcher.dispatch(any(JsonRpcMessage.Request.class), eq(session)))
                .thenReturn(JsonRpcMessage.Response.ok(42L, Map.of("ok", true)));
        org.mockito.Mockito.doThrow(new IllegalStateException(sensitiveFailure))
                .when(session).sendMessage(any(TextMessage.class));

        Logger logger = (Logger) LoggerFactory.getLogger(JsonRpcWebSocketHandler.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            org.assertj.core.api.Assertions.assertThatCode(() -> handler.handle(session, """
                    {"jsonrpc":"2.0","id":42,"method":"thread/create","params":{"cwd":"."}}
                    """)).doesNotThrowAnyException();
        } finally {
            logger.detachAppender(appender);
            appender.stop();
        }

        assertThat(appender.list.stream()
                .map(ILoggingEvent::getFormattedMessage)
                .collect(Collectors.joining("\n")))
                .contains("IllegalStateException")
                .doesNotContain(sensitiveFailure, "secret-case-token.txt");
    }

    @Test
    void businessAuthRequestInfoLogUsesFixedSummaryWithoutChangingDispatchedParams() throws Exception {
        String accountCanary = "account-canary@example.test";
        String candidateCanary = "candidate-canary";
        String innocentCanary = "innocent-password-token-canary";
        AtomicReference<JsonRpcMessage.Request> dispatched = new AtomicReference<>();
        org.mockito.Mockito.doAnswer(invocation -> {
            dispatched.set(invocation.getArgument(0));
            return JsonRpcMessage.Response.ok(41L, Map.of("ok", true));
        }).when(dispatcher).dispatch(any(JsonRpcMessage.Request.class), eq(session));

        Logger logger = (Logger) LoggerFactory.getLogger(JsonRpcWebSocketHandler.class);
        Level previousLevel = logger.getLevel();
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            logger.setLevel(Level.INFO);
            handler.handle(session, """
                    {"jsonrpc":"2.0","id":41,"method":"business/auth/login","params":{
                      "account":"account-canary@example.test",
                      "candidateId":"candidate-canary",
                      "innocentLookingField":"innocent-password-token-canary"
                    }}
                    """);
        } finally {
            logger.setLevel(previousLevel);
            logger.detachAppender(appender);
            appender.stop();
        }

        String logs = appender.list.stream()
                .map(ILoggingEvent::getFormattedMessage)
                .collect(Collectors.joining("\n"));
        assertThat(logs)
                .contains("business/auth/login")
                .contains("[business-auth-redacted]")
                .doesNotContain(accountCanary)
                .doesNotContain(candidateCanary)
                .doesNotContain(innocentCanary);

        JsonNode dispatchedParams = objectMapper.valueToTree(dispatched.get().params());
        assertThat(dispatchedParams.path("account").asText()).isEqualTo(accountCanary);
        assertThat(dispatchedParams.path("candidateId").asText()).isEqualTo(candidateCanary);
        assertThat(dispatchedParams.path("innocentLookingField").asText()).isEqualTo(innocentCanary);
    }

    @Test
    void outboundClientUsesMonotonicIdsAndHandlerCorrelatesDesktopResponses() throws Exception {
        ApplicationOutboundJsonRpcClient client = new ApplicationOutboundJsonRpcClient(objectMapper, tracker);

        var first = client.request(session, "application/action/request",
                Map.of("executionId", "execution-1", "input", "secret-action-payload"), Duration.ofSeconds(1));
        JsonNode firstWire = objectMapper.readTree(sent.get().getPayload());
        long firstId = firstWire.path("id").longValue();
        var second = client.request(session, "application/action/status",
                Map.of("executionId", "execution-1"), Duration.ofSeconds(1));
        JsonNode secondWire = objectMapper.readTree(sent.get().getPayload());

        assertThat(firstId).isPositive();
        assertThat(secondWire.path("id").longValue()).isEqualTo(firstId + 1);
        assertThat(first.toString()).doesNotContain("secret-action-payload");

        handler.handle(session, "{\"jsonrpc\":\"2.0\",\"id\":" + firstId + ",\"result\":{\"accepted\":true}}");
        handler.handle(session, "{\"jsonrpc\":\"2.0\",\"id\":" + (firstId + 1) + ",\"result\":{\"state\":\"running\"}}");

        assertThat(first.join()).isInstanceOf(JsonRpcMessage.Response.class);
        assertThat(second.join()).isInstanceOf(JsonRpcMessage.Response.class);
    }

    @Test
    void outboundSendFailureAtomicallyRemovesPendingCorrelation() throws Exception {
        ApplicationOutboundJsonRpcClient client = new ApplicationOutboundJsonRpcClient(objectMapper, tracker);
        org.mockito.Mockito.doThrow(new IOException("socket closed"))
                .when(session).sendMessage(any(TextMessage.class));

        assertThatThrownBy(() -> client.request(
                session,
                "application/action/request",
                Map.of("input", "secret-action-payload"),
                Duration.ofSeconds(1)))
                .isInstanceOf(IllegalStateException.class)
                .hasCauseInstanceOf(IOException.class);
        assertThat(tracker.pendingCount()).isZero();
    }

    @Test
    void outboundNotificationUsesRegisteredSessionWithoutCorrelationId() throws Exception {
        ApplicationOutboundJsonRpcClient client = new ApplicationOutboundJsonRpcClient(objectMapper, tracker);
        client.registerSession(session);

        client.sendNotification("ws-1", "business/auth/state-changed",
                Map.of("authSessionId", "auth-1", "state", "SIGNED_OUT", "generation", 3));

        JsonNode wire = objectMapper.readTree(sent.get().getPayload());
        assertThat(wire.path("jsonrpc").asText()).isEqualTo("2.0");
        assertThat(wire.path("method").asText()).isEqualTo("business/auth/state-changed");
        assertThat(wire.has("id")).isFalse();
        assertThat(wire.at("/params/state").asText()).isEqualTo("SIGNED_OUT");
        assertThat(tracker.pendingCount()).isZero();
    }

    @Test
    void closeFailsPendingOutboundRequests() throws Exception {
        var pending = tracker.register("ws-1", 30L, Duration.ofSeconds(5));

        handler.afterConnectionClosed(session, CloseStatus.NORMAL);

        assertThatThrownBy(pending::join).hasCauseInstanceOf(IOException.class);
        assertThat(tracker.pendingCount()).isZero();
        verify(pendingActions).onConnectionClosed("ws-1", "business desktop WebSocket closed");
        verify(outboundClient).unregisterSession("ws-1", session);
    }

    @Test
    void connectionLifecycleRegistersSessionForServerInitiatedRequests() throws Exception {
        handler.afterConnectionEstablished(session);

        verify(outboundClient).registerSession(session);
    }

    @Test
    void onlyCompleteSixDependencyConstructorIsSpringAutowired() {
        var constructors = Arrays.asList(JsonRpcWebSocketHandler.class.getConstructors());

        assertThat(constructors)
                .filteredOn(constructor -> constructor.isAnnotationPresent(Autowired.class))
                .singleElement()
                .satisfies(constructor -> assertThat(constructor.getParameterCount()).isEqualTo(6));
    }

    private void verifyNoRequestDispatchOrResponseWrite() throws IOException {
        verify(dispatcher, never()).dispatch(any(), any());
        verify(dispatcher, never()).dispatchNotification(any(), any());
        verify(session, never()).sendMessage(any(TextMessage.class));
    }

    private static final class ExposedHandler extends JsonRpcWebSocketHandler {
        @SuppressWarnings("unchecked")
        private ExposedHandler(
                JsonRpcDispatcher dispatcher,
                ObjectMapper objectMapper,
                ApplicationOutboundRequestTracker tracker,
                PendingApplicationActions pendingActions,
                ApplicationOutboundJsonRpcClient outboundClient) {
            super(dispatcher, objectMapper, emptyProvider(), provider(tracker), provider(pendingActions),
                    provider(outboundClient));
        }

        private void handle(WebSocketSession session, String payload) {
            handleTextMessage(session, new TextMessage(payload));
        }

        private static <T> ObjectProvider<T> emptyProvider() {
            ObjectProvider<T> provider = mock(ObjectProvider.class);
            when(provider.getIfAvailable()).thenReturn(null);
            return provider;
        }

        private static ObjectProvider<ApplicationOutboundRequestTracker> provider(
                ApplicationOutboundRequestTracker tracker) {
            @SuppressWarnings("unchecked")
            ObjectProvider<ApplicationOutboundRequestTracker> provider = mock(ObjectProvider.class);
            when(provider.getIfAvailable()).thenReturn(tracker);
            return provider;
        }

        private static ObjectProvider<PendingApplicationActions> provider(
                PendingApplicationActions pendingActions) {
            @SuppressWarnings("unchecked")
            ObjectProvider<PendingApplicationActions> provider = mock(ObjectProvider.class);
            when(provider.getIfAvailable()).thenReturn(pendingActions);
            return provider;
        }

        private static ObjectProvider<ApplicationOutboundJsonRpcClient> provider(
                ApplicationOutboundJsonRpcClient outboundClient) {
            @SuppressWarnings("unchecked")
            ObjectProvider<ApplicationOutboundJsonRpcClient> provider = mock(ObjectProvider.class);
            when(provider.getIfAvailable()).thenReturn(outboundClient);
            return provider;
        }
    }
}
