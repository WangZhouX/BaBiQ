package com.wzx.babiq.server.attachment;

import jakarta.annotation.PreDestroy;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.SeekableByteChannel;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.OpenOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Revalidates and loads local attachment content under bounded parsing resources.
 */
@Component
public final class AttachmentContentLoader implements AutoCloseable {

    public static final int MAX_TURN_EXTRACTED_CHARACTERS = 250_000;
    private static final int EXECUTOR_PARALLELISM = 2;
    private static final int EXECUTOR_QUEUE_CAPACITY = 8;
    private static final Duration DEFAULT_FILE_TIMEOUT = Duration.ofSeconds(10);
    private static final Duration DEFAULT_TURN_TIMEOUT = Duration.ofSeconds(30);
    private static final Set<String> ARCHIVE_EXTENSIONS = Set.of("zip", "rar", "7z", "tar", "gz");
    private static final Set<String> ARCHIVE_MEDIA_TYPES = Set.of(
            "application/zip",
            "application/x-rar-compressed",
            "application/vnd.rar",
            "application/x-7z-compressed",
            "application/x-tar",
            "application/gzip");
    private static final Set<String> OOXML_MEDIA_TYPES = Set.of(
            "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
            "application/vnd.openxmlformats-officedocument.presentationml.presentation");

    private final AttachmentDocumentExtractor extractor;
    private final OoxmlArchiveGuard archiveGuard;
    private final AttachmentBinaryReader reader;
    private final ThreadPoolExecutor executor;
    private final Duration fileTimeout;
    private final Duration turnTimeout;
    private final boolean ownsExecutor;

    public AttachmentContentLoader(
            AttachmentDocumentExtractor extractor,
            OoxmlArchiveGuard archiveGuard
    ) {
        this(
                extractor,
                archiveGuard,
                secureReader(),
                newExecutor(),
                DEFAULT_FILE_TIMEOUT,
                DEFAULT_TURN_TIMEOUT,
                true);
    }

    AttachmentContentLoader(
            AttachmentDocumentExtractor extractor,
            OoxmlArchiveGuard archiveGuard,
            AttachmentBinaryReader reader,
            ThreadPoolExecutor executor,
            Duration fileTimeout,
            Duration turnTimeout,
            boolean ownsExecutor
    ) {
        this.extractor = Objects.requireNonNull(extractor, "extractor");
        this.archiveGuard = Objects.requireNonNull(archiveGuard, "archiveGuard");
        this.reader = Objects.requireNonNull(reader, "reader");
        this.executor = Objects.requireNonNull(executor, "executor");
        this.fileTimeout = positive(fileTimeout, "fileTimeout");
        this.turnTimeout = positive(turnTimeout, "turnTimeout");
        this.ownsExecutor = ownsExecutor;
    }

    public List<AttachmentContent> load(List<PreparedAttachment> attachments) {
        List<PreparedAttachment> ordered = attachments == null ? List.of() : List.copyOf(attachments);
        if (ordered.isEmpty()) {
            return List.of();
        }
        long deadline = deadlineAfter(turnTimeout);
        List<AttachmentContent> contents = new ArrayList<>(ordered.size());
        long extractedCharacters = 0;
        for (PreparedAttachment attachment : ordered) {
            AttachmentContent content = loadOne(Objects.requireNonNull(attachment, "attachment"), deadline);
            if (content.textSegment() != null) {
                try {
                    extractedCharacters = Math.addExact(
                            extractedCharacters,
                            content.textSegment().originalCharacterCount());
                } catch (ArithmeticException exception) {
                    throw turnTextLimit();
                }
                if (extractedCharacters > MAX_TURN_EXTRACTED_CHARACTERS) {
                    throw turnTextLimit();
                }
            }
            contents.add(content);
        }
        return List.copyOf(contents);
    }

    private AttachmentContent loadOne(PreparedAttachment attachment, long deadline) {
        ParseExecution execution = new ParseExecution(attachment);
        Future<AttachmentContent> future;
        try {
            future = executor.submit(execution);
        } catch (RejectedExecutionException exception) {
            throw new AttachmentException(
                    AttachmentErrorCode.ATTACHMENT_PARSE_OVERLOADED,
                    "附件解析任务过多，请稍后重试");
        }

        long waitNanos = Math.min(fileTimeout.toNanos(), remainingNanos(deadline));
        if (waitNanos <= 0) {
            cancel(execution, future);
            throw timeout();
        }
        try {
            return future.get(waitNanos, TimeUnit.NANOSECONDS);
        } catch (TimeoutException exception) {
            cancel(execution, future);
            throw timeout();
        } catch (InterruptedException exception) {
            cancel(execution, future);
            Thread.currentThread().interrupt();
            throw timeout();
        } catch (ExecutionException exception) {
            Throwable cause = exception.getCause();
            if (cause instanceof AttachmentException attachmentFailure) {
                throw attachmentFailure;
            }
            throw new AttachmentException(
                    AttachmentErrorCode.ATTACHMENT_PARSE_FAILED,
                    "附件内容解析失败");
        }
    }

