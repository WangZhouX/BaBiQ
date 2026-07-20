package com.wzx.babiq.server.attachment;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AttachmentContentLoaderTest {

    @TempDir
    Path tempDir;

    private final List<AttachmentContentLoader> loaders = new ArrayList<>();

    @AfterEach
    void closeLoaders() {
        loaders.forEach(AttachmentContentLoader::close);
    }

    @Test
    void returnsExactImageBytesAndDetectedMimeWithoutDocumentExtraction() throws Exception {
        byte[] image = png();
        Path path = Files.write(tempDir.resolve("image.png"), image);
        PreparedAttachment attachment = validate(path);
        AttachmentDocumentExtractor extractor = mock(AttachmentDocumentExtractor.class);
        AttachmentContentLoader loader = loader(extractor);

        List<AttachmentContent> contents = loader.load(List.of(attachment));

        assertThat(contents).hasSize(1);
        assertThat(contents.getFirst().isImage()).isTrue();
        assertThat(contents.getFirst().imageBytes()).containsExactly(image);
        assertThat(contents.getFirst().attachment().metadata().mediaType()).isEqualTo("image/png");
        verify(extractor, never()).extract(
                any(), any(byte[].class),
                any(AttachmentDocumentExtractor.ExtractionCancellation.class));
    }

    @Test
    void extractsDocumentsInSelectionOrderAndEnforcesTurnCharacterLimit() throws Exception {
        List<PreparedAttachment> attachments = new ArrayList<>();
        for (int index = 0; index < 3; index++) {
            Path path = Files.writeString(tempDir.resolve("file-" + index + ".txt"), "body-" + index);
            attachments.add(validate(path));
        }
        AttachmentDocumentExtractor extractor = mock(AttachmentDocumentExtractor.class);
        when(extractor.extract(
                any(), any(byte[].class),
                any(AttachmentDocumentExtractor.ExtractionCancellation.class)))
                .thenAnswer(invocation -> {
            PreparedAttachment attachment = invocation.getArgument(0);
            return new AttachmentTextSegment(
                    attachment.metadata().id(),
                    attachment.metadata().displayId(),
                    attachment.metadata().name(),
                    attachment.metadata().mediaType(),
                    "x".repeat(90_000));
        });
        AttachmentContentLoader loader = loader(extractor);

        assertThatThrownBy(() -> loader.load(attachments))
                .isInstanceOfSatisfying(AttachmentException.class, failure ->
                        assertThat(failure.code())
                                .isEqualTo(AttachmentErrorCode.ATTACHMENT_TEXT_LIMIT_EXCEEDED));
    }

    @Test
    void acceptsExactlyTwoHundredFiftyThousandExtractedCharactersInSelectionOrder()
            throws Exception {
        List<PreparedAttachment> attachments = List.of(
                validate(Files.writeString(tempDir.resolve("one.txt"), "one")),
                validate(Files.writeString(tempDir.resolve("two.txt"), "two")),
                validate(Files.writeString(tempDir.resolve("three.txt"), "three")));
        AttachmentDocumentExtractor extractor = mock(AttachmentDocumentExtractor.class);
        when(extractor.extract(
                any(), any(byte[].class),
                any(AttachmentDocumentExtractor.ExtractionCancellation.class)))
                .thenAnswer(invocation -> {
            PreparedAttachment attachment = invocation.getArgument(0);
            int index = attachment.metadata().name().startsWith("three") ? 50_000 : 100_000;
            return segment(attachment, "x".repeat(index));
        });
        AttachmentContentLoader loader = loader(extractor);

        List<AttachmentContent> contents = loader.load(attachments);

        assertThat(contents)
                .extracting(content -> content.attachment().metadata().name())
                .containsExactly("one.txt", "two.txt", "three.txt");
        assertThat(contents)
                .extracting(content -> content.textSegment().originalCharacterCount())
                .containsExactly(100_000, 100_000, 50_000);
    }

    @Test
    void revalidatesIdentityAndShaImmediatelyBeforeActualRead() throws Exception {
        Path path = Files.writeString(tempDir.resolve("changing.txt"), "first");
        PreparedAttachment prepared = validate(path);
        Files.writeString(path, "other");
        AttachmentDocumentExtractor extractor = mock(AttachmentDocumentExtractor.class);
        AttachmentContentLoader loader = loader(extractor);

        assertThatThrownBy(() -> loader.load(List.of(prepared)))
                .isInstanceOfSatisfying(AttachmentException.class, failure ->
                        assertThat(failure.code()).isEqualTo(AttachmentErrorCode.ATTACHMENT_CHANGED))
                .hasMessageNotContaining(path.toString())
                .hasMessageNotContaining(prepared.metadata().sha256());
        verify(extractor, never()).extract(
                any(), any(byte[].class),
                any(AttachmentDocumentExtractor.ExtractionCancellation.class));
    }

    @Test
    void rejectsArchiveExtensionsAtTheLoaderBoundary() throws Exception {
        byte[] bytes = "not-an-archive".getBytes(StandardCharsets.UTF_8);
        Path path = Files.write(tempDir.resolve("payload.zip"), bytes);
        PreparedAttachment prepared = manual(path, "application/zip", bytes);
        AttachmentDocumentExtractor extractor = mock(AttachmentDocumentExtractor.class);
        AttachmentContentLoader loader = loader(extractor);

        assertThatThrownBy(() -> loader.load(List.of(prepared)))
                .isInstanceOfSatisfying(AttachmentException.class, failure ->
                        assertThat(failure.code())
                                .isEqualTo(AttachmentErrorCode.ATTACHMENT_TYPE_UNSUPPORTED));
        verify(extractor, never()).extract(
                any(), any(byte[].class),
                any(AttachmentDocumentExtractor.ExtractionCancellation.class));
    }

    @Test
    void timedOutWorkerExitsAndSingleThreadPoolCanRunTheNextParse() throws Exception {
        Path slowPath = Files.writeString(tempDir.resolve("slow.txt"), "slow");
        Path nextPath = Files.writeString(tempDir.resolve("next.txt"), "next");
        PreparedAttachment slow = validate(slowPath);
        PreparedAttachment next = validate(nextPath);
        CountDownLatch firstEntered = new CountDownLatch(1);
        CountDownLatch firstExited = new CountDownLatch(1);
        AtomicInteger calls = new AtomicInteger();
        ParserForLoader parser = new ParserForLoader(calls, firstEntered, firstExited);
        AttachmentDocumentExtractor extractor = new AttachmentDocumentExtractor(parser);
        ThreadPoolExecutor executor = new ThreadPoolExecutor(
                1, 1, 0, TimeUnit.MILLISECONDS, new ArrayBlockingQueue<>(8),
                new ThreadPoolExecutor.AbortPolicy());
        AttachmentContentLoader loader = new AttachmentContentLoader(
                extractor,
                new OoxmlArchiveGuard(),
                AttachmentContentLoader.secureReader(),
                executor,
                Duration.ofMillis(50),
                Duration.ofSeconds(5),
                false);
        loaders.add(loader);

        try {
            assertThatThrownBy(() -> loader.load(List.of(slow)))
                    .isInstanceOfSatisfying(AttachmentException.class, failure ->
                            assertThat(failure.code())
                                    .isEqualTo(AttachmentErrorCode.ATTACHMENT_PARSE_TIMEOUT));
            assertThat(firstEntered.await(2, TimeUnit.SECONDS)).isTrue();
            assertThat(firstExited.await(2, TimeUnit.SECONDS)).isTrue();
            assertThat(loader.load(List.of(next)).getFirst().textSegment().text())
                    .isEqualTo("next-safe");
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void enforcesTheWholeTurnDeadlineAcrossFiles() throws Exception {
        List<PreparedAttachment> attachments = List.of(
                validate(Files.writeString(tempDir.resolve("one.txt"), "one")),
                validate(Files.writeString(tempDir.resolve("two.txt"), "two")));
        AttachmentDocumentExtractor extractor = mock(AttachmentDocumentExtractor.class);
        when(extractor.extract(
                any(), any(byte[].class),
                any(AttachmentDocumentExtractor.ExtractionCancellation.class)))
                .thenAnswer(invocation -> {
            Thread.sleep(70);
            PreparedAttachment attachment = invocation.getArgument(0);
            return segment(attachment, "body");
        });
        AttachmentContentLoader loader = loader(
                extractor, AttachmentContentLoader.secureReader(),
                Duration.ofSeconds(5), Duration.ofMillis(100));

        assertThatThrownBy(() -> loader.load(attachments))
                .isInstanceOfSatisfying(AttachmentException.class, failure ->
                        assertThat(failure.code())
                                .isEqualTo(AttachmentErrorCode.ATTACHMENT_PARSE_TIMEOUT));
    }

    @Test
    void exposesStableOverloadWhenTwoWorkersAndEightQueuedTasksAreOccupied() throws Exception {
        ThreadPoolExecutor executor = new ThreadPoolExecutor(
                2, 2, 0, TimeUnit.MILLISECONDS, new ArrayBlockingQueue<>(8),
                new ThreadPoolExecutor.AbortPolicy());
        CountDownLatch blocker = new CountDownLatch(1);
        for (int index = 0; index < 10; index++) {
            executor.execute(() -> {
                try {
                    blocker.await();
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                }
            });
        }
        Path path = Files.writeString(tempDir.resolve("queued.txt"), "queued");
        AttachmentContentLoader loader = new AttachmentContentLoader(
                mock(AttachmentDocumentExtractor.class),
                new OoxmlArchiveGuard(),
                AttachmentContentLoader.secureReader(),
                executor,
                Duration.ofSeconds(10),
                Duration.ofSeconds(30),
                false);
        loaders.add(loader);

        try {
            assertThatThrownBy(() -> loader.load(List.of(validate(path))))
                    .isInstanceOfSatisfying(AttachmentException.class, failure ->
                            assertThat(failure.code())
                                    .isEqualTo(AttachmentErrorCode.ATTACHMENT_PARSE_OVERLOADED));
        } finally {
            blocker.countDown();
            executor.shutdownNow();
        }
    }

    @Test
    void defaultExecutorHasExactlyTwoWorkersAndEightQueueSlots() {
        AttachmentContentLoader loader = loader(mock(AttachmentDocumentExtractor.class));

        assertThat(loader.executorParallelism()).isEqualTo(2);
        assertThat(loader.executorQueueCapacity()).isEqualTo(8);
        assertThat(loader.fileTimeout()).isEqualTo(Duration.ofSeconds(10));
        assertThat(loader.turnTimeout()).isEqualTo(Duration.ofSeconds(30));
    }

    private AttachmentContentLoader loader(AttachmentDocumentExtractor extractor) {
        return loader(
                extractor,
                AttachmentContentLoader.secureReader(),
                Duration.ofSeconds(10),
                Duration.ofSeconds(30));
    }

    private AttachmentContentLoader loader(
            AttachmentDocumentExtractor extractor,
            AttachmentContentLoader.AttachmentBinaryReader reader,
            Duration fileTimeout,
            Duration turnTimeout
    ) {
        AttachmentContentLoader loader = new AttachmentContentLoader(
                extractor,
                new OoxmlArchiveGuard(),
                reader,
                AttachmentContentLoader.newExecutor(),
                fileTimeout,
                turnTimeout,
                true);
        loaders.add(loader);
        return loader;
    }

    private PreparedAttachment validate(Path path) {
        return new AttachmentFileValidator().validate(new AttachmentRequest(
                UUID.randomUUID().toString(),
                "A-234567",
                path.getFileName().toString(),
                path.toAbsolutePath().toString()));
    }

    private PreparedAttachment manual(Path path, String mediaType, byte[] bytes) throws Exception {
        BasicFileAttributes attributes = Files.readAttributes(path, BasicFileAttributes.class);
        return new PreparedAttachment(
                new AttachmentMetadata(
                        UUID.randomUUID().toString(),
                        "A-234567",
                        path.getFileName().toString(),
                        path.toAbsolutePath().toString(),
                        mediaType,
                        bytes.length,
                        sha256(bytes),
                        AttachmentSource.SELECTED_FILE),
                path.toRealPath(),
                new PreparedAttachment.FileIdentity(
                        attributes.size(),
                        attributes.lastModifiedTime(),
                        attributes.fileKey() == null ? null : attributes.fileKey().toString()));
    }

    private static AttachmentTextSegment segment(PreparedAttachment attachment, String text) {
        return new AttachmentTextSegment(
                attachment.metadata().id(),
                attachment.metadata().displayId(),
                attachment.metadata().name(),
                attachment.metadata().mediaType(),
                text);
    }

    private static byte[] png() {
        return java.util.Base64.getDecoder().decode(
                "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNk+A8AAQUBAScY42YAAAAASUVORK5CYII=");
    }

    private static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (Exception exception) {
            throw new IllegalStateException(exception);
        }
    }

    private static final class ParserForLoader implements org.apache.tika.parser.Parser {

        private final AtomicInteger calls;
        private final CountDownLatch firstEntered;
        private final CountDownLatch firstExited;

        private ParserForLoader(
                AtomicInteger calls,
                CountDownLatch firstEntered,
                CountDownLatch firstExited
        ) {
            this.calls = calls;
            this.firstEntered = firstEntered;
            this.firstExited = firstExited;
        }

        @Override
        public java.util.Set<org.apache.tika.mime.MediaType> getSupportedTypes(
                org.apache.tika.parser.ParseContext context
        ) {
            return java.util.Set.of();
        }

        @Override
        public void parse(
                java.io.InputStream stream,
                org.xml.sax.ContentHandler handler,
                org.apache.tika.metadata.Metadata metadata,
                org.apache.tika.parser.ParseContext context
        ) throws java.io.IOException, org.xml.sax.SAXException {
            if (calls.incrementAndGet() == 1) {
                firstEntered.countDown();
                try {
                    Thread.sleep(10_000);
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    firstExited.countDown();
                    throw new java.io.InterruptedIOException("cancelled");
                }
                throw new AssertionError("first parse was not interrupted");
            }
            handler.startDocument();
            handler.startElement(
                    "http://www.w3.org/1999/xhtml",
                    "html",
                    "html",
                    new org.xml.sax.helpers.AttributesImpl());
            handler.startElement(
                    "http://www.w3.org/1999/xhtml",
                    "body",
                    "body",
                    new org.xml.sax.helpers.AttributesImpl());
            char[] text = "next-safe".toCharArray();
            handler.characters(text, 0, text.length);
            handler.endElement("http://www.w3.org/1999/xhtml", "body", "body");
            handler.endElement("http://www.w3.org/1999/xhtml", "html", "html");
            handler.endDocument();
        }
    }
}
