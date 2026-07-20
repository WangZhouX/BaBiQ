package com.wzx.babiq.server.attachment;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.encryption.AccessPermission;
import org.apache.pdfbox.pdmodel.encryption.StandardProtectionPolicy;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.poi.hslf.usermodel.HSLFSlide;
import org.apache.poi.hslf.usermodel.HSLFSlideShow;
import org.apache.poi.hslf.usermodel.HSLFTextBox;
import org.apache.poi.hssf.usermodel.HSSFWorkbook;
import org.apache.poi.openxml4j.opc.PackagePart;
import org.apache.poi.openxml4j.opc.PackagePartName;
import org.apache.poi.openxml4j.opc.PackagingURIHelper;
import org.apache.poi.openxml4j.opc.TargetMode;
import org.apache.poi.sl.usermodel.TextParagraph;
import org.apache.poi.xslf.usermodel.XMLSlideShow;
import org.apache.poi.xslf.usermodel.XSLFSlide;
import org.apache.poi.xslf.usermodel.XSLFTextBox;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.tika.parser.EmptyParser;
import org.apache.tika.parser.AutoDetectParser;
import org.apache.tika.parser.ParseContext;
import org.apache.tika.parser.Parser;
import org.apache.tika.parser.pdf.PDFParserConfig;
import org.apache.tika.sax.BodyContentHandler;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.xml.sax.ContentHandler;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

class AttachmentDocumentExtractorTest {

    @TempDir
    Path tempDir;

    private final AttachmentDocumentExtractor extractor = new AttachmentDocumentExtractor();

