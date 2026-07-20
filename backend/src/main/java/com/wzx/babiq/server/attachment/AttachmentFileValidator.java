package com.wzx.babiq.server.attachment;

import org.apache.tika.Tika;
import org.apache.tika.io.TikaInputStream;
import org.springframework.stereotype.Component;

import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.SeekableByteChannel;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.OpenOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.StandardWatchEventKinds;
import java.nio.file.WatchEvent;
import java.nio.file.WatchKey;
import java.nio.file.WatchService;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileTime;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.LinkedHashMap;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

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
    private final ValidationRaceHook raceHook;

    public AttachmentFileValidator() {
        this(new Tika(), ValidationRaceHook.NO_OP);
    }

    AttachmentFileValidator(Tika tika) {
        this(tika, ValidationRaceHook.NO_OP);
    }

    AttachmentFileValidator(Tika tika, ValidationRaceHook raceHook) {
        this.tika = tika;
        this.raceHook = raceHook;
    }

    public PreparedAttachment validate(AttachmentRequest request) {
        requireRequestShape(request);
        Path requestedPath = parseAbsolutePath(request.localPath());
        rejectLinkedPathSegments(requestedPath);
        BasicFileAttributes initialAttributes = readInitialAttributes(requestedPath);
        if (initialAttributes.size() > AttachmentLimits.MAX_FILE_BYTES) {
            throw failure(
                    AttachmentErrorCode.ATTACHMENT_FILE_TOO_LARGE,
                    "附件超过 20 MiB 的单文件上限");
        }

        Path canonicalPath = toCanonicalPath(requestedPath);
        rejectLinkedPathSegments(requestedPath);
        rejectLinkedPathSegments(canonicalPath);
        BasicFileAttributes canonicalAttributes = readFinalAttributes(canonicalPath);
        if (!sameIdentity(identity(initialAttributes), identity(canonicalAttributes))) {
            throw failure(AttachmentErrorCode.ATTACHMENT_CHANGED, "附件在读取期间发生变化，请重新选择");
        }
        initialAttributes = canonicalAttributes;
        List<PathSegmentFingerprint> pathChainBefore = capturePathChain(requestedPath);
        String actualName = requireActualFileName(canonicalPath);
        String extension = extension(actualName);
        if (!TEXT_EXTENSIONS.contains(extension) && !STRICT_MEDIA_TYPES.containsKey(extension)) {
            throw unsupported();
        }

        try (PathMutationWatch mutationWatch = PathMutationWatch.start(requestedPath)) {
            invokeRaceHook(ValidationPhase.BEFORE_BOUND_OPEN, requestedPath, canonicalPath);
            rejectLinkedPathSegments(requestedPath);
            rejectLinkedPathSegments(canonicalPath);
            BoundFileSnapshot snapshot = readBoundSnapshot(
                    requestedPath, canonicalPath, initialAttributes.size());

            String mediaType = detectMediaType(snapshot.bytes());
            verifyContentMatchesExtension(snapshot.bytes(), extension, mediaType);
            if (mediaType.startsWith("image/")) {
                validateImageDimensions(snapshot.bytes(), mediaType);
            }

            rejectLinkedPathSegments(requestedPath);
            rejectLinkedPathSegments(canonicalPath);
            if (mutationWatch.targetChanged()
                    || !pathChainBefore.equals(capturePathChain(requestedPath))) {
                throw failure(AttachmentErrorCode.ATTACHMENT_CHANGED, "附件路径在读取期间发生变化");
            }
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
                    snapshot.sha256(),
                    AttachmentSource.SELECTED_FILE);
            return new PreparedAttachment(metadata, canonicalPath, finalIdentity);
        } catch (IOException exception) {
            throw failure(
                    AttachmentErrorCode.ATTACHMENT_CHANGED,
                    "无法安全监控附件路径",
                    exception);
        }
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

    private String detectMediaType(byte[] bytes) {
        try (TikaInputStream input = TikaInputStream.get(bytes)) {
            String mediaType = tika.detect(input);
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

    private static void verifyContentMatchesExtension(
            byte[] bytes,
            String extension,
            String detectedMediaType
    ) {
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
            if (!"application/pdf".equals(detectedMediaType) || !hasPrefix(bytes, "%PDF-".getBytes())) {
                throw unsupported();
            }
            return;
        }
        if (LEGACY_OFFICE_EXTENSIONS.contains(extension)) {
            if (!hasPrefix(bytes, OLE_SIGNATURE) || !expected.equals(detectedMediaType)) {
                throw unsupported();
            }
            return;
        }
        if (OOXML_EXTENSIONS.contains(extension)) {
            if (!hasOoxmlContentType(bytes, expected)) {
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

    private static boolean hasPrefix(byte[] bytes, byte[] expected) {
        if (bytes.length < expected.length) {
            return false;
        }
        byte[] actual = java.util.Arrays.copyOf(bytes, expected.length);
        return MessageDigest.isEqual(expected, actual);
    }

    private static boolean hasOoxmlContentType(byte[] bytes, String expectedMediaType) {
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(bytes))) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                if (!"[Content_Types].xml".equals(entry.getName())) {
                    continue;
                }
                if (entry.isDirectory() || entry.getSize() > CONTENT_TYPE_XML_LIMIT) {
                    return false;
                }
                byte[] content = zip.readNBytes(CONTENT_TYPE_XML_LIMIT + 1);
                return content.length <= CONTENT_TYPE_XML_LIMIT
                        && new String(content, java.nio.charset.StandardCharsets.UTF_8)
                        .contains(expectedMediaType);
            }
            return false;
        } catch (IOException exception) {
            return false;
        }
    }

    private static void validateImageDimensions(byte[] bytes, String mediaType) {
        ImageDimensions dimensions = "image/webp".equals(mediaType)
                ? readWebpDimensions(bytes)
                : readImageIoDimensions(bytes);
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

    private static ImageDimensions readImageIoDimensions(byte[] bytes) {
        try (ImageInputStream input = ImageIO.createImageInputStream(new ByteArrayInputStream(bytes))) {
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

    private static ImageDimensions readWebpDimensions(byte[] bytes) {
        try {
            byte[] header = java.util.Arrays.copyOf(bytes, Math.min(bytes.length, 30));
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

    private BoundFileSnapshot readBoundSnapshot(
            Path requestedPath,
            Path canonicalPath,
            long expectedSize
    ) {
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is required", exception);
        }
        if (expectedSize < 0 || expectedSize > AttachmentLimits.MAX_FILE_BYTES) {
            throw failure(
                    AttachmentErrorCode.ATTACHMENT_FILE_TOO_LARGE,
                    "附件超过 20 MiB 的单文件上限");
        }
        Set<OpenOption> options = Set.of(StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS);
        try (SeekableByteChannel channel = Files.newByteChannel(canonicalPath, options)) {
            if (channel.size() != expectedSize) {
                throw failure(AttachmentErrorCode.ATTACHMENT_CHANGED, "附件在读取期间发生变化");
            }
            invokeRaceHook(ValidationPhase.AFTER_BOUND_OPEN, requestedPath, canonicalPath);
            byte[] bytes = new byte[(int) expectedSize];
            int offset = 0;
            while (offset < bytes.length) {
                int chunkSize = Math.min(HASH_BUFFER_BYTES, bytes.length - offset);
                ByteBuffer target = ByteBuffer.wrap(bytes, offset, chunkSize);
                int read = channel.read(target);
                if (read < 0) {
                    throw failure(AttachmentErrorCode.ATTACHMENT_CHANGED, "附件在读取期间发生变化");
                }
                if (read == 0) {
                    continue;
                }
                digest.update(bytes, offset, read);
                offset += read;
            }
            if (channel.read(ByteBuffer.allocate(1)) >= 0 || channel.size() != expectedSize) {
                throw failure(AttachmentErrorCode.ATTACHMENT_CHANGED, "附件在读取期间发生变化");
            }
            return new BoundFileSnapshot(bytes, HexFormat.of().formatHex(digest.digest()));
        } catch (AttachmentException exception) {
            throw exception;
        } catch (IOException exception) {
            throw failure(
                    AttachmentErrorCode.ATTACHMENT_CHANGED,
                    "附件在读取期间变得不可用",
                    exception);
        }
    }

    private void invokeRaceHook(ValidationPhase phase, Path requestedPath, Path canonicalPath) {
        try {
            raceHook.onPhase(phase, requestedPath, canonicalPath);
        } catch (IOException | RuntimeException exception) {
            throw failure(
                    AttachmentErrorCode.ATTACHMENT_CHANGED,
                    "附件路径在读取期间发生变化",
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

    private static void rejectLinkedPathSegments(Path path) {
        Path absolute = path.toAbsolutePath().normalize();
        Path current = absolute.getRoot();
        rejectLinkedSegment(current, false);
        int index = 0;
        int segmentCount = absolute.getNameCount();
        for (Path segment : absolute) {
            current = current == null ? segment : current.resolve(segment);
            index++;
            rejectLinkedSegment(current, index < segmentCount);
        }
    }

    private static List<PathSegmentFingerprint> capturePathChain(Path path) {
        java.util.ArrayList<PathSegmentFingerprint> fingerprints = new java.util.ArrayList<>();
        Path absolute = path.toAbsolutePath().normalize();
        Path current = absolute.getRoot();
        if (current != null) {
            fingerprints.add(readPathFingerprint(current));
        }
        for (Path segment : absolute) {
            current = current == null ? segment : current.resolve(segment);
            fingerprints.add(readPathFingerprint(current));
        }
        return List.copyOf(fingerprints);
    }

    private static PathSegmentFingerprint readPathFingerprint(Path path) {
        try {
            BasicFileAttributes attributes = Files.readAttributes(
                    path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            Object fileKey = attributes.fileKey();
            boolean directory = attributes.isDirectory();
            return new PathSegmentFingerprint(
                    directory,
                    attributes.isRegularFile(),
                    directory ? 0 : attributes.size(),
                    directory ? FileTime.fromMillis(0) : attributes.lastModifiedTime(),
                    fileKey == null ? null : fileKey.toString());
        } catch (IOException | SecurityException exception) {
            throw failure(
                    AttachmentErrorCode.ATTACHMENT_CHANGED,
                    "附件路径在读取期间发生变化",
                    exception);
        }
    }

    private static void rejectLinkedSegment(Path path, boolean mustBeDirectory) {
        if (path == null) {
            return;
        }
        try {
            if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) {
                throw failure(AttachmentErrorCode.ATTACHMENT_NOT_FOUND, "附件路径已不存在");
            }
            BasicFileAttributes attributes = Files.readAttributes(
                    path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            if (Files.isSymbolicLink(path) || attributes.isOther()) {
                throw failure(
                        AttachmentErrorCode.ATTACHMENT_NOT_REGULAR_FILE,
                        "附件路径不能包含符号链接");
            }
            if (mustBeDirectory && !attributes.isDirectory()) {
                throw failure(
                        AttachmentErrorCode.ATTACHMENT_NOT_REGULAR_FILE,
                        "附件路径的上级必须是普通目录");
            }
        } catch (AttachmentException exception) {
            throw exception;
        } catch (IOException | SecurityException exception) {
            throw failure(
                    AttachmentErrorCode.ATTACHMENT_PATH_INVALID,
                    "无法安全检查附件路径",
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

    enum ValidationPhase {
        BEFORE_BOUND_OPEN,
        AFTER_BOUND_OPEN
    }

    @FunctionalInterface
    interface ValidationRaceHook {
        ValidationRaceHook NO_OP = (phase, requestedPath, canonicalPath) -> {
        };

        void onPhase(ValidationPhase phase, Path requestedPath, Path canonicalPath) throws IOException;
    }

    private record BoundFileSnapshot(byte[] bytes, String sha256) {
        @Override
        public String toString() {
            return "BoundFileSnapshot[bytes=<redacted>, sha256=<redacted>]";
        }
    }

    private record PathSegmentFingerprint(
            boolean directory,
            boolean regularFile,
            long size,
            FileTime lastModifiedTime,
            String fileKey
    ) {
        @Override
        public String toString() {
            return "PathSegmentFingerprint[directory=%s, regularFile=%s, size=%d, "
                    .formatted(directory, regularFile, size)
                    + "lastModifiedTime=<redacted>, fileKey=<redacted>]";
        }
    }

    private static final class PathMutationWatch implements AutoCloseable {

        private final WatchService watchService;
        private final Map<WatchKey, Path> expectedChildren;

        private PathMutationWatch(WatchService watchService, Map<WatchKey, Path> expectedChildren) {
            this.watchService = watchService;
            this.expectedChildren = expectedChildren;
        }

        static PathMutationWatch start(Path target) throws IOException {
            WatchService watchService = target.getFileSystem().newWatchService();
            Map<WatchKey, Path> expectedChildren = new LinkedHashMap<>();
            Path absolute = target.toAbsolutePath().normalize();
            Path child = absolute;
            while (child.getParent() != null) {
                Path parent = child.getParent();
                WatchKey key = parent.register(
                        watchService,
                        StandardWatchEventKinds.ENTRY_CREATE,
                        StandardWatchEventKinds.ENTRY_DELETE);
                expectedChildren.put(key, child.getFileName());
                child = parent;
            }
            return new PathMutationWatch(watchService, expectedChildren);
        }

        boolean targetChanged() {
            boolean changed = false;
            WatchKey key;
            try {
                key = watchService.poll(25, TimeUnit.MILLISECONDS);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                return true;
            }
            while (key != null) {
                Path expectedChild = expectedChildren.get(key);
                for (WatchEvent<?> event : key.pollEvents()) {
                    if (event.kind() == StandardWatchEventKinds.OVERFLOW
                            || expectedChild != null && expectedChild.equals(event.context())) {
                        changed = true;
                    }
                }
                if (!key.reset()) {
                    changed = true;
                }
                key = watchService.poll();
            }
            return changed;
        }

        @Override
        public void close() throws IOException {
            watchService.close();
        }
    }
}