    private void cancel(ParseExecution execution, Future<?> future) {
        Thread worker = execution.worker;
        if (worker != null) {
            extractor.cancel(worker);
        }
        future.cancel(true);
    }

    private AttachmentContent loadNow(PreparedAttachment attachment) {
        rejectGenericArchive(attachment.metadata());
        byte[] bytes = reader.read(attachment);
        String mediaType = attachment.metadata().mediaType().toLowerCase(Locale.ROOT);
        if (mediaType.startsWith("image/")) {
            return AttachmentContent.image(attachment, bytes);
        }
        if (OOXML_MEDIA_TYPES.contains(mediaType)) {
            archiveGuard.validate(bytes);
        }
        AttachmentTextSegment segment = extractor.extract(attachment, bytes);
        return AttachmentContent.document(attachment, segment);
    }

    private static void rejectGenericArchive(AttachmentMetadata metadata) {
        String extension = extension(metadata.name());
        String mediaType = metadata.mediaType().toLowerCase(Locale.ROOT);
        if (ARCHIVE_EXTENSIONS.contains(extension) || ARCHIVE_MEDIA_TYPES.contains(mediaType)) {
            throw new AttachmentException(
                    AttachmentErrorCode.ATTACHMENT_TYPE_UNSUPPORTED,
                    "不支持压缩包附件");
        }
    }

    static AttachmentBinaryReader secureReader() {
        return AttachmentContentLoader::readAndRevalidate;
    }