    @Test
    void extractsGeneratedUtf8PdfAndOfficeFixtures() throws Exception {
        List<Fixture> fixtures = List.of(
                new Fixture("sample.txt", "text/plain",
                        "第一行\r\n\r\n  第二行\t值".getBytes(StandardCharsets.UTF_8), "第二行"),
                new Fixture("sample.pdf", "application/pdf", pdf("PDF marker"), "PDF marker"),
                new Fixture("sample.docx",
                        "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
                        docx("DOCX marker"), "DOCX marker"),
                new Fixture("sample.xlsx",
                        "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                        xlsx("XLSX marker"), "XLSX marker"),
                new Fixture("sample.pptx",
                        "application/vnd.openxmlformats-officedocument.presentationml.presentation",
                        pptx("PPTX marker"), "PPTX marker"),
                new Fixture("sample.xls", "application/vnd.ms-excel",
                        xls("XLS marker"), "XLS marker"),
                new Fixture("sample.ppt", "application/vnd.ms-powerpoint",
                        ppt("PPT marker"), "PPT marker"),
                new Fixture("sample.doc", "application/msword",
                        LegacyOfficeTestFixtures.word6Document(), "quick brown fox"));

        for (Fixture fixture : fixtures) {
            AttachmentTextSegment segment;
            try {
                segment = extractor.extract(
                        prepared(fixture.name(), fixture.mediaType(), fixture.bytes()),
                        fixture.bytes());
            } catch (AttachmentException failure) {
                throw new AssertionError(
                        fixture.name() + " failed with " + failure.code(),
                        failure);
            }
            assertThat(segment.text()).as(fixture.name()).containsIgnoringCase(fixture.marker());
            assertThat(segment.text()).doesNotContain("\r").doesNotContain("\t");
            assertThat(segment.originalCharacterCount()).isEqualTo(segment.text().length());
            assertThat(segment.toString())
                    .doesNotContain(fixture.marker())
                    .doesNotContain(tempDir.toString())
                    .doesNotContain(sha256(fixture.bytes()));
        }
    }

    @Test
    void rejectsMoreThanOneHundredThousandExtractedCharactersWithoutReturningPartialText() {
        byte[] tooMuch = "x".repeat(100_001).getBytes(StandardCharsets.UTF_8);

        assertThatThrownBy(() -> extractor.extract(
                prepared("large.txt", "text/plain", tooMuch), tooMuch))
                .isInstanceOfSatisfying(AttachmentException.class, failure ->
                        assertThat(failure.code())
                                .isEqualTo(AttachmentErrorCode.ATTACHMENT_TEXT_LIMIT_EXCEEDED))
                .hasMessageNotContaining("xxxx");
    }

    @Test
    void mapsEncryptedPdfToStableEncryptedCode() throws Exception {
        byte[] encrypted = encryptedPdf();

        assertThatThrownBy(() -> extractor.extract(
                prepared("secret.pdf", "application/pdf", encrypted), encrypted))
                .isInstanceOfSatisfying(AttachmentException.class, failure ->
                        assertThat(failure.code()).isEqualTo(AttachmentErrorCode.ATTACHMENT_ENCRYPTED))
                .hasMessageNotContaining(tempDir.toString())
                .hasMessageNotContaining("owner-password")
                .hasMessageNotContaining("user-password");
    }

    @Test
    void installsEmptyEmbeddedParserAndSafePdfConfiguration() throws Exception {
        AtomicReference<ParseContext> observed = new AtomicReference<>();
        Parser parser = new Parser() {
            @Override
            public java.util.Set<org.apache.tika.mime.MediaType> getSupportedTypes(
                    ParseContext context
            ) {
                return java.util.Set.of();
            }

            @Override
            public void parse(
                    InputStream stream,
                    ContentHandler handler,
                    org.apache.tika.metadata.Metadata metadata,
                    ParseContext context
            ) throws java.io.IOException, org.xml.sax.SAXException {
                observed.set(context);
                handler.startDocument();
                char[] text = "safe body".toCharArray();
                handler.characters(text, 0, text.length);
                handler.endDocument();
            }
        };
        AttachmentDocumentExtractor configured = new AttachmentDocumentExtractor(parser);
        byte[] bytes = "safe body".getBytes(StandardCharsets.UTF_8);

        configured.extract(prepared("sample.txt", "text/plain", bytes), bytes);

        assertThat(observed.get().get(Parser.class)).isSameAs(EmptyParser.INSTANCE);
        PDFParserConfig pdf = observed.get().get(PDFParserConfig.class);
        assertThat(pdf.isThrowOnEncryptedPayload()).isTrue();
        assertThat(pdf.isExtractInlineImages()).isFalse();
    }

    @Test
    void realDocxEmbeddedTextIsRecognizedByTikaButExcludedFromAttachmentText() throws Exception {
        String outer = "OUTER-DOCUMENT-SENTINEL";
        String embedded = "EMBEDDED-BODY-MUST-NOT-APPEAR";
        byte[] fixture = docxWithEmbeddedText(outer, embedded);
        assertThat(extractWithEmbeddedParsing(fixture)).contains(outer).contains(embedded);

        AttachmentTextSegment segment = extractor.extract(
                prepared(
                        "embedded.docx",
                        "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
                        fixture),
                fixture);

        assertThat(segment.text()).contains(outer).doesNotContain(embedded);
    }

    @Test
    void cancellationBeforeStreamRegistrationPreventsParserInvocation() {
        Parser parser = mock(Parser.class);
        AttachmentDocumentExtractor configured = new AttachmentDocumentExtractor(parser);
        AttachmentDocumentExtractor.ExtractionCancellation cancellation =
                new AttachmentDocumentExtractor.ExtractionCancellation();
        cancellation.cancel();
        byte[] bytes = "cancelled".getBytes(StandardCharsets.UTF_8);

        assertThatThrownBy(() -> configured.extract(
                prepared("cancelled.txt", "text/plain", bytes), bytes, cancellation))
                .isInstanceOfSatisfying(AttachmentException.class, failure ->
                        assertThat(failure.code())
                                .isEqualTo(AttachmentErrorCode.ATTACHMENT_PARSE_TIMEOUT));
        try {
            verify(parser, never()).parse(any(), any(), any(), any());
        } catch (Exception impossible) {
            throw new AssertionError(impossible);
        }
    }

    @Test
    void cancellationClosesOnlyItsExecutionAndCannotAffectReusedWorker() throws Exception {
        CountDownLatch firstEntered = new CountDownLatch(1);
        CountDownLatch firstContinue = new CountDownLatch(1);
        CountDownLatch secondEntered = new CountDownLatch(1);
        CountDownLatch secondContinue = new CountDownLatch(1);
        AtomicBoolean firstStreamClosed = new AtomicBoolean();
        AtomicInteger calls = new AtomicInteger();
        AtomicReference<Thread> firstThread = new AtomicReference<>();
        AtomicReference<Thread> secondThread = new AtomicReference<>();
        Parser parser = parserWithCancellationRace(
                calls,
                firstEntered,
                firstContinue,
                secondEntered,
                secondContinue,
                firstStreamClosed,
                firstThread,
                secondThread);
        AttachmentDocumentExtractor configured = new AttachmentDocumentExtractor(parser);
        ExecutorService worker = Executors.newSingleThreadExecutor();
        AttachmentDocumentExtractor.ExtractionCancellation firstCancellation =
                new AttachmentDocumentExtractor.ExtractionCancellation();
        AttachmentDocumentExtractor.ExtractionCancellation secondCancellation =
                new AttachmentDocumentExtractor.ExtractionCancellation();
        byte[] bytes = "body".getBytes(StandardCharsets.UTF_8);
        PreparedAttachment attachment = prepared("body.txt", "text/plain", bytes);

        try {
            Future<AttachmentTextSegment> first =
                    worker.submit(() -> configured.extract(attachment, bytes, firstCancellation));
            assertThat(firstEntered.await(2, TimeUnit.SECONDS)).isTrue();
            firstCancellation.cancel();
            firstContinue.countDown();
            assertThatThrownBy(() -> await(first))
                    .isInstanceOfSatisfying(AttachmentException.class, failure ->
                            assertThat(failure.code())
                                    .isEqualTo(AttachmentErrorCode.ATTACHMENT_PARSE_TIMEOUT));
            assertThat(firstStreamClosed).isTrue();

            Future<AttachmentTextSegment> second =
                    worker.submit(() -> configured.extract(attachment, bytes, secondCancellation));
            assertThat(secondEntered.await(2, TimeUnit.SECONDS)).isTrue();
            firstCancellation.cancel();
            secondContinue.countDown();
            assertThat(second.get(2, TimeUnit.SECONDS).text()).isEqualTo("second-safe");
            assertThat(secondCancellation.isCancelled()).isFalse();
            assertThat(firstThread.get()).isSameAs(secondThread.get());
        } finally {
            firstContinue.countDown();
            secondContinue.countDown();
            worker.shutdownNow();
        }
    }

    private PreparedAttachment prepared(String name, String mediaType, byte[] bytes) {
        return new PreparedAttachment(
                new AttachmentMetadata(
                        UUID.randomUUID().toString(),
                        "A-234567",
                        name,
                        tempDir.resolve(name).toString(),
                        mediaType,
                        bytes.length,
                        sha256(bytes),
                        AttachmentSource.SELECTED_FILE),
                tempDir.resolve(name).toAbsolutePath(),
                new PreparedAttachment.FileIdentity(bytes.length, FileTime.fromMillis(1), "fixture"));
    }

    private static byte[] pdf(String marker) throws Exception {
        try (PDDocument document = new PDDocument();
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            PDPage page = new PDPage();
            document.addPage(page);
            try (PDPageContentStream content = new PDPageContentStream(document, page)) {
                content.beginText();
                content.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
                content.newLineAtOffset(40, 700);
                content.showText(marker);
                content.endText();
            }
            document.save(output);
            return output.toByteArray();
        }
    }

    private static byte[] encryptedPdf() throws Exception {
        byte[] plain = pdf("private marker");
        try (PDDocument document = Loader.loadPDF(plain);
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            StandardProtectionPolicy policy = new StandardProtectionPolicy(
                    "owner-password", "user-password", new AccessPermission());
            policy.setEncryptionKeyLength(128);
            document.protect(policy);
            document.save(output);
            return output.toByteArray();
        }
    }

    private static byte[] docx(String marker) throws Exception {
        try (XWPFDocument document = new XWPFDocument();
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            document.createParagraph().createRun().setText(marker);
            document.write(output);
            return output.toByteArray();
        }
    }

    private static byte[] docxWithEmbeddedText(String outer, String embedded) throws Exception {
        try (XWPFDocument document = new XWPFDocument();
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            document.createParagraph().createRun().setText(outer);
            PackagePartName name =
                    PackagingURIHelper.createPartName("/word/embeddings/embedded1.txt");
            PackagePart embeddedPart = document.getPackage().createPart(name, "text/plain");
            try (java.io.OutputStream stream = embeddedPart.getOutputStream()) {
                stream.write(embedded.getBytes(StandardCharsets.UTF_8));
            }
            document.getPackagePart().addRelationship(
                    name,
                    TargetMode.INTERNAL,
                    "http://schemas.openxmlformats.org/officeDocument/2006/relationships/package");
            document.write(output);
            return output.toByteArray();
        }
    }

    private static String extractWithEmbeddedParsing(byte[] fixture) throws Exception {
        AutoDetectParser parser = new AutoDetectParser();
        BodyContentHandler body = new BodyContentHandler(10_000);
        ParseContext context = new ParseContext();
        context.set(Parser.class, parser);
        try (InputStream input = new java.io.ByteArrayInputStream(fixture)) {
            parser.parse(input, body, new org.apache.tika.metadata.Metadata(), context);
        }
        return body.toString();
    }

    private static Parser parserWithCancellationRace(
            AtomicInteger calls,
            CountDownLatch firstEntered,
            CountDownLatch firstContinue,
            CountDownLatch secondEntered,
            CountDownLatch secondContinue,
            AtomicBoolean firstStreamClosed,
            AtomicReference<Thread> firstThread,
            AtomicReference<Thread> secondThread
    ) {
        return new Parser() {
            @Override
            public java.util.Set<org.apache.tika.mime.MediaType> getSupportedTypes(
                    ParseContext context
            ) {
                return java.util.Set.of();
            }

            @Override
            public void parse(
                    InputStream stream,
                    ContentHandler handler,
                    org.apache.tika.metadata.Metadata metadata,
                    ParseContext context
            ) throws java.io.IOException, org.xml.sax.SAXException {
                int call = calls.incrementAndGet();
                if (call == 1) {
                    firstThread.set(Thread.currentThread());
                    firstEntered.countDown();
                    awaitLatch(firstContinue);
                    try {
                        stream.read();
                    } catch (java.io.IOException expected) {
                        firstStreamClosed.set(true);
                        throw expected;
                    }
                    throw new AssertionError("cancelled stream remained readable");
                }
                secondThread.set(Thread.currentThread());
                secondEntered.countDown();
                awaitLatch(secondContinue);
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
                char[] text = "second-safe".toCharArray();
                handler.characters(text, 0, text.length);
                handler.endElement("http://www.w3.org/1999/xhtml", "body", "body");
                handler.endElement("http://www.w3.org/1999/xhtml", "html", "html");
                handler.endDocument();
            }
        };
    }

    private static void awaitLatch(CountDownLatch latch) throws org.xml.sax.SAXException {
        try {
            if (!latch.await(2, TimeUnit.SECONDS)) {
                throw new org.xml.sax.SAXException("test latch timeout");
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new org.xml.sax.SAXException(exception);
        }
    }

    private static AttachmentTextSegment await(Future<AttachmentTextSegment> future)
            throws Throwable {
        try {
            return future.get(2, TimeUnit.SECONDS);
        } catch (ExecutionException exception) {
            throw exception.getCause();
        }
    }

    private static byte[] xlsx(String marker) throws Exception {
        try (XSSFWorkbook workbook = new XSSFWorkbook();
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            workbook.createSheet("Sheet1").createRow(0).createCell(0).setCellValue(marker);
            workbook.write(output);
            return output.toByteArray();
        }
    }

    private static byte[] pptx(String marker) throws Exception {
        try (XMLSlideShow show = new XMLSlideShow();
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            XSLFSlide slide = show.createSlide();
            XSLFTextBox box = slide.createTextBox();
            box.setText(marker);
            show.write(output);
            return output.toByteArray();
        }
    }

    private static byte[] xls(String marker) throws Exception {
        try (HSSFWorkbook workbook = new HSSFWorkbook();
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            workbook.createSheet("Sheet1").createRow(0).createCell(0).setCellValue(marker);
            workbook.write(output);
            return output.toByteArray();
        }
    }

    private static byte[] ppt(String marker) throws Exception {
        try (HSLFSlideShow show = new HSLFSlideShow();
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            HSLFSlide slide = show.createSlide();
            HSLFTextBox box = new HSLFTextBox();
            box.setText(marker);
            box.setHorizontalCentered(true);
            box.getTextParagraphs().getFirst().setTextAlign(TextParagraph.TextAlign.CENTER);
            slide.addShape(box);
            show.write(output);
            return output.toByteArray();
        }
    }

    private static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (Exception exception) {
            throw new IllegalStateException(exception);
        }
    }

    private record Fixture(String name, String mediaType, byte[] bytes, String marker) {
    }
}
