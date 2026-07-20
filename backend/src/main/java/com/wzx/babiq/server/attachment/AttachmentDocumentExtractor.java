package com.wzx.babiq.server.attachment;

import org.apache.tika.exception.EncryptedDocumentException;
import org.apache.tika.exception.WriteLimitReachedException;
import org.apache.tika.io.TikaInputStream;
import org.apache.tika.metadata.HttpHeaders;
import org.apache.tika.metadata.Metadata;
import org.apache.tika.parser.AutoDetectParser;
import org.apache.tika.parser.EmptyParser;
import org.apache.tika.parser.ParseContext;
import org.apache.tika.parser.Parser;
import org.apache.tika.parser.pdf.PDFParserConfig;
import org.apache.tika.sax.BodyContentHandler;
import org.apache.tika.sax.SecureContentHandler;
import org.springframework.stereotype.Component;

import java.io.ByteArrayInputStream;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Synchronous Tika extraction. Timeouts and queue limits are owned by {@link AttachmentContentLoader}.
 */
@Component
public class AttachmentDocumentExtractor {

    public static final int MAX_EXTRACTED_CHARACTERS = 100_000;
    private static final long SECURE_OUTPUT_THRESHOLD = 10_000;
    private static final long SECURE_MAXIMUM_COMPRESSION_RATIO = 100;

    private final Parser parser;

    public AttachmentDocumentExtractor() {
        this(new AutoDetectParser());
    }

    AttachmentDocumentExtractor(Parser parser) {
        this.parser = Objects.requireNonNull(parser, "parser");
    }

    public AttachmentTextSegment extract(PreparedAttachment attachment, byte[] bytes) {
        return extract(attachment, bytes, new ExtractionCancellation());
    }

    AttachmentTextSegment extract(
            PreparedAttachment attachment,
            byte[] bytes,
            ExtractionCancellation cancellation
    ) {
        Objects.requireNonNull(attachment, "attachment");
        Objects.requireNonNull(bytes, "bytes");
        Objects.requireNonNull(cancellation, "cancellation");
        if (cancellation.isCancelled()) {
            throw timeout();
        }
        Metadata metadata = new Metadata();
        metadata.set(HttpHeaders.CONTENT_TYPE, attachment.metadata().mediaType());
        metadata.set(HttpHeaders.CONTENT_LENGTH, Long.toString(bytes.length));
        ParseContext context = parseContext();

        try (TikaInputStream input = TikaInputStream.get(
                new CancellationAwareInputStream(new ByteArrayInputStream(bytes)))) {
            if (!cancellation.register(input)) {
                throw timeout();
            }
            BodyContentHandler body = new BodyContentHandler(MAX_EXTRACTED_CHARACTERS);
            SecureContentHandler secure = new SecureContentHandler(body, input);
            secure.setOutputThreshold(SECURE_OUTPUT_THRESHOLD);
            secure.setMaximumCompressionRatio(SECURE_MAXIMUM_COMPRESSION_RATIO);
            parser.parse(input, secure, metadata, context);
            String normalized = normalizeWhitespace(body.toString());
            if (normalized.length() > MAX_EXTRACTED_CHARACTERS) {
                throw textLimit();
            }
            return new AttachmentTextSegment(
                    attachment.metadata().id(),
                    attachment.metadata().displayId(),
                    attachment.metadata().name(),
                    attachment.metadata().mediaType(),
                    normalized);
        } catch (AttachmentException exception) {
            throw exception;
        } catch (Exception exception) {
            if (cancellation.isCancelled()) {
                throw timeout();
            }
            if (containsCause(exception, EncryptedDocumentException.class)) {
                throw new AttachmentException(
                        AttachmentErrorCode.ATTACHMENT_ENCRYPTED,
                        "附件已加密，无法读取");
            }
            if (WriteLimitReachedException.isWriteLimitReached(exception)) {
                throw textLimit();
            }
            if (Thread.currentThread().isInterrupted()
                    || containsCause(exception, java.nio.channels.ClosedByInterruptException.class)) {
                throw timeout();
            }
            throw new AttachmentException(
                    AttachmentErrorCode.ATTACHMENT_PARSE_FAILED,
                    "附件内容解析失败");
        } finally {
            cancellation.unregister();
        }
    }