    static ThreadPoolExecutor newExecutor() {
        AtomicInteger sequence = new AtomicInteger();
        ThreadFactory factory = runnable -> {
            Thread thread = new Thread(
                    runnable,
                    "attachment-parser-" + sequence.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        };
        return new ThreadPoolExecutor(
                EXECUTOR_PARALLELISM,
                EXECUTOR_PARALLELISM,
                0,
                TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(EXECUTOR_QUEUE_CAPACITY),
                factory,
                new ThreadPoolExecutor.AbortPolicy());
    }

    private static byte[] readAndRevalidate(PreparedAttachment attachment) {
        Path path = attachment.canonicalPath().toAbsolutePath().normalize();
        try {
            rejectLinkedPathSegments(path);
            Path actualCanonical = path.toRealPath();
            if (!actualCanonical.equals(path)) {
                throw changed();
            }
            BasicFileAttributes before = readRegularAttributes(path);
            requireExpectedIdentity(attachment, before);
            if (before.size() > AttachmentLimits.MAX_FILE_BYTES
                    || before.size() > Integer.MAX_VALUE) {
                throw changed();
            }

            MessageDigest digest = sha256Digest();
            byte[] bytes = new byte[(int) before.size()];
            Set<OpenOption> options = Set.of(StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS);
            try (SeekableByteChannel channel = Files.newByteChannel(path, options)) {
                if (channel.size() != before.size()) {
                    throw changed();
                }
                int offset = 0;
                while (offset < bytes.length) {
                    int chunk = Math.min(64 * 1024, bytes.length - offset);
                    ByteBuffer target = ByteBuffer.wrap(bytes, offset, chunk);
                    int read = channel.read(target);
                    if (read < 0) {
                        throw changed();
                    }
                    if (read == 0) {
                        if (Thread.currentThread().isInterrupted()) {
                            throw timeout();
                        }
                        continue;
                    }
                    digest.update(bytes, offset, read);
                    offset += read;
                }
                if (channel.read(ByteBuffer.allocate(1)) >= 0 || channel.size() != before.size()) {
                    throw changed();
                }
            }
            rejectLinkedPathSegments(path);
            BasicFileAttributes after = readRegularAttributes(path);
            requireExpectedIdentity(attachment, after);
            String actualSha = HexFormat.of().formatHex(digest.digest());
            if (!MessageDigest.isEqual(
                    actualSha.getBytes(java.nio.charset.StandardCharsets.US_ASCII),
                    attachment.metadata().sha256()
                            .toLowerCase(Locale.ROOT)
                            .getBytes(java.nio.charset.StandardCharsets.US_ASCII))) {
                throw changed();
            }
            return bytes;
        } catch (AttachmentException exception) {
            throw exception;
        } catch (NoSuchFileException exception) {
            throw changed();
        } catch (IOException | SecurityException | UnsupportedOperationException exception) {
            throw changed();
        }
    }

    private static BasicFileAttributes readRegularAttributes(Path path) throws IOException {
        if (Files.isSymbolicLink(path)) {
            throw changed();
        }
        BasicFileAttributes attributes = Files.readAttributes(
                path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        if (!attributes.isRegularFile() || attributes.isOther()) {
            throw changed();
        }
        return attributes;
    }

    private static void requireExpectedIdentity(
            PreparedAttachment attachment,
            BasicFileAttributes actual
    ) {
        PreparedAttachment.FileIdentity expected = attachment.identity();
        Object fileKey = actual.fileKey();
        String actualKey = fileKey == null ? null : fileKey.toString();
        if (actual.size() != expected.sizeBytes()
                || actual.size() != attachment.metadata().sizeBytes()
                || !actual.lastModifiedTime().equals(expected.lastModifiedTime())
                || expected.fileKey() != null
                && actualKey != null
                && !expected.fileKey().equals(actualKey)) {
            throw changed();
        }
    }

    private static void rejectLinkedPathSegments(Path path) throws IOException {
        Path absolute = path.toAbsolutePath().normalize();
        Path current = absolute.getRoot();
        if (current != null) {
            rejectLinkedSegment(current, false);
        }
        int index = 0;
        for (Path segment : absolute) {
            current = current == null ? segment : current.resolve(segment);
            index++;
            rejectLinkedSegment(current, index < absolute.getNameCount());
        }
    }

    private static void rejectLinkedSegment(Path path, boolean requireDirectory) throws IOException {
        BasicFileAttributes attributes = Files.readAttributes(
                path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        if (Files.isSymbolicLink(path)
                || attributes.isOther()
                || requireDirectory && !attributes.isDirectory()) {
            throw changed();
        }
    }

    private static MessageDigest sha256Digest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is required", exception);
        }
    }

    private static AttachmentException changed() {
        return new AttachmentException(
                AttachmentErrorCode.ATTACHMENT_CHANGED,
                "附件自发送后已变化，请重新选择");
    }

    private static AttachmentException timeout() {
        return new AttachmentException(
                AttachmentErrorCode.ATTACHMENT_PARSE_TIMEOUT,
                "附件解析超时");
    }

    private static AttachmentException turnTextLimit() {
        return new AttachmentException(
                AttachmentErrorCode.ATTACHMENT_TEXT_LIMIT_EXCEEDED,
                "本轮附件可提取文本超过 250,000 字符上限");
    }

    private static Duration positive(Duration value, String name) {
        Objects.requireNonNull(value, name);
        if (value.isZero() || value.isNegative()) {
            throw new IllegalArgumentException(name + " must be positive");
        }
        return value;
    }

    private static long deadlineAfter(Duration duration) {
        long now = System.nanoTime();
        try {
            return Math.addExact(now, duration.toNanos());
        } catch (ArithmeticException exception) {
            return Long.MAX_VALUE;
        }
    }

    private static long remainingNanos(long deadline) {
        if (deadline == Long.MAX_VALUE) {
            return Long.MAX_VALUE;
        }
        return deadline - System.nanoTime();
    }

    private static String extension(String name) {
        int separator = name.lastIndexOf('.');
        if (separator < 0 || separator == name.length() - 1) {
            return "";
        }
        return name.substring(separator + 1).toLowerCase(Locale.ROOT);
    }

    int executorParallelism() {
        return executor.getMaximumPoolSize();
    }

    int executorQueueCapacity() {
        return executor.getQueue().size() + executor.getQueue().remainingCapacity();
    }

    Duration fileTimeout() {
        return fileTimeout;
    }

    Duration turnTimeout() {
        return turnTimeout;
    }

    @Override
    @PreDestroy
    public void close() {
        if (ownsExecutor) {
            executor.shutdownNow();
        }
    }

    @FunctionalInterface
    interface AttachmentBinaryReader {
        byte[] read(PreparedAttachment attachment);
    }

    private final class ParseExecution implements java.util.concurrent.Callable<AttachmentContent> {

        private final PreparedAttachment attachment;
        private volatile Thread worker;

        private ParseExecution(PreparedAttachment attachment) {
            this.attachment = attachment;
        }

        @Override
        public AttachmentContent call() {
            worker = Thread.currentThread();
            try {
                return loadNow(attachment);
            } finally {
                worker = null;
            }
        }
    }
}
