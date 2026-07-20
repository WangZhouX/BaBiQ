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
import org.apache.poi.sl.usermodel.TextParagraph;
import org.apache.poi.xslf.usermodel.XMLSlideShow;
import org.apache.poi.xslf.usermodel.XSLFSlide;
import org.apache.poi.xslf.usermodel.XSLFTextBox;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.tika.parser.EmptyParser;
import org.apache.tika.parser.ParseContext;
import org.apache.tika.parser.Parser;
import org.apache.tika.parser.pdf.PDFParserConfig;
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
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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