    private static ParseContext parseContext() {
        ParseContext context = new ParseContext();
        context.set(Parser.class, EmptyParser.INSTANCE);
        PDFParserConfig pdf = new PDFParserConfig();
        pdf.setThrowOnEncryptedPayload(true);
        pdf.setExtractInlineImages(false);
        pdf.setExtractInlineImageMetadataOnly(false);
        pdf.setExtractActions(false);
        pdf.setOcrStrategy(PDFParserConfig.OCR_STRATEGY.NO_OCR);
        context.set(PDFParserConfig.class, pdf);
        return context;
    }

    private static String normalizeWhitespace(String raw) {
        String normalized = raw.replace("\r\n", "\n").replace('\r', '\n').replace('\t', ' ');
        normalized = normalized.replace('\u000b', ' ').replace('\f', '\n');
        normalized = normalized.replaceAll("[\\p{Zs} ]+", " ");
        normalized = normalized.replaceAll(" *\\n *", "\n");
        normalized = normalized.replaceAll("\\n{3,}", "\n\n");
        return normalized.strip();
    }

    private static AttachmentException textLimit() {
        return new AttachmentException(
                AttachmentErrorCode.ATTACHMENT_TEXT_LIMIT_EXCEEDED,
                "附件可提取文本超过 100,000 字符上限");
    }

    private static AttachmentException timeout() {
        return new AttachmentException(
                AttachmentErrorCode.ATTACHMENT_PARSE_TIMEOUT,
                "附件解析超时");
    }

    private static boolean containsCause(Throwable failure, Class<? extends Throwable> type) {
        Throwable current = failure;
        while (current != null) {
            if (type.isInstance(current)) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    static final class ExtractionCancellation {

        private final AtomicBoolean cancelled = new AtomicBoolean();
        private final AtomicReference<TikaInputStream> activeStream = new AtomicReference<>();

        boolean register(TikaInputStream stream) {
            Objects.requireNonNull(stream, "stream");
            if (cancelled.get()) {
                close(stream);
                return false;
            }
            if (!activeStream.compareAndSet(null, stream)) {
                close(stream);
                throw new IllegalStateException("an extraction stream is already registered");
            }
            if (cancelled.get()) {
                TikaInputStream registered = activeStream.getAndSet(null);
                close(registered);
                return false;
            }
            return true;
        }

        void unregister() {
            activeStream.set(null);
        }

        void cancel() {
            cancelled.set(true);
            close(activeStream.getAndSet(null));
        }

        boolean isCancelled() {
            return cancelled.get();
        }

        private static void close(TikaInputStream stream) {
            if (stream == null) {
                return;
            }
            try {
                stream.close();
            } catch (IOException ignored) {
                // Cancellation deliberately exposes only the stable timeout code.
            }
        }
    }

    private static final class CancellationAwareInputStream extends FilterInputStream {

        private final AtomicBoolean closed = new AtomicBoolean();

        private CancellationAwareInputStream(InputStream input) {
            super(input);
        }

        @Override
        public int read() throws IOException {
            requireOpen();
            return super.read();
        }

        @Override
        public int read(byte[] bytes, int offset, int length) throws IOException {
            requireOpen();
            return super.read(bytes, offset, length);
        }

        @Override
        public long skip(long count) throws IOException {
            requireOpen();
            return super.skip(count);
        }

        @Override
        public int available() throws IOException {
            requireOpen();
            return super.available();
        }

        @Override
        public void close() throws IOException {
            if (closed.compareAndSet(false, true)) {
                super.close();
            }
        }

        private void requireOpen() throws IOException {
            if (closed.get()) {
                throw new IOException("attachment extraction stream is closed");
            }
        }
    }
}
