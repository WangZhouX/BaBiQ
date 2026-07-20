package com.wzx.babiq.server.attachment;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import java.util.zip.CRC32;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class AttachmentFileValidatorTest {

    @TempDir
    Path tempDir;

    private final AttachmentFileValidator validator = new AttachmentFileValidator();

    @Test
    void acceptsSupportedImagesTextCodePdfAndOfficeDocumentsFromDetectedContent() throws Exception {
        List<Path> files = List.of(
                writePng("sample.png", 4, 3),
                write("sample.txt", "plain business text".getBytes(StandardCharsets.UTF_8)),
                write("sample.java", "class Example {}".getBytes(StandardCharsets.UTF_8)),
                write("sample.pdf", "%PDF-1.4\n1 0 obj\n<<>>\nendobj\n%%EOF".getBytes(StandardCharsets.US_ASCII)),
                writeOle("sample.doc"),
                writeOoxml("sample.docx",
                        "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
                        "word/document.xml"),
                writeOle("sample.xls"),
                writeOoxml("sample.xlsx",
                        "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                        "xl/workbook.xml"),
                writeOle("sample.ppt"),
                writeOoxml("sample.pptx",
                        "application/vnd.openxmlformats-officedocument.presentationml.presentation",
                        "ppt/presentation.xml")
        );

        for (int index = 0; index < files.size(); index++) {
            Path file = files.get(index);
            PreparedAttachment prepared = validator.validate(request(file, displayId(index)));

            assertThat(prepared.canonicalPath()).isEqualTo(file.toRealPath());
            assertThat(prepared.metadata().name()).isEqualTo(file.getFileName().toString());
            assertThat(prepared.metadata().mediaType()).isNotBlank();
            assertThat(prepared.metadata().sizeBytes()).isEqualTo(Files.size(file));
            assertThat(prepared.metadata().source()).isEqualTo(AttachmentSource.SELECTED_FILE);
        }
    }

    @Test
    void rejectsRelativeMissingDirectoryLinkUnsupportedExtensionAndContentSpoofing() throws Exception {
        assertCode(
                new AttachmentRequest(UUID.randomUUID().toString(), "A-234567", "relative.txt", "relative.txt"),
                AttachmentErrorCode.ATTACHMENT_PATH_INVALID);

        Path missing = tempDir.resolve("missing.txt");
        assertCode(request(missing, "A-234568"), AttachmentErrorCode.ATTACHMENT_NOT_FOUND);

        assertCode(request(tempDir, "A-234569"), AttachmentErrorCode.ATTACHMENT_NOT_REGULAR_FILE);

        Path archive = write("archive.zip", new byte[]{0x50, 0x4b, 0x03, 0x04});
        assertCode(request(archive, "A-23457A"), AttachmentErrorCode.ATTACHMENT_TYPE_UNSUPPORTED);

        Path spoofedPdf = write("spoofed.pdf", "not a pdf".getBytes(StandardCharsets.UTF_8));
        assertCode(request(spoofedPdf, "A-23457B"), AttachmentErrorCode.ATTACHMENT_TYPE_UNSUPPORTED);

        Path link = tempDir.resolve("linked.txt");
        try {
            Files.createSymbolicLink(link, write("target.txt", "target".getBytes(StandardCharsets.UTF_8)));
        } catch (IOException | UnsupportedOperationException | SecurityException exception) {
            assumeTrue(false, "symbolic links are unavailable on this filesystem");
        }
        assertCode(request(link, "A-23457C"), AttachmentErrorCode.ATTACHMENT_NOT_REGULAR_FILE);
    }

    @Test
    void enforcesPathFilenameAndSingleFileLimits() throws Exception {
        Path file = write("small.txt", "content".getBytes(StandardCharsets.UTF_8));
        String oversizedPath = "C:\\" + "a".repeat(AttachmentLimits.MAX_PATH_CHARACTERS);
        assertCode(
                new AttachmentRequest(UUID.randomUUID().toString(), "A-23457D", "small.txt", oversizedPath),
                AttachmentErrorCode.ATTACHMENT_PATH_INVALID);
        assertCode(
                new AttachmentRequest(UUID.randomUUID().toString(), "A-23457E",
                        "x".repeat(AttachmentLimits.MAX_FILE_NAME_CHARACTERS + 1), file.toString()),
                AttachmentErrorCode.ATTACHMENT_PATH_INVALID);

        Path large = tempDir.resolve("large.txt");
        try (FileChannel channel = FileChannel.open(
                large, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
            channel.position(AttachmentLimits.MAX_FILE_BYTES);
            channel.write(ByteBuffer.wrap(new byte[]{'x'}));
        }
        assertCode(request(large, "A-23457F"), AttachmentErrorCode.ATTACHMENT_FILE_TOO_LARGE);
    }

    @Test
    void rejectsImagesOverDimensionAndPixelLimitsWithoutDecodingTheRaster() throws Exception {
        Path tooWide = write("wide.png", pngHeader(AttachmentLimits.MAX_IMAGE_SIDE + 1, 1));
        assertCode(request(tooWide, "A-23457G"), AttachmentErrorCode.ATTACHMENT_IMAGE_TOO_LARGE);

        Path tooManyPixels = write("pixels.png", pngHeader(10_000, 5_001));
        assertCode(request(tooManyPixels, "A-23457H"), AttachmentErrorCode.ATTACHMENT_IMAGE_TOO_LARGE);
    }

    @Test
    void recordsStreamingSha256AndStableFileIdentityWithoutLeakingPaths() throws Exception {
        Path file = write("identity.txt", "stable identity".getBytes(StandardCharsets.UTF_8));

        PreparedAttachment prepared = validator.validate(request(file, "A-23457J"));

        String expectedHash = HexFormat.of().formatHex(
                java.security.MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(file)));
        assertThat(prepared.metadata().sha256()).isEqualTo(expectedHash);
        assertThat(prepared.identity().sizeBytes()).isEqualTo(Files.size(file));
        assertThat(prepared.identity().lastModifiedTime()).isNotNull();
        assertThat(prepared.toString()).doesNotContain(file.toString());
        assertThat(prepared.metadata().toString()).doesNotContain(file.toString());
    }

    @Test
    void errorAndPathBearingObjectsHaveSafePathFreeStringRepresentations() {
        String secretPath = tempDir.resolve("secret-client-contract.pdf").toString();
        AttachmentRequest request = new AttachmentRequest(
                UUID.randomUUID().toString(), "A-23457K", "secret-client-contract.pdf", secretPath);

        assertThat(request.toString()).doesNotContain(secretPath).doesNotContain("secret-client-contract.pdf");
        assertThatThrownBy(() -> validator.validate(request))
                .isInstanceOf(AttachmentException.class)
                .satisfies(error -> {
                    AttachmentException exception = (AttachmentException) error;
                    assertThat(exception.code()).isEqualTo(AttachmentErrorCode.ATTACHMENT_NOT_FOUND);
                    assertThat(exception.safeMessage()).doesNotContain(secretPath);
                    assertThat(exception.toString()).doesNotContain(secretPath);
                });
    }

    private void assertCode(AttachmentRequest request, AttachmentErrorCode expectedCode) {
        assertThatThrownBy(() -> validator.validate(request))
                .isInstanceOf(AttachmentException.class)
                .extracting(error -> ((AttachmentException) error).code())
                .isEqualTo(expectedCode);
    }

    private AttachmentRequest request(Path path, String displayId) {
        return new AttachmentRequest(
                UUID.randomUUID().toString(),
                displayId,
                path.getFileName() == null ? "attachment" : path.getFileName().toString(),
                path.toString());
    }

    private Path write(String name, byte[] bytes) throws IOException {
        return Files.write(tempDir.resolve(name), bytes);
    }

    private Path writePng(String name, int width, int height) throws IOException {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        Path path = tempDir.resolve(name);
        ImageIO.write(image, "png", path.toFile());
        return path;
    }

    private Path writeOle(String name) throws IOException {
        byte[] bytes = new byte[512];
        byte[] signature = {
                (byte) 0xd0, (byte) 0xcf, 0x11, (byte) 0xe0,
                (byte) 0xa1, (byte) 0xb1, 0x1a, (byte) 0xe1
        };
        System.arraycopy(signature, 0, bytes, 0, signature.length);
        return write(name, bytes);
    }

    private Path writeOoxml(String name, String contentType, String payloadName) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(output)) {
            zip.putNextEntry(new ZipEntry("[Content_Types].xml"));
            zip.write(("""
                    <?xml version="1.0" encoding="UTF-8"?>
                    <Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">
                      <Override PartName="/%s" ContentType="%s"/>
                    </Types>
                    """).formatted(payloadName, contentType).getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
            zip.putNextEntry(new ZipEntry(payloadName));
            zip.write("<root/>".getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
        }
        return write(name, output.toByteArray());
    }

    private static byte[] pngHeader(int width, int height) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        output.write(new byte[]{(byte) 0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a});
        writePngChunk(output, "IHDR", ByteBuffer.allocate(13)
                .order(ByteOrder.BIG_ENDIAN)
                .putInt(width)
                .putInt(height)
                .put((byte) 8)
                .put((byte) 6)
                .put((byte) 0)
                .put((byte) 0)
                .put((byte) 0)
                .array());
        writePngChunk(output, "IEND", new byte[0]);
        return output.toByteArray();
    }

    private static void writePngChunk(ByteArrayOutputStream output, String type, byte[] data) throws IOException {
        byte[] typeBytes = type.getBytes(StandardCharsets.US_ASCII);
        output.write(ByteBuffer.allocate(4).order(ByteOrder.BIG_ENDIAN).putInt(data.length).array());
        output.write(typeBytes);
        output.write(data);
        CRC32 crc = new CRC32();
        crc.update(typeBytes);
        crc.update(data);
        output.write(ByteBuffer.allocate(4).order(ByteOrder.BIG_ENDIAN).putInt((int) crc.getValue()).array());
    }

    private static String displayId(int index) {
        final String alphabet = "23456789ABCDEFGHJKLMNPQRSTUVWXYZ";
        int value = index + 2;
        StringBuilder builder = new StringBuilder("A-");
        for (int position = 0; position < 6; position++) {
            builder.append(alphabet.charAt(value % alphabet.length()));
            value /= alphabet.length();
        }
        return builder.toString();
    }
}
