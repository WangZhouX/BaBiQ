package com.wzx.babiq.server.attachment;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.wzx.babiq.server.agent.AgentLoop;
import com.wzx.babiq.server.agent.PendingApprovals;
import com.wzx.babiq.server.agent.ReActStrategy;
import com.wzx.babiq.server.agent.TurnExecutor;
import com.wzx.babiq.server.api.error.JsonRpcException;
import com.wzx.babiq.server.api.method.TurnStartHandler;
import com.wzx.babiq.server.conversation.ConversationEventRecorder;
import com.wzx.babiq.server.conversation.ConversationService;
import com.wzx.babiq.server.conversation.items.UserMessageItem;
import com.wzx.babiq.server.conversation.repository.ConversationRepository;
import com.wzx.babiq.server.observability.TurnObservationRegistry;
import com.wzx.babiq.server.observability.TurnSummaryEmitter;
import com.wzx.babiq.server.persistence.service.TurnPersistenceService;
import com.wzx.babiq.server.recovery.StartupRecoveryCoordinator;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.web.socket.WebSocketSession;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.LinkOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileTime;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AttachmentPublicationCleanupRaceTest {

    private static final Instant NOW = Instant.parse("2026-07-21T04:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);

    @TempDir
    Path tempDir;

    @Test
    void publicationFirstMakesClipboardPathVisibleToCleanupBeforeItCanDelete() throws Exception {
        Path root = Files.createDirectories(tempDir.resolve("publication-first"));
        Path candidate = oldScreenshot(root, "截图-20260114-000000-MNPQRS.png");
        AttachmentReservationRegistry registry = new AttachmentReservationRegistry();
        AttachmentReferenceRepository repository = mock(AttachmentReferenceRepository.class);
        CountDownLatch repositoryReached = new CountDownLatch(1);
        when(repository.findAll()).thenAnswer(invocation -> {
            repositoryReached.countDown();
            return List.of();
        });
        ClipboardAttachmentRetentionService retention = retention(root, repository, registry);

        PreparedAttachment attachment = clipboardAttachment(candidate);
        AttachmentPreparationService preparation = mock(AttachmentPreparationService.class);
        AttachmentHistoryResolver history = mock(AttachmentHistoryResolver.class);
        CountDownLatch preparationEntered = new CountDownLatch(1);
        CountDownLatch allowPreparation = new CountDownLatch(1);
        when(preparation.prepareNew(any(), any())).thenAnswer(invocation -> {
            preparationEntered.countDown();
            allowPreparation.await();
            if (!Files.exists(candidate)) {
                throw missing();
            }
            return new PreparedTurnInput("review", List.of(attachment), List.of());
        });
        when(history.resolve(any(), any(), any())).thenAnswer(invocation -> invocation.getArgument(2));
        TurnExecutor executor = mock(TurnExecutor.class);
        ConversationService conversations = new ConversationService();
        String threadId = conversations.createThread(tempDir.toString()).id();
        TurnStartHandler handler =
                handler(conversations, executor, preparation, history, registry);
        ExecutorService workers = Executors.newFixedThreadPool(2);
        Future<Object> publication = null;
        Future<ClipboardAttachmentRetentionService.CleanupResult> cleanup = null;
        try {
            publication = workers.submit(() -> handler.handle(
                    request(threadId, attachment, true), session()));
            assertThat(preparationEntered.await(5, TimeUnit.SECONDS)).isTrue();

            CountDownLatch cleanupTaskStarted = new CountDownLatch(1);
            cleanup = workers.submit(() -> {
                cleanupTaskStarted.countDown();
                return retention.cleanup();
            });
            assertThat(cleanupTaskStarted.await(5, TimeUnit.SECONDS)).isTrue();
            assertThat(repositoryReached.await(200, TimeUnit.MILLISECONDS)).isFalse();
            allowPreparation.countDown();

            Map<?, ?> response = (Map<?, ?>) publication.get(5, TimeUnit.SECONDS);
            ClipboardAttachmentRetentionService.CleanupResult result =
                    cleanup.get(5, TimeUnit.SECONDS);

            assertThat(candidate).exists();
            assertThat(result.deletedFiles()).isZero();
            assertThat(registry.isPathProtected(candidate)).isTrue();
            verify(executor).submit(any(), any(PreparedTurnInput.class), any(), any(), any(), any(), any());
            registry.releaseTurn(String.valueOf(response.get("turnId")));
        } finally {
            allowPreparation.countDown();
            if (publication != null) {
                publication.cancel(true);
            }
            if (cleanup != null) {
                cleanup.cancel(true);
            }
            workers.shutdownNow();
        }
    }

    @Test
    void cleanupFirstDeletesBeforePublicationValidationWhichThenFailsSafely() throws Exception {
        Path root = Files.createDirectories(tempDir.resolve("cleanup-first"));
        Path candidate = oldScreenshot(root, "截图-20260115-000000-NPQRST.png");
        AttachmentReservationRegistry registry = new AttachmentReservationRegistry();
        AttachmentReferenceRepository repository = mock(AttachmentReferenceRepository.class);
        CountDownLatch cleanupEntered = new CountDownLatch(1);
        CountDownLatch allowCleanup = new CountDownLatch(1);
        when(repository.findAll()).thenAnswer(invocation -> {
            cleanupEntered.countDown();
            allowCleanup.await();
            return List.of();
        });
        ClipboardAttachmentRetentionService retention = retention(root, repository, registry);

        PreparedAttachment attachment = clipboardAttachment(candidate);
        AttachmentPreparationService preparation = mock(AttachmentPreparationService.class);
        AttachmentHistoryResolver history = mock(AttachmentHistoryResolver.class);
        CountDownLatch preparationEntered = new CountDownLatch(1);
        when(preparation.prepareNew(any(), any())).thenAnswer(invocation -> {
            preparationEntered.countDown();
            if (!Files.exists(candidate)) {
                throw missing();
            }
            return new PreparedTurnInput("review", List.of(attachment), List.of());
        });
        when(history.resolve(any(), any(), any())).thenAnswer(invocation -> invocation.getArgument(2));
        ConversationService conversations = new ConversationService();
        String threadId = conversations.createThread(tempDir.toString()).id();
        TurnStartHandler handler =
                handler(conversations, mock(TurnExecutor.class), preparation, history, registry);
        ExecutorService workers = Executors.newFixedThreadPool(2);
        Future<ClipboardAttachmentRetentionService.CleanupResult> cleanup =
                workers.submit(retention::cleanup);
        Future<Object> publication = null;
        try {
            assertThat(cleanupEntered.await(5, TimeUnit.SECONDS)).isTrue();
            CountDownLatch publicationTaskStarted = new CountDownLatch(1);
            publication = workers.submit(() -> {
                publicationTaskStarted.countDown();
                return handler.handle(request(threadId, attachment, true), session());
            });
            assertThat(publicationTaskStarted.await(5, TimeUnit.SECONDS)).isTrue();
            assertThat(preparationEntered.await(200, TimeUnit.MILLISECONDS)).isFalse();
            allowCleanup.countDown();

            assertThat(cleanup.get(5, TimeUnit.SECONDS).deletedFiles()).isEqualTo(1);
            assertThat(candidate).doesNotExist();
            Future<Object> finalPublication = publication;
            assertThatThrownBy(() -> finalPublication.get(5, TimeUnit.SECONDS))
                    .isInstanceOfSatisfying(ExecutionException.class, failure ->
                            assertThat(failure.getCause())
                                    .isInstanceOfSatisfying(JsonRpcException.class, jsonRpc ->
                                            assertThat(jsonRpc.errorData()).isEqualTo(Map.of(
                                                    "attachmentCode",
                                                    AttachmentErrorCode.ATTACHMENT_NOT_FOUND.name()))));
        } finally {
            allowCleanup.countDown();
            if (publication != null) {
                publication.cancel(true);
            }
            cleanup.cancel(true);
            workers.shutdownNow();
        }
    }

    @Test
    void resolvedHistoryAttachmentsAreIncludedInThePublishedProtectionSet() throws Exception {
        Path candidate = Files.write(tempDir.resolve("history.png"), new byte[]{1, 2, 3});
        PreparedAttachment attachment = clipboardAttachment(candidate);
        AttachmentReservationRegistry registry = new AttachmentReservationRegistry();
        AttachmentPreparationService preparation = mock(AttachmentPreparationService.class);
        AttachmentHistoryResolver history = mock(AttachmentHistoryResolver.class);
        PreparedTurnInput prepared = new PreparedTurnInput(
                "reuse " + attachment.metadata().displayId(), List.of(), List.of());
        PreparedTurnInput resolved = new PreparedTurnInput(
                prepared.text(), List.of(), List.of(attachment));
        when(preparation.prepareNew(prepared.text(), List.of())).thenReturn(prepared);
        when(history.resolve(any(), any(), any())).thenReturn(resolved);
        ConversationService conversations = new ConversationService();
        String threadId = conversations.createThread(tempDir.toString()).id();
        TurnStartHandler handler = handler(
                conversations, mock(TurnExecutor.class), preparation, history, registry);

        Map<?, ?> response = (Map<?, ?>) handler.handle(
                request(threadId, attachment, false), session());

        assertThat(registry.isPathProtected(candidate)).isTrue();
        registry.releaseTurn(String.valueOf(response.get("turnId")));
        assertThat(registry.isPathProtected(candidate)).isFalse();
    }

    @Test
    void concurrentHistoryReferencesSharePathProtectionWithoutOwningPersistedIdentity()
            throws Exception {
        Path candidate = Files.write(tempDir.resolve("shared-history.png"), new byte[]{1, 2, 3});
        PreparedAttachment attachment = clipboardAttachment(candidate);
        AttachmentReservationRegistry registry = new AttachmentReservationRegistry();
        AttachmentPreparationService preparation = mock(AttachmentPreparationService.class);
        AttachmentHistoryResolver history = mock(AttachmentHistoryResolver.class);
        PreparedTurnInput prepared = new PreparedTurnInput(
                "reuse " + attachment.metadata().displayId(), List.of(), List.of());
        PreparedTurnInput resolved = new PreparedTurnInput(
                prepared.text(), List.of(), List.of(attachment));
        when(preparation.prepareNew(prepared.text(), List.of())).thenReturn(prepared);
        when(history.resolve(any(), any(), any())).thenReturn(resolved);
        ConversationService conversations = new ConversationService();
        String threadId = conversations.createThread(tempDir.toString()).id();
        TurnStartHandler handler = handler(
                conversations, mock(TurnExecutor.class), preparation, history, registry);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService workers = Executors.newFixedThreadPool(2);
        Future<Object> first = workers.submit(() -> {
            ready.countDown();
            start.await();
            return handler.handle(request(threadId, attachment, false), session());
        });
        Future<Object> second = workers.submit(() -> {
            ready.countDown();
            start.await();
            return handler.handle(request(threadId, attachment, false), session());
        });
        String firstTurnId = null;
        String secondTurnId = null;
        try {
            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            firstTurnId = String.valueOf(((Map<?, ?>) first.get(5, TimeUnit.SECONDS)).get("turnId"));
            secondTurnId =
                    String.valueOf(((Map<?, ?>) second.get(5, TimeUnit.SECONDS)).get("turnId"));

            assertThat(registry.isPathProtected(candidate)).isTrue();
            registry.releaseTurn(firstTurnId);
            assertThat(registry.isPathProtected(candidate)).isTrue();
            registry.releaseTurn(secondTurnId);
            assertThat(registry.isPathProtected(candidate)).isFalse();
        } finally {
            start.countDown();
            registry.releaseTurn(firstTurnId);
            registry.releaseTurn(secondTurnId);
            first.cancel(true);
            second.cancel(true);
            workers.shutdownNow();
        }
    }

    @Test
    void archivedHistoryPathStaysProtectedAfterUserMessagePersistenceUntilWorkerExit()
            throws Exception {
        Path root = Files.createDirectories(tempDir.resolve("archived-worker-lifecycle"));
        Path candidate = oldScreenshot(root, "截图-20260116-000000-PQRSTU.png");
        PreparedAttachment attachment = clipboardAttachment(candidate);
        AttachmentReservationRegistry registry = new AttachmentReservationRegistry();
        AttachmentReferenceRecord expiredReference = expiredReference(attachment);
        ClipboardAttachmentRetentionService retention = retention(
                root,
                () -> List.of(expiredReference),
                registry);

        ConversationRepository persistedItems = mock(ConversationRepository.class);
        ConversationEventRecorder recorder = new ConversationEventRecorder(
                persistedItems,
                mock(TurnPersistenceService.class),
                null,
                new ObjectMapper());
        CountDownLatch persistedBeforeLoad = new CountDownLatch(1);
        CountDownLatch allowAttachmentLoad = new CountDownLatch(1);
        AtomicBoolean blockedUserMessage = new AtomicBoolean();
        WebSocketSession session = session();
        org.mockito.Mockito.doAnswer(invocation -> {
            org.springframework.web.socket.TextMessage message = invocation.getArgument(0);
            if (message.getPayload().contains("\"method\":\"item/added\"")
                    && blockedUserMessage.compareAndSet(false, true)) {
                persistedBeforeLoad.countDown();
                allowAttachmentLoad.await();
            }
            return null;
        }).when(session).sendMessage(any());

        AtomicReference<Boolean> existedAtLoad = new AtomicReference<>();
        CountDownLatch attachmentLoadAttempted = new CountDownLatch(1);
        AttachmentContentLoader loader = mock(AttachmentContentLoader.class);
        when(loader.load(any())).thenAnswer(invocation -> {
            existedAtLoad.set(Files.exists(candidate));
            attachmentLoadAttempted.countDown();
            throw new IllegalStateException("stop after attachment load observation");
        });
        ReActStrategy strategy = mock(ReActStrategy.class);
        when(strategy.resolveModelName(any())).thenReturn("test-model");
        AgentLoop loop = new AgentLoop(
                strategy,
                new PendingApprovals(),
                mock(TurnSummaryEmitter.class),
                new TurnObservationRegistry(),
                null,
                loader);
        ExecutorService worker = Executors.newSingleThreadExecutor();
        TurnExecutor executor = new TurnExecutor(loop, worker, registry);

        AttachmentPreparationService preparation = mock(AttachmentPreparationService.class);
        AttachmentHistoryResolver history = mock(AttachmentHistoryResolver.class);
        PreparedTurnInput prepared = new PreparedTurnInput(
                "reuse " + attachment.metadata().displayId(), List.of(), List.of());
        PreparedTurnInput resolved = new PreparedTurnInput(
                prepared.text(), List.of(), List.of(attachment));
        when(preparation.prepareNew(prepared.text(), List.of())).thenReturn(prepared);
        when(history.resolve(any(), any(), any())).thenReturn(resolved);
        ConversationService conversations = new ConversationService();
        String threadId = conversations.createThread(tempDir.toString()).id();
        TurnStartHandler handler = new TurnStartHandler(
                conversations,
                new ObjectMapper(),
                executor,
                null,
                null,
                recorder,
                null,
                null,
                null,
                preparation,
                history,
                registry);

        try {
            handler.handle(request(threadId, attachment, false), session);
            assertThat(persistedBeforeLoad.await(5, TimeUnit.SECONDS)).isTrue();
            verify(persistedItems).saveItem(any());

            ClipboardAttachmentRetentionService.CleanupResult result = retention.cleanup();

            assertThat(result.deletedFiles()).isZero();
            assertThat(candidate).exists();
            allowAttachmentLoad.countDown();
            assertThat(attachmentLoadAttempted.await(5, TimeUnit.SECONDS)).isTrue();
            assertThat(existedAtLoad).hasValue(true);
            org.awaitility.Awaitility.await().atMost(Duration.ofSeconds(5)).untilAsserted(() ->
                    assertThat(registry.isPathProtected(candidate)).isFalse());
        } finally {
            allowAttachmentLoad.countDown();
            executor.close();
            worker.shutdownNow();
        }
    }

    private ClipboardAttachmentRetentionService retention(
            Path root,
            AttachmentReferenceRepository repository,
            AttachmentReservationRegistry registry
    ) {
        return new ClipboardAttachmentRetentionService(
                repository,
                new ObjectMapper(),
                root,
                CLOCK,
                new StartupRecoveryCoordinator(),
                registry,
                stableFileAttributes(root));
    }

    private static TurnStartHandler handler(
            ConversationService conversations,
            TurnExecutor executor,
            AttachmentPreparationService preparation,
            AttachmentHistoryResolver history,
            AttachmentReservationRegistry registry
    ) {
        return new TurnStartHandler(
                conversations,
                new ObjectMapper(),
                executor,
                null,
                null,
                null,
                null,
                null,
                null,
                preparation,
                history,
                registry);
    }

    private static com.fasterxml.jackson.databind.JsonNode request(
            String threadId,
            PreparedAttachment attachment,
            boolean includeNewAttachment
    ) {
        Map<String, Object> input = includeNewAttachment
                ? Map.of(
                        "type", "text",
                        "text", "review",
                        "attachments", List.of(Map.of(
                                "id", attachment.metadata().id(),
                                "displayId", attachment.metadata().displayId(),
                                "name", attachment.metadata().name(),
                                "localPath", attachment.metadata().localPath())))
                : Map.of(
                        "type", "text",
                        "text", "reuse " + attachment.metadata().displayId(),
                        "attachments", List.of());
        return new ObjectMapper().valueToTree(Map.of("threadId", threadId, "input", input));
    }

    private static WebSocketSession session() {
        WebSocketSession session = mock(WebSocketSession.class);
        when(session.getId()).thenReturn("ws-race-test");
        return session;
    }

    private static PreparedAttachment clipboardAttachment(Path file) throws Exception {
        AttachmentMetadata metadata = new AttachmentMetadata(
                "550e8400-e29b-41d4-a716-446655440000",
                "A-7K3M2Q",
                file.getFileName().toString(),
                file.toRealPath().toString(),
                "image/png",
                Files.size(file),
                "a".repeat(64),
                AttachmentSource.CLIPBOARD_IMAGE);
        return new PreparedAttachment(
                metadata,
                file.toRealPath(),
                new PreparedAttachment.FileIdentity(
                        Files.size(file),
                        Files.getLastModifiedTime(file),
                        "race-file-key"));
    }

    private static AttachmentException missing() {
        return new AttachmentException(
                AttachmentErrorCode.ATTACHMENT_NOT_FOUND,
                "attachment no longer exists");
    }

    private static AttachmentReferenceRecord expiredReference(PreparedAttachment attachment)
            throws Exception {
        String payload = new ObjectMapper().writeValueAsString(UserMessageItem.of(
                "item-archived",
                "reuse archived attachment",
                List.of(attachment.metadata())));
        return new AttachmentReferenceRecord(
                payload,
                NOW.minus(Duration.ofDays(31)).toString());
    }

    private static Path oldScreenshot(Path root, String name) throws Exception {
        Path path = Files.write(root.resolve(name), new byte[]{1, 2, 3});
        Files.setLastModifiedTime(path, FileTime.from(NOW.minus(Duration.ofDays(2))));
        return path;
    }

    private static ClipboardAttachmentRetentionService.FileAttributeReader stableFileAttributes(
            Path root
    ) {
        Path canonicalRoot = root.toAbsolutePath().normalize();
        return path -> {
            BasicFileAttributes actual = Files.readAttributes(
                    path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            BasicFileAttributes stable = mock(BasicFileAttributes.class);
            when(stable.isRegularFile()).thenReturn(actual.isRegularFile());
            when(stable.isDirectory()).thenReturn(actual.isDirectory());
            when(stable.isSymbolicLink()).thenReturn(actual.isSymbolicLink());
            when(stable.isOther()).thenReturn(actual.isOther());
            when(stable.size()).thenReturn(actual.size());
            when(stable.lastModifiedTime()).thenReturn(actual.lastModifiedTime());
            when(stable.fileKey()).thenReturn(
                    path.toAbsolutePath().normalize().equals(canonicalRoot)
                            ? "root-key"
                            : "file-key:" + path.toAbsolutePath().normalize());
            return stable;
        };
    }
}
