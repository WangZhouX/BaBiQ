package com.wzx.babiq.server.attachment;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wzx.babiq.server.application.config.BusinessDesktopModeProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Deletes expired application-owned clipboard screenshots without touching selected user files.
 */
@Service
@ConditionalOnProperty(prefix = "babiq.business", name = "enabled", havingValue = "true")
public final class ClipboardAttachmentRetentionService {

    private static final Logger log =
            LoggerFactory.getLogger(ClipboardAttachmentRetentionService.class);
    private static final Pattern GENERATED_SCREENSHOT = Pattern.compile(
            "^截图-\\d{8}-\\d{6}-[A-HJ-NP-Z2-9]{6}\\.png$");
    private static final Duration ORPHAN_RETENTION = Duration.ofHours(24);
    private static final Duration ARCHIVED_RETENTION = Duration.ofDays(30);

    private final AttachmentReferenceRepository repository;
    private final ObjectMapper objectMapper;
    private final Path configuredRoot;
    private final Clock clock;

    @Autowired
    public ClipboardAttachmentRetentionService(
            AttachmentReferenceRepository repository,
            ObjectMapper objectMapper,
            BusinessDesktopModeProperties properties
    ) {
        this(repository, objectMapper, properties.attachmentClipboardRoot(), Clock.systemUTC());
    }

    ClipboardAttachmentRetentionService(
            AttachmentReferenceRepository repository,
            ObjectMapper objectMapper,
            Path configuredRoot,
            Clock clock
    ) {
        this.repository = repository;
        this.objectMapper = objectMapper;
        this.configuredRoot = configuredRoot.toAbsolutePath().normalize();
        this.clock = clock;
    }

    /**
     * Runs the idempotent retention scan used by startup recovery and the six-hour scheduler.
     *
     * @return aggregate path-free cleanup statistics
     */
    @Scheduled(
            fixedDelayString =
                    "${babiq.business.attachment-cleanup-interval-millis:21600000}",
            initialDelayString =
                    "${babiq.business.attachment-cleanup-interval-millis:21600000}")
    public CleanupResult cleanup() {
        Path root = canonicalControlledRoot();
        if (root == null) {
            return new CleanupResult(0, 0, 0, 1);
        }

        ReferenceIndex references = readReferences(root);
        if (!references.safeToDelete()) {
            if (references.invalidRecords() > 0) {
                log.warn("Controlled clipboard retention failed closed: reasonType={}, count={}",
                        "INVALID_ATTACHMENT_REFERENCE", references.invalidRecords());
            }
            return new CleanupResult(0, 0, references.invalidRecords(), 0);
        }
        int scanned = 0;
        int deleted = 0;
        int failures = 0;
        Instant orphanCutoff = clock.instant().minus(ORPHAN_RETENTION);
        try (DirectoryStream<Path> entries = Files.newDirectoryStream(root)) {
            for (Path entry : entries) {
                String fileName = fileName(entry);
                if (fileName == null || !GENERATED_SCREENSHOT.matcher(fileName).matches()) {
                    continue;
                }
                BasicFileAttributes attributes = safeRegularFileAttributes(entry);
                if (attributes == null) {
                    continue;
                }
                scanned++;
                ReferenceRetention retention = references.byPath().get(entry.toAbsolutePath().normalize());
                boolean eligible = retention == ReferenceRetention.ARCHIVE_EXPIRED
                        || retention == null
                        && attributes.lastModifiedTime().toInstant().isBefore(orphanCutoff);
                if (!eligible) {
                    continue;
                }
                try {
                    if (sameRegularFile(entry, attributes)) {
                        if (Files.deleteIfExists(entry)) {
                            deleted++;
                        }
                    }
                } catch (IOException | SecurityException exception) {
                    failures++;
                }
            }
        } catch (IOException | SecurityException exception) {
            failures++;
        }

        if (references.invalidRecords() > 0) {
            log.warn("Controlled clipboard retention ignored invalid references: reasonType={}, count={}",
                    "INVALID_ATTACHMENT_REFERENCE", references.invalidRecords());
        }
        if (failures > 0) {
            log.warn("Controlled clipboard retention encountered filesystem failures: "
                    + "reasonType={}, count={}", "FILESYSTEM_OPERATION_FAILED", failures);
        }
        return new CleanupResult(scanned, deleted, references.invalidRecords(), failures);
    }

