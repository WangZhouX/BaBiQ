package com.wzx.babiq.server.attachment;

import org.apache.tika.Tika;
import org.apache.tika.io.TikaInputStream;
import org.springframework.stereotype.Component;

import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileTime;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Iterator;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Authoritative local-file validation performed before a Turn is created.
 */
@Component
public class AttachmentFileValidator {

    private static final int HASH_BUFFER_BYTES = 64 * 1024;
    private static final int CONTENT_TYPE_XML_LIMIT = 256 * 1024;
    private static final Set<String> TEXT_EXTENSIONS = Set.of(
            "txt", "md", "markdown", "csv", "json", "xml", "yaml", "yml", "log",
            "java", "kt", "kts", "js", "jsx", "ts", "tsx", "py", "sql", "sh", "bash",
            "ps1", "html", "htm", "css", "scss", "properties", "gradle", "groovy",
            "c", "h", "cpp", "hpp", "cs", "go", "rs", "rb", "php", "swift", "toml", "ini");
    private static final Set<String> TEXT_MEDIA_TYPES = Set.of(
            "application/json",
            "application/xml",
            "application/x-yaml",
            "application/yaml",
            "application/javascript",
            "application/x-javascript",
            "application/sql",
            "application/x-sh");
    private static final Map<String, String> STRICT_MEDIA_TYPES = Map.ofEntries(
            Map.entry("png", "image/png"),
            Map.entry("jpg", "image/jpeg"),
            Map.entry("jpeg", "image/jpeg"),
            Map.entry("gif", "image/gif"),
            Map.entry("webp", "image/webp"),
            Map.entry("pdf", "application/pdf"),
            Map.entry("doc", "application/msword"),
            Map.entry("xls", "application/vnd.ms-excel"),
            Map.entry("ppt", "application/vnd.ms-powerpoint"),
            Map.entry("docx", "application/vnd.openxmlformats-officedocument.wordprocessingml.document"),
            Map.entry("xlsx", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"),
            Map.entry("pptx", "application/vnd.openxmlformats-officedocument.presentationml.presentation"));
    private static final Set<String> LEGACY_OFFICE_EXTENSIONS = Set.of("doc", "xls", "ppt");
    private static final Set<String> OOXML_EXTENSIONS = Set.of("docx", "xlsx", "pptx");
    private static final byte[] OLE_SIGNATURE = {
            (byte) 0xd0, (byte) 0xcf, 0x11, (byte) 0xe0,
            (byte) 0xa1, (byte) 0xb1, 0x1a, (byte) 0xe1
    };

    private final Tika tika;

    public AttachmentFileValidator() {
        this(new Tika());
    }

    AttachmentFileValidator(Tika tika) {
        this.tika = tika;
    }

    public PreparedAttachment validate(AttachmentRequest request) {
        requireRequestShape(request);
        Path requestedPath = parseAbsolutePath(request.localPath());
        BasicFileAttributes initialAttributes = readInitialAttributes(requestedPath);
        if (initialAttributes.size() > AttachmentLimits.MAX_FILE_BYTES) {
            throw failure(
                    AttachmentErrorCode.ATTACHMENT_FILE_TOO_LARGE,
                    "附件超过 20 MiB 的单文件上限");
        }

        Path canonicalPath = toCanonicalPath(requestedPath);
        BasicFileAttributes canonicalAttributes = readFinalAttributes(canonicalPath);
        if (!sameIdentity(identity(initialAttributes), identity(canonicalAttributes))) {
            throw failure(AttachmentErrorCode.ATTACHMENT_CHANGED, "附件在读取期间发生变化，请重新选择");
        }
        initialAttributes = canonicalAttributes;
        String actualName = requireActualFileName(canonicalPath);
        String extension = extension(actualName);
        if (!TEXT_EXTENSIONS.contains(extension) && !STRICT_MEDIA_TYPES.containsKey(extension)) {
            throw unsupported();
        }

        String mediaType = detectMediaType(canonicalPath, actualName);
        verifyContentMatchesExtension(canonicalPath, extension, mediaType);
        if (mediaType.startsWith("image/")) {
            validateImageDimensions(canonicalPath, mediaType);
        }

        String sha256 = calculateSha256(canonicalPath);
        BasicFileAttributes finalAttributes = readFinalAttributes(canonicalPath);
        PreparedAttachment.FileIdentity initialIdentity = identity(initialAttributes);
        PreparedAttachment.FileIdentity finalIdentity = identity(finalAttributes);
        if (!sameIdentity(initialIdentity, finalIdentity)) {
            throw failure(AttachmentErrorCode.ATTACHMENT_CHANGED, "附件在读取期间发生变化，请重新选择");
        }

        AttachmentMetadata metadata = new AttachmentMetadata(
                request.id(),
                request.displayId(),
                actualName,
                canonicalPath.toString(),
                normalizedMediaType(extension, mediaType),
                finalAttributes.size(),
                sha256,
                AttachmentSource.SELECTED_FILE);
        return new PreparedAttachment(metadata, canonicalPath, finalIdentity);
    }

    private static void requireRequestShape(AttachmentRequest request) {
        if (request == null) {
            throw failure(AttachmentErrorCode.ATTACHMENT_EMPTY, "附件描述不能为空");
        }
        if (request.name() == null || request.name().isBlank()
                || request.name().length() > AttachmentLimits.MAX_FILE_NAME_CHARACTERS) {
            throw failure(AttachmentErrorCode.ATTACHMENT_PATH_INVALID, "附件名称无效");
        }
        if (request.localPath() == null || request.localPath().isBlank()
                || request.localPath().length() > AttachmentLimits.MAX_PATH_CHARACTERS) {
            throw failure(AttachmentErrorCode.ATTACHMENT_PATH_INVALID, "附件路径无效");
        }
    }

    private static Path parseAbsolutePath(String rawPath) {
        try {
            Path path = Path.of(rawPath);
            if (!path.isAbsolute()) {
                throw failure(AttachmentErrorCode.ATTACHMENT_PATH_INVALID, "附件路径必须是绝对路径");
            }
            return path.toAbsolutePath().normalize();
        } catch (InvalidPathException exception) {
            throw failure(AttachmentErrorCode.ATTACHMENT_PATH_INVALID, "附件路径无效");
        }
    }

    private static BasicFileAttributes readInitialAttributes(Path path) {
        try {
            if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) {
                throw failure(AttachmentErrorCode.ATTACHMENT_NOT_FOUND, "附件已不存在");
            }
            if (Files.isSymbolicLink(path)) {
                throw failure(
                        AttachmentErrorCode.ATTACHMENT_NOT_REGULAR_FILE,
                        "附件必须是普通文件，不能是符号链接");
            }
            BasicFileAttributes attributes = Files.readAttributes(
                    path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            if (!attributes.isRegularFile() || attributes.isOther()) {
                throw failure(
                        AttachmentErrorCode.ATTACHMENT_NOT_REGULAR_FILE,
                        "附件必须是普通文件");
            }
            return attributes;
        } catch (NoSuchFileException exception) {
            throw failure(AttachmentErrorCode.ATTACHMENT_NOT_FOUND, "附件已不存在");
        } catch (AttachmentException exception) {
            throw exception;
        } catch (IOException | SecurityException exception) {
            throw failure(
                    AttachmentErrorCode.ATTACHMENT_PATH_INVALID,
                    "无法安全访问附件",
                    exception);
        }
    }

    private static Path toCanonicalPath(Path path) {
        try {
            return path.toRealPath();
        } catch (NoSuchFileException exception) {
            throw failure(AttachmentErrorCode.ATTACHMENT_NOT_FOUND, "附件已不存在");
        } catch (IOException | SecurityException exception) {
            throw failure(
                    AttachmentErrorCode.ATTACHMENT_PATH_INVALID,
                    "无法规范化附件路径",
                    exception);
        }
    }

    private static String requireActualFileName(Path path) {
        Path fileName = path.getFileName();
        if (fileName == null) {
            throw failure(AttachmentErrorCode.ATTACHMENT_PATH_INVALID, "附件名称无效");
        }
        String value = fileName.toString();
        if (value.isBlank() || value.length() > AttachmentLimits.MAX_FILE_NAME_CHARACTERS) {
            throw failure(AttachmentErrorCode.ATTACHMENT_PATH_INVALID, "附件名称无效");
        }
        return value;
    }

    private String detectMediaType(Path path, String actualName) {
        try (TikaInputStream input = TikaInputStream.get(path)) {
            String mediaType = tika.detect(input, actualName);
            if (mediaType == null || mediaType.isBlank()) {
                throw unsupported();
            }
            return mediaType.toLowerCase(Locale.ROOT);
        } catch (AttachmentException exception) {
            throw exception;
        } catch (IOException | RuntimeException exception) {
            throw failure(
                    AttachmentErrorCode.ATTACHMENT_TYPE_UNSUPPORTED,
                    "无法识别附件类型",
                    exception);
        }
    }

    private static void verifyContentMatchesExtension(Path path, String extension, String detectedMediaType) {
        if (TEXT_EXTENSIONS.contains(extension)) {
            if (!isTextMediaType(detectedMediaType)) {
                throw unsupported();
            }
            return;
        }

        String expected = STRICT_MEDIA_TYPES.get(extension);
        if (expected == null) {
            throw unsupported();
        }
        if ("pdf".equals(extension)) {
            if (!"application/pdf".equals(detectedMediaType) || !hasPrefix(path, "%PDF-".getBytes())) {
                throw unsupported();
            }
            return;
        }
        if (LEGACY_OFFICE_EXTENSIONS.contains(extension)) {
            if (!hasPrefix(path, OLE_SIGNATURE)) {
                throw unsupported();
            }
            return;
        }
        if (OOXML_EXTENSIONS.contains(extension)) {
            if (!hasOoxmlContentType(path, expected)) {
                throw unsupported();
            }
            return;
        }
        if (!expected.equals(detectedMediaType)) {
            throw unsupported();
        }
    }

    private static boolean isTextMediaType(String mediaType) {
        return mediaType.startsWith("text/") || TEXT_MEDIA_TYPES.contains(mediaType);
    }

    private static String normalizedMediaType(String extension, String detectedMediaType) {
        if (TEXT_EXTENSIONS.contains(extension)) {
            return detectedMediaType;
        }
        return STRICT_MEDIA_TYPES.getOrDefault(extension, detectedMediaType);
    }

    private static boolean hasPrefix(Path path, byte[] expected) {
        try (InputStream input = Files.newInputStream(path)) {
            byte[] actual = input.readNBytes(expected.length);
            return MessageDigest.isEqual(expected, actual);
        } catch (IOException exception) {
            throw failure(AttachmentErrorCode.ATTACHMENT_CHANGED, "附件在读取期间变得不可用");
        }
    }

    private static boolean hasOoxmlContentType(Path path, String expectedMediaType) {
        try (ZipFile zip = new ZipFile(path.toFile())) {
            ZipEntry entry = zip.getEntry("[Content_Types].xml");
            if (entry == null
                    || entry.isDirectory()
                    || entry.getSize() < 0
                    || entry.getSize() > CONTENT_TYPE_XML_LIMIT) {
                return false;
            }
            long compressedSize = entry.getCompressedSize();
            if (compressedSize <= 0 || entry.getSize() / (double) compressedSize > 100.0d) {
                return false;
            }
            try (InputStream input = zip.getInputStream(entry)) {
                byte[] content = input.readNBytes(CONTENT_TYPE_XML_LIMIT + 1);
                if (content.length > CONTENT_TYPE_XML_LIMIT) {
                    return false;
                }
                return new String(content, java.nio.charset.StandardCharsets.UTF_8)
                        .contains(expectedMediaType);
            }
        } catch (IOException exception) {
            return false;
        }
    }

    private static void validateImageDimensions(Path path, String mediaType) {
        ImageDimensions dimensions = "image/webp".equals(mediaType)
                ? readWebpDimensions(path)
                : readImageIoDimensions(path);
        long pixels = (long) dimensions.width() * dimensions.height();
        if (dimensions.width() <= 0
                || dimensions.height() <= 0
                || dimensions.width() > AttachmentLimits.MAX_IMAGE_SIDE
                || dimensions.height() > AttachmentLimits.MAX_IMAGE_SIDE
                || pixels > AttachmentLimits.MAX_IMAGE_PIXELS) {
            throw failure(
                    AttachmentErrorCode.ATTACHMENT_IMAGE_TOO_LARGE,
                    "图片尺寸超过安全上限");
        }
    }

    private static ImageDimensions readImageIoDimensions(Path path) {
        try (ImageInputStream input = ImageIO.createImageInputStream(path.toFile())) {
            if (input == null) {
                throw unsupported();
            }
            Iterator<ImageReader> readers = ImageIO.getImageReaders(input);
            if (!readers.hasNext()) {
                throw unsupported();
            }
            ImageReader reader = readers.next();
            try {
                reader.setInput(input, true, true);
                return new ImageDimensions(reader.getWidth(0), reader.getHeight(0));
            } finally {
                reader.dispose();
            }
        } catch (AttachmentException exception) {
            throw exception;
        } catch (IOException | RuntimeException exception) {
            throw failure(
                    AttachmentErrorCode.ATTACHMENT_TYPE_UNSUPPORTED,
                    "图片内容无效",
                    exception);
        }
    }

    private static ImageDimensions readWebpDimensions(Path path) {
        try (InputStream input = Files.newInputStream(path)) {
            byte[] header = input.readNBytes(30);
            if (header.length < 25
                    || !asciiEquals(header, 0, "RIFF")
                    || !asciiEquals(header, 8, "WEBP")) {
                throw unsupported();
            }
            if (asciiEquals(header, 12, "VP8X") && header.length >= 30) {
                return new ImageDimensions(
                        littleEndian24(header, 24) + 1,
                        littleEndian24(header, 27) + 1);
            }
            if (asciiEquals(header, 12, "VP8 ") && header.length >= 30
                    && (header[23] & 0xff) == 0x9d
                    && (header[24] & 0xff) == 0x01
                    && (header[25] & 0xff) == 0x2a) {
                int width = littleEndian16(header, 26) & 0x3fff;
                int height = littleEndian16(header, 28) & 0x3fff;
                return new ImageDimensions(width, height);
            }
            if (asciiEquals(header, 12, "VP8L") && header.length >= 25
                    && (header[20] & 0xff) == 0x2f) {
                int bits = ByteBuffer.wrap(header, 21, 4)
                        .order(ByteOrder.LITTLE_ENDIAN)
                        .getInt();
                return new ImageDimensions((bits & 0x3fff) + 1, ((bits >>> 14) & 0x3fff) + 1);
            }
            throw unsupported();
        } catch (AttachmentException exception) {
            throw exception;
        } catch (IOException exception) {
            throw failure(
                    AttachmentErrorCode.ATTACHMENT_TYPE_UNSUPPORTED,
                    "图片内容无效",
                    exception);
        }
    }

    private static boolean asciiEquals(byte[] bytes, int offset, String value) {
        if (offset < 0 || offset + value.length() > bytes.length) {
            return false;
        }
        for (int index = 0; index < value.length(); index++) {
            if ((byte) value.charAt(index) != bytes[offset + index]) {
                return false;
            }
        }
        return true;
    }

    private static int littleEndian16(byte[] bytes, int offset) {
        return (bytes[offset] & 0xff) | (bytes[offset + 1] & 0xff) << 8;
    }

    private static int littleEndian24(byte[] bytes, int offset) {
        return (bytes[offset] & 0xff)
                | (bytes[offset + 1] & 0xff) << 8
                | (bytes[offset + 2] & 0xff) << 16;
    }

    private static String calculateSha256(Path path) {
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is required", exception);
        }
        byte[] buffer = new byte[HASH_BUFFER_BYTES];
        try (InputStream input = Files.newInputStream(path)) {
            int read;
            while ((read = input.read(buffer)) >= 0) {
                if (read > 0) {
                    digest.update(buffer, 0, read);
                }
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (IOException exception) {
            throw failure(
                    AttachmentErrorCode.ATTACHMENT_CHANGED,
                    "附件在读取期间变得不可用",
                    exception);
        }
    }

    private static BasicFileAttributes readFinalAttributes(Path path) {
        try {
            if (Files.isSymbolicLink(path)) {
                throw failure(AttachmentErrorCode.ATTACHMENT_CHANGED, "附件在读取期间发生变化");
            }
            BasicFileAttributes attributes = Files.readAttributes(
                    path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            if (!attributes.isRegularFile() || attributes.isOther()) {
                throw failure(AttachmentErrorCode.ATTACHMENT_CHANGED, "附件在读取期间发生变化");
            }
            return attributes;
        } catch (AttachmentException exception) {
            throw exception;
        } catch (IOException | SecurityException exception) {
            throw failure(
                    AttachmentErrorCode.ATTACHMENT_CHANGED,
                    "附件在读取期间变得不可用",
                    exception);
        }
    }

    private static PreparedAttachment.FileIdentity identity(BasicFileAttributes attributes) {
        Object fileKey = attributes.fileKey();
        return new PreparedAttachment.FileIdentity(
                attributes.size(),
                attributes.lastModifiedTime(),
                fileKey == null ? null : fileKey.toString());
    }

    private static boolean sameIdentity(
            PreparedAttachment.FileIdentity before,
            PreparedAttachment.FileIdentity after
    ) {
        if (before.sizeBytes() != after.sizeBytes()
                || !before.lastModifiedTime().equals(after.lastModifiedTime())) {
            return false;
        }
        return before.fileKey() == null
                || after.fileKey() == null
                || before.fileKey().equals(after.fileKey());
    }

    private static String extension(String fileName) {
        int index = fileName.lastIndexOf('.');
        if (index < 0 || index == fileName.length() - 1) {
            return "";
        }
        return fileName.substring(index + 1).toLowerCase(Locale.ROOT);
    }

    private static AttachmentException unsupported() {
        return failure(
                AttachmentErrorCode.ATTACHMENT_TYPE_UNSUPPORTED,
                "不支持该附件类型，或文件内容与扩展名不一致");
    }

    private static AttachmentException failure(AttachmentErrorCode code, String safeMessage) {
        return new AttachmentException(code, safeMessage);
    }

    private static AttachmentException failure(
            AttachmentErrorCode code,
            String safeMessage,
            Throwable ignored
    ) {
        return new AttachmentException(code, safeMessage);
    }

    private record ImageDimensions(int width, int height) {
    }
}
