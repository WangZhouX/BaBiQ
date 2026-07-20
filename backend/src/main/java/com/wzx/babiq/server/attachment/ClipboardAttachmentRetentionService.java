package com.wzx.babiq.server.attachment;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wzx.babiq.server.application.config.BusinessDesktopModeProperties;
import com.wzx.babiq.server.recovery.StartupRecoveryCoordinator;
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
import java.nio.file.SecureDirectoryStream;
import java.nio.file.attribute.BasicFileAttributeView;
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
            "^\\x{622A}\\x{56FE}-\\d{8}-\\d{6}-[A-HJ-NP-Z2-9]{6}\\.png$");
    private static final Duration ORPHAN_RETENTION = Duration.ofHours(24);
    private static final Duration ARCHIVED_RETENTION = Duration.ofDays(30);
    private static final FileAttributeReader SYSTEM_FILE_ATTRIBUTES =
            new FileAttributeReader() {
                @Override
                public BasicFileAttributes read(Path path) throws IOException {
                    return readBasicAttributes(path);
                }

                @Override
                public boolean supportsSecureDirectoryIdentity() {
                    return true;
                }
            };

    private final AttachmentReferenceRepository repository;
    private final ObjectMapper objectMapper;
    private final Path configuredRoot;
    private final Clock clock;
    private final StartupRecoveryCoordinator startupRecoveryCoordinator;
    private final AttachmentReservationRegistry attachmentReservationRegistry;
    private final FileAttributeReader fileAttributeReader;

    @Autowired
    public ClipboardAttachmentRetentionService(
            AttachmentReferenceRepository repository,
            ObjectMapper objectMapper,
            BusinessDesktopModeProperties properties,
            StartupRecoveryCoordinator startupRecoveryCoordinator,
            AttachmentReservationRegistry attachmentReservationRegistry
    ) {
        this(
                repository,
                objectMapper,
                properties.attachmentClipboardRoot(),
                Clock.systemUTC(),
                startupRecoveryCoordinator,
                attachmentReservationRegistry);
    }

    ClipboardAttachmentRetentionService(
            AttachmentReferenceRepository repository,
            ObjectMapper objectMapper,
            Path configuredRoot,
            Clock clock
    ) {
        this(
                repository,
                objectMapper,
                configuredRoot,
                clock,
                new StartupRecoveryCoordinator(),
                new AttachmentReservationRegistry(),
                SYSTEM_FILE_ATTRIBUTES);
    }

    ClipboardAttachmentRetentionService(
            AttachmentReferenceRepository repository,
            ObjectMapper objectMapper,
            Path configuredRoot,
            Clock clock,
            StartupRecoveryCoordinator startupRecoveryCoordinator
    ) {
        this(
                repository,
                objectMapper,
                configuredRoot,
                clock,
                startupRecoveryCoordinator,
                new AttachmentReservationRegistry(),
                SYSTEM_FILE_ATTRIBUTES);
    }

    ClipboardAttachmentRetentionService(
            AttachmentReferenceRepository repository,
            ObjectMapper objectMapper,
            Path configuredRoot,
            Clock clock,
            StartupRecoveryCoordinator startupRecoveryCoordinator,
            AttachmentReservationRegistry attachmentReservationRegistry
    ) {
        this(
                repository,
                objectMapper,
                configuredRoot,
                clock,
                startupRecoveryCoordinator,
                attachmentReservationRegistry,
                SYSTEM_FILE_ATTRIBUTES);
    }

    ClipboardAttachmentRetentionService(
            AttachmentReferenceRepository repository,
            ObjectMapper objectMapper,
            Path configuredRoot,
            Clock clock,
            StartupRecoveryCoordinator startupRecoveryCoordinator,
            AttachmentReservationRegistry attachmentReservationRegistry,
            FileAttributeReader fileAttributeReader
    ) {
        this.repository = repository;
        this.objectMapper = objectMapper;
        this.configuredRoot = configuredRoot.toAbsolutePath().normalize();
        this.clock = clock;
        this.startupRecoveryCoordinator = Objects.requireNonNull(startupRecoveryCoordinator);
        this.attachmentReservationRegistry =
                Objects.requireNonNull(attachmentReservationRegistry);
        this.fileAttributeReader = Objects.requireNonNull(fileAttributeReader);
    }

    /**
     * Runs the idempotent retention scan used by startup recovery and the six-hour scheduler.
     *
     * @return aggregate path-free cleanup statistics
     */
    public CleanupResult cleanup() {
        return attachmentReservationRegistry.withinCleanupGuard(this::cleanupWithinGuard);
    }

    private CleanupResult cleanupWithinGuard() {
        ControlledRoot root = canonicalControlledRoot();
        if (root == null) {
            return new CleanupResult(0, 0, 0, 1);
        }

        ReferenceIndex references = readReferences(root.path());
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
        try (DirectoryStream<Path> entries = Files.newDirectoryStream(root.path())) {
            SecureDirectoryStream<Path> secureEntries =
                    fileAttributeReader.supportsSecureDirectoryIdentity()
                            ? secureDirectoryStream(entries)
                            : null;
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
                Path candidatePath = entry.toAbsolutePath().normalize();
                ReferenceRetention retention = references.byPath().get(candidatePath);
                boolean eligible = retention == ReferenceRetention.ARCHIVE_EXPIRED
                        || retention == null
                        && attributes.lastModifiedTime().toInstant().isBefore(orphanCutoff);
                if (!eligible || attachmentReservationRegistry.isPathProtected(candidatePath)) {
                    continue;
                }
                try {
                    if (deleteIfUnchanged(root, secureEntries, entry, attributes)) {
                        deleted++;
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

    /**
     * Runs periodic cleanup only after startup recovery has released the SQLite write barrier.
     */
    @Scheduled(
            fixedDelayString =
                    "${babiq.business.attachment-cleanup-interval-millis:21600000}",
            initialDelayString =
                    "${babiq.business.attachment-cleanup-interval-millis:21600000}")
    public void scheduledCleanup() {
        if (!startupRecoveryCoordinator.isRecoveryComplete()) {
            return;
        }
        cleanup();
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
                if (rootNode == null || !rootNode.isObject()) {
                    throw new InvalidAttachmentReferenceException();
                }
                if (!rootNode.has("attachments")) {
                    continue;
                }
                JsonNode attachments = rootNode.get("attachments");
                if (attachments == null || !attachments.isArray()) {
                    throw new InvalidAttachmentReferenceException();
                }
                for (JsonNode attachmentNode : attachments) {
                    if (!attachmentNode.isObject()) {
                        throw new InvalidAttachmentReferenceException();
                    }
                    AttachmentMetadata metadata =
                            objectMapper.treeToValue(attachmentNode, AttachmentMetadata.class);
                    Path referencedPath = controlledPath(root, metadata);
                    if (metadata.source() == AttachmentSource.CLIPBOARD_IMAGE
                            && referencedPath == null) {
                        throw new InvalidAttachmentReferenceException();
                    }
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
            Path rawPath = Path.of(metadata.localPath());
            if (!rawPath.isAbsolute() || !rawPath.equals(rawPath.normalize())) {
                return null;
            }
            Path path = rawPath.toAbsolutePath();
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

    private ControlledRoot canonicalControlledRoot() {
        try {
            rejectLinkedSegments(configuredRoot);
            BasicFileAttributes attributes = fileAttributeReader.read(configuredRoot);
            Object rootFileKey = attributes.fileKey();
            if (!safeRootAttributes(Files.isSymbolicLink(configuredRoot), attributes)
                    || rootFileKey == null) {
                return null;
            }
            Path canonical = configuredRoot.toRealPath(LinkOption.NOFOLLOW_LINKS)
                    .toAbsolutePath()
                    .normalize();
            rejectLinkedSegments(canonical);
            BasicFileAttributes canonicalAttributes = fileAttributeReader.read(canonical);
            if (!sameRootIdentity(
                    rootFileKey,
                    Files.isSymbolicLink(canonical),
                    canonicalAttributes)) {
                return null;
            }
            return new ControlledRoot(canonical, rootFileKey);
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

    private BasicFileAttributes safeRegularFileAttributes(Path path) {
        try {
            BasicFileAttributes attributes = fileAttributeReader.read(path);
            if (!safeCandidateAttributes(Files.isSymbolicLink(path), attributes)) {
                return null;
            }
            return attributes;
        } catch (IOException | SecurityException exception) {
            return null;
        }
    }

    private boolean deleteIfUnchanged(
            ControlledRoot root,
            SecureDirectoryStream<Path> secureEntries,
            Path entry,
            BasicFileAttributes expected
    ) throws IOException {
        if (!sameControlledRoot(root)) {
            return false;
        }
        Path relativeName = entry.getFileName();
        if (relativeName == null) {
            return false;
        }
        if (secureEntries != null) {
            BasicFileAttributes first =
                    secureRegularFileAttributes(secureEntries, relativeName);
            if (!sameFileIdentity(expected, first) || !sameControlledRoot(root)) {
                return false;
            }
            BasicFileAttributes current =
                    secureRegularFileAttributes(secureEntries, relativeName);
            if (!sameFileIdentity(expected, current)
                    || !sameFileIdentity(first, current)) {
                return false;
            }
            secureEntries.deleteFile(relativeName);
            return true;
        }

        rejectLinkedSegments(entry);
        BasicFileAttributes first = safeRegularFileAttributes(entry);
        if (!sameFileIdentity(expected, first) || !sameControlledRoot(root)) {
            return false;
        }
        rejectLinkedSegments(entry);
        BasicFileAttributes current = safeRegularFileAttributes(entry);
        if (!sameFileIdentity(expected, current)
                || !sameFileIdentity(first, current)
                || !sameControlledRoot(root)) {
            return false;
        }
        rejectLinkedSegments(entry);
        BasicFileAttributes finalCandidate = safeRegularFileAttributes(entry);
        return sameFileIdentity(expected, finalCandidate)
                && sameFileIdentity(current, finalCandidate)
                && Files.deleteIfExists(entry);
    }

    private boolean sameControlledRoot(ControlledRoot root) {
        try {
            rejectLinkedSegments(root.path());
            BasicFileAttributes current = fileAttributeReader.read(root.path());
            return sameRootIdentity(
                    root.fileKey(),
                    Files.isSymbolicLink(root.path()),
                    current);
        } catch (IOException | SecurityException exception) {
            return false;
        }
    }

    private static BasicFileAttributes readBasicAttributes(Path path) throws IOException {
        return Files.readAttributes(
                path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
    }

    private static BasicFileAttributes secureRegularFileAttributes(
            SecureDirectoryStream<Path> entries,
            Path relativeName
    ) throws IOException {
        BasicFileAttributeView view = entries.getFileAttributeView(
                relativeName,
                BasicFileAttributeView.class,
                LinkOption.NOFOLLOW_LINKS);
        if (view == null) {
            return null;
        }
        BasicFileAttributes attributes = view.readAttributes();
        return safeCandidateAttributes(attributes.isSymbolicLink(), attributes)
                ? attributes
                : null;
    }

    @SuppressWarnings("unchecked")
    private static SecureDirectoryStream<Path> secureDirectoryStream(
            DirectoryStream<Path> entries
    ) {
        return entries instanceof SecureDirectoryStream<?>
                ? (SecureDirectoryStream<Path>) entries
                : null;
    }

    static boolean sameFileIdentity(
            BasicFileAttributes expected,
            BasicFileAttributes current
    ) {
        if (expected == null || current == null) {
            return false;
        }
        Object expectedKey = expected.fileKey();
        Object currentKey = current.fileKey();
        return expected.size() == current.size()
                && expected.lastModifiedTime().equals(current.lastModifiedTime())
                && expectedKey != null
                && expectedKey.equals(currentKey);
    }

    static boolean safeRootAttributes(
            boolean symbolicLink,
            BasicFileAttributes attributes
    ) {
        return !symbolicLink && attributes.isDirectory() && !attributes.isOther();
    }

    static boolean safeCandidateAttributes(
            boolean symbolicLink,
            BasicFileAttributes attributes
    ) {
        return !symbolicLink && attributes.isRegularFile() && !attributes.isOther();
    }

    static boolean sameRootIdentity(
            Object expectedFileKey,
            boolean symbolicLink,
            BasicFileAttributes current
    ) {
        return expectedFileKey != null
                && current != null
                && safeRootAttributes(symbolicLink, current)
                && expectedFileKey.equals(current.fileKey());
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

    private record ControlledRoot(Path path, Object fileKey) {
    }

    @FunctionalInterface
    interface FileAttributeReader {
        BasicFileAttributes read(Path path) throws IOException;

        default boolean supportsSecureDirectoryIdentity() {
            return false;
        }
    }

    private static final class InvalidAttachmentReferenceException extends RuntimeException {
    }

    public record CleanupResult(
            int scannedFiles,
            int deletedFiles,
            int invalidReferenceRecords,
            int filesystemFailures
    ) {
    }
}