    private ReferenceIndex readReferences(Path root) {
        Map<Path, ReferenceRetention> byPath = new HashMap<>();
        int invalid = 0;
        List<AttachmentReferenceRecord> records;
        try {
            records = Objects.requireNonNull(repository.findAll(), "attachment reference records");
        } catch (RuntimeException exception) {
            log.warn("Controlled clipboard retention could not read references: "
                    + "reasonType={}, count={}", "REFERENCE_QUERY_FAILED", 1);
            return new ReferenceIndex(Map.of(), 1, false);
        }
        for (AttachmentReferenceRecord record : records) {
            try {
                ReferenceRetention retention = retentionFor(record.archivedAt());
                JsonNode rootNode = objectMapper.readTree(record.payloadJson());
                JsonNode attachments = rootNode.path("attachments");
                if (!attachments.isArray()) {
                    continue;
                }
                for (JsonNode attachmentNode : attachments) {
                    AttachmentMetadata metadata =
                            objectMapper.treeToValue(attachmentNode, AttachmentMetadata.class);
                    Path referencedPath = controlledPath(root, metadata);
                    if (referencedPath != null) {
                        ReferenceRetention attachmentRetention =
                                metadata.source() == AttachmentSource.CLIPBOARD_IMAGE
                                        ? retention
                                        : ReferenceRetention.ACTIVE;
                        byPath.merge(
                                referencedPath,
                                attachmentRetention,
                                ReferenceRetention::strongest);
                    }
                }
            } catch (IOException | RuntimeException exception) {
                invalid++;
            }
        }
        return new ReferenceIndex(Map.copyOf(byPath), invalid, invalid == 0);
    }

    private ReferenceRetention retentionFor(String archivedAt) {
        if (archivedAt == null || archivedAt.isBlank()) {
            return ReferenceRetention.ACTIVE;
        }
        Instant archived = Instant.parse(archivedAt);
        return archived.plus(ARCHIVED_RETENTION).isBefore(clock.instant())
                ? ReferenceRetention.ARCHIVE_EXPIRED
                : ReferenceRetention.ARCHIVE_RETAINED;
    }

    private static Path controlledPath(Path root, AttachmentMetadata metadata) {
        try {
            Path path = Path.of(metadata.localPath()).toAbsolutePath().normalize();
            Path parent = path.getParent();
            Path name = path.getFileName();
            if (parent == null || name == null
                    || !parent.equals(root)
                    || !GENERATED_SCREENSHOT.matcher(name.toString()).matches()) {
                return null;
            }
            return path;
        } catch (InvalidPathException | SecurityException exception) {
            return null;
        }
    }

    private Path canonicalControlledRoot() {
        try {
            rejectLinkedSegments(configuredRoot);
            BasicFileAttributes attributes = Files.readAttributes(
                    configuredRoot, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            if (!attributes.isDirectory() || attributes.isOther()
                    || Files.isSymbolicLink(configuredRoot)) {
                return null;
            }
            Path canonical = configuredRoot.toRealPath(LinkOption.NOFOLLOW_LINKS)
                    .toAbsolutePath()
                    .normalize();
            rejectLinkedSegments(canonical);
            return canonical;
        } catch (IOException | RuntimeException exception) {
            return null;
        }
    }

    private static void rejectLinkedSegments(Path path) throws IOException {
        Path current = path.getRoot();
        rejectLinkedEntry(current);
        for (Path segment : path) {
            current = current == null ? segment : current.resolve(segment);
            rejectLinkedEntry(current);
        }
    }

    private static void rejectLinkedEntry(Path path) throws IOException {
        if (path == null || !Files.exists(path, LinkOption.NOFOLLOW_LINKS)) {
            return;
        }
        BasicFileAttributes attributes = Files.readAttributes(
                path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        if (Files.isSymbolicLink(path) || attributes.isOther()) {
            throw new IOException("controlled path contains a link");
        }
    }

    private static BasicFileAttributes safeRegularFileAttributes(Path path) {
        try {
            BasicFileAttributes attributes = Files.readAttributes(
                    path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            if (Files.isSymbolicLink(path)
                    || !attributes.isRegularFile()
                    || attributes.isOther()) {
                return null;
            }
            return attributes;
        } catch (IOException | SecurityException exception) {
            return null;
        }
    }

    private static boolean sameRegularFile(Path path, BasicFileAttributes expected) {
        BasicFileAttributes current = safeRegularFileAttributes(path);
        return current != null && sameFileIdentity(expected, current);
    }

    static boolean sameFileIdentity(
            BasicFileAttributes expected,
            BasicFileAttributes current
    ) {
        Object expectedKey = expected.fileKey();
        Object currentKey = current.fileKey();
        return expected.size() == current.size()
                && expected.lastModifiedTime().equals(current.lastModifiedTime())
                && (expectedKey == null && currentKey == null
                || expectedKey != null && expectedKey.equals(currentKey));
    }

    private static String fileName(Path path) {
        Path fileName = path.getFileName();
        return fileName == null ? null : fileName.toString();
    }

    private enum ReferenceRetention {
        ARCHIVE_EXPIRED,
        ARCHIVE_RETAINED,
        ACTIVE;

        private static ReferenceRetention strongest(
                ReferenceRetention left,
                ReferenceRetention right
        ) {
            return left.ordinal() >= right.ordinal() ? left : right;
        }
    }

    private record ReferenceIndex(
            Map<Path, ReferenceRetention> byPath,
            int invalidRecords,
            boolean safeToDelete
    ) {
    }

    public record CleanupResult(
            int scannedFiles,
            int deletedFiles,
            int invalidReferenceRecords,
            int filesystemFailures
    ) {
    }
}
