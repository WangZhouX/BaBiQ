package com.wzx.babiq.server.attachment;

import com.sun.jna.platform.win32.Kernel32;
import com.sun.jna.platform.win32.WinBase;
import com.sun.jna.platform.win32.WinDef.DWORD;
import com.sun.jna.platform.win32.WinNT;
import com.sun.jna.platform.win32.WinNT.HANDLE;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;

/**
 * Windows-only deletion strategy that anchors roots and candidates with native file handles.
 *
 * <p>The Kernel32 mapping is held behind {@link Kernel32Holder}; merely loading this class on a
 * non-Windows JVM does not initialize a Windows native library.</p>
 */
final class WindowsSafeAttachmentDeletionStrategy {

    private static final int SHARE_WITHOUT_DELETE =
            WinNT.FILE_SHARE_READ | WinNT.FILE_SHARE_WRITE;
    private static final int SHARE_ALL =
            SHARE_WITHOUT_DELETE | WinNT.FILE_SHARE_DELETE;
    private static final int OPEN_REPARSE_ATTRIBUTES =
            WinNT.FILE_FLAG_BACKUP_SEMANTICS | WinNT.FILE_FLAG_OPEN_REPARSE_POINT;

    RootLease openRoot(Path configuredRoot) throws IOException {
        Objects.requireNonNull(configuredRoot, "configuredRoot");
        List<NativeHandle> anchors = new ArrayList<>();
        DirectoryStream<Path> entries = null;
        try {
            Path canonical = configuredRoot.toRealPath(LinkOption.NOFOLLOW_LINKS)
                    .toAbsolutePath()
                    .normalize();
            List<Path> segments = absoluteSegments(canonical);
            for (int index = 0; index < segments.size(); index++) {
                Path segment = segments.get(index);
                NativeHandle anchor = open(
                        segment,
                        WinNT.FILE_READ_ATTRIBUTES
                                | (segment.equals(canonical) ? WinNT.DELETE : 0),
                        index == 0 ? SHARE_ALL : SHARE_WITHOUT_DELETE);
                anchors.add(anchor);
                verifyDirectoryWithoutReparse(anchor.handle());
            }
            if (anchors.isEmpty()) {
                throw failure("root_anchor_missing");
            }
            NativeFileIdentity rootIdentity =
                    readIdentity(anchors.getLast().handle());
            entries = Files.newDirectoryStream(canonical);
            return new RootLease(canonical, anchors, entries, rootIdentity);
        } catch (IOException | RuntimeException | LinkageError exception) {
            closeQuietly(entries);
            closeQuietly(anchors);
            throw genericFailure("root_open", exception);
        }
    }

    NativeFileIdentity readIdentity(Path path) throws IOException {
        Objects.requireNonNull(path, "path");
        try (NativeHandle handle = open(
                path.toAbsolutePath().normalize(),
                WinNT.FILE_READ_ATTRIBUTES,
                SHARE_ALL)) {
            verifyNotReparse(handle.handle());
            return readIdentity(handle.handle());
        } catch (IOException | RuntimeException | LinkageError exception) {
            throw genericFailure("identity_read", exception);
        }
    }

    final class RootLease implements AutoCloseable {

        private final Path path;
        private final List<NativeHandle> anchors;
        private final DirectoryStream<Path> entries;
        private final NativeFileIdentity rootIdentity;
        private boolean closed;

        private RootLease(
                Path path,
                List<NativeHandle> anchors,
                DirectoryStream<Path> entries,
                NativeFileIdentity rootIdentity
        ) {
            this.path = path;
            this.anchors = List.copyOf(anchors);
            this.entries = entries;
            this.rootIdentity = rootIdentity;
        }

        Path path() {
            return path;
        }

        DirectoryStream<Path> entries() {
            return entries;
        }

        CandidateLease openCandidate(Path candidate) throws IOException {
            ensureOpen();
            Path normalized = candidate.toAbsolutePath().normalize();
            if (normalized.getFileName() == null
                    || !path.equals(normalized.getParent())) {
                throw failure("candidate_scope");
            }
            NativeHandle handle = null;
            try {
                handle = open(
                        normalized,
                        WinNT.FILE_READ_ATTRIBUTES | WinNT.DELETE,
                        WinNT.FILE_SHARE_READ);
                verifyRegularFileWithoutReparse(handle.handle());
                BasicFileAttributes attributes = readRegularAttributes(normalized);
                NativeFileIdentity identity = readIdentity(handle.handle());
                return new CandidateLease(this, normalized, handle, attributes, identity);
            } catch (IOException | RuntimeException | LinkageError exception) {
                closeQuietly(handle);
                throw genericFailure("candidate_open", exception);
            }
        }

        private boolean identityMatches() throws IOException {
            ensureOpen();
            NativeHandle root = anchors.getLast();
            verifyDirectoryWithoutReparse(root.handle());
            return rootIdentity.equals(readIdentity(root.handle()));
        }

        private void ensureOpen() throws IOException {
            if (closed) {
                throw failure("lease_closed");
            }
        }

        @Override
        public void close() throws IOException {
            if (closed) {
                return;
            }
            closed = true;
            IOException failure = null;
            try {
                entries.close();
            } catch (IOException | RuntimeException | LinkageError exception) {
                failure = genericFailure("entries_close", exception);
            }
            for (int index = anchors.size() - 1; index >= 0; index--) {
                try {
                    anchors.get(index).close();
                } catch (IOException | RuntimeException | LinkageError exception) {
                    IOException safeFailure = exception instanceof IOException ioException
                            ? ioException
                            : genericFailure("handle_close", exception);
                    if (failure == null) {
                        failure = safeFailure;
                    } else {
                        failure.addSuppressed(safeFailure);
                    }
                }
            }
            if (failure != null) {
                throw failure;
            }
        }
    }

    final class CandidateLease implements AutoCloseable {

        private final RootLease root;
        private final Path path;
        private final NativeHandle handle;
        private final BasicFileAttributes expectedAttributes;
        private final NativeFileIdentity expectedIdentity;
        private boolean deleted;

        private CandidateLease(
                RootLease root,
                Path path,
                NativeHandle handle,
                BasicFileAttributes expectedAttributes,
                NativeFileIdentity expectedIdentity
        ) {
            this.root = root;
            this.path = path;
            this.handle = handle;
            this.expectedAttributes = expectedAttributes;
            this.expectedIdentity = expectedIdentity;
        }

        BasicFileAttributes attributes() {
            return expectedAttributes;
        }

        boolean deleteIfUnchanged() throws IOException {
            if (deleted) {
                return false;
            }
            try {
                if (!root.identityMatches()) {
                    return false;
                }
                verifyRegularFileWithoutReparse(handle.handle());
                NativeFileIdentity currentIdentity = readIdentity(handle.handle());
                BasicFileAttributes currentAttributes = readRegularAttributes(path);
                if (!expectedIdentity.equals(currentIdentity)
                        || !sameMutableAttributes(expectedAttributes, currentAttributes)) {
                    return false;
                }
                WinBase.FILE_DISPOSITION_INFO disposition =
                        new WinBase.FILE_DISPOSITION_INFO(true);
                disposition.write();
                boolean success = kernel32().SetFileInformationByHandle(
                        handle.handle(),
                        WinBase.FileDispositionInfo,
                        disposition.getPointer(),
                        new DWORD(disposition.size()));
                if (!success) {
                    throw failure("candidate_delete");
                }
                deleted = true;
                return true;
            } catch (IOException | RuntimeException | LinkageError exception) {
                throw genericFailure("candidate_delete", exception);
            }
        }

        @Override
        public void close() throws IOException {
            handle.close();
        }
    }

    record NativeFileIdentity(long volumeSerialNumber, String fileId) {

        NativeFileIdentity {
            Objects.requireNonNull(fileId, "fileId");
        }
    }

    private static NativeHandle open(Path path, int access, int shareMode) throws IOException {
        HANDLE handle = kernel32().CreateFile(
                path.toString(),
                access,
                shareMode,
                null,
                WinNT.OPEN_EXISTING,
                OPEN_REPARSE_ATTRIBUTES,
                null);
        if (handle == null || WinBase.INVALID_HANDLE_VALUE.equals(handle)) {
            throw failure("handle_open");
        }
        return new NativeHandle(handle);
    }

    private static NativeFileIdentity readIdentity(HANDLE handle) throws IOException {
        WinBase.FILE_ID_INFO information = new WinBase.FILE_ID_INFO();
        boolean success = kernel32().GetFileInformationByHandleEx(
                handle,
                WinBase.FileIdInfo,
                information.getPointer(),
                new DWORD(information.size()));
        if (!success) {
            throw failure("identity_query");
        }
        information.read();
        byte[] identifier = new byte[information.FileId.Identifier.length];
        for (int index = 0; index < identifier.length; index++) {
            identifier[index] = information.FileId.Identifier[index].byteValue();
        }
        return new NativeFileIdentity(
                information.VolumeSerialNumber,
                HexFormat.of().formatHex(identifier));
    }

    private static void verifyDirectoryWithoutReparse(HANDLE handle) throws IOException {
        int attributes = readAttributeMask(handle);
        if ((attributes & WinNT.FILE_ATTRIBUTE_REPARSE_POINT) != 0
                || (attributes & WinNT.FILE_ATTRIBUTE_DIRECTORY) == 0) {
            throw failure("unsafe_root");
        }
    }

    private static void verifyRegularFileWithoutReparse(HANDLE handle) throws IOException {
        int attributes = readAttributeMask(handle);
        if ((attributes & WinNT.FILE_ATTRIBUTE_REPARSE_POINT) != 0
                || (attributes & WinNT.FILE_ATTRIBUTE_DIRECTORY) != 0) {
            throw failure("unsafe_candidate");
        }
    }

    private static void verifyNotReparse(HANDLE handle) throws IOException {
        if ((readAttributeMask(handle) & WinNT.FILE_ATTRIBUTE_REPARSE_POINT) != 0) {
            throw failure("unsafe_reparse");
        }
    }

    private static int readAttributeMask(HANDLE handle) throws IOException {
        WinBase.FILE_ATTRIBUTE_TAG_INFO information =
                new WinBase.FILE_ATTRIBUTE_TAG_INFO();
        boolean success = kernel32().GetFileInformationByHandleEx(
                handle,
                WinBase.FileAttributeTagInfo,
                information.getPointer(),
                new DWORD(information.size()));
        if (!success) {
            throw failure("attribute_query");
        }
        information.read();
        return information.FileAttributes;
    }

    private static BasicFileAttributes readRegularAttributes(Path path) throws IOException {
        BasicFileAttributes attributes = Files.readAttributes(
                path,
                BasicFileAttributes.class,
                LinkOption.NOFOLLOW_LINKS);
        if (Files.isSymbolicLink(path)
                || !attributes.isRegularFile()
                || attributes.isOther()) {
            throw failure("unsafe_candidate_attributes");
        }
        return attributes;
    }

    private static boolean sameMutableAttributes(
            BasicFileAttributes expected,
            BasicFileAttributes current
    ) {
        return expected.size() == current.size()
                && expected.lastModifiedTime().equals(current.lastModifiedTime());
    }

    private static List<Path> absoluteSegments(Path path) throws IOException {
        Path root = path.getRoot();
        if (root == null) {
            throw failure("absolute_root");
        }
        List<Path> segments = new ArrayList<>();
        Path current = root;
        segments.add(current);
        for (Path segment : path) {
            current = current.resolve(segment);
            segments.add(current);
        }
        return segments;
    }

    private static IOException failure(String operation) {
        return new IOException(
                "Windows attachment filesystem operation failed: operation="
                        + operation
                        + ", errorCode="
                        + kernel32().GetLastError());
    }

    private static IOException genericFailure(String operation, Throwable cause) {
        return new IOException(
                "Windows attachment filesystem operation failed: operation="
                        + operation
                        + ", reasonType="
                        + cause.getClass().getSimpleName());
    }

    private static Kernel32 kernel32() {
        return Kernel32Holder.INSTANCE;
    }

    private static void closeQuietly(DirectoryStream<Path> entries) {
        if (entries == null) {
            return;
        }
        try {
            entries.close();
        } catch (IOException | RuntimeException | LinkageError ignored) {
            // The caller reports one generic filesystem failure.
        }
    }

    private static void closeQuietly(List<NativeHandle> handles) {
        for (int index = handles.size() - 1; index >= 0; index--) {
            closeQuietly(handles.get(index));
        }
    }

    private static void closeQuietly(NativeHandle handle) {
        if (handle == null) {
            return;
        }
        try {
            handle.close();
        } catch (IOException | RuntimeException | LinkageError ignored) {
            // The caller reports one generic filesystem failure.
        }
    }

    private static final class NativeHandle implements AutoCloseable {

        private final HANDLE handle;
        private boolean closed;

        private NativeHandle(HANDLE handle) {
            this.handle = handle;
        }

        private HANDLE handle() throws IOException {
            if (closed) {
                throw failure("handle_closed");
            }
            return handle;
        }

        @Override
        public void close() throws IOException {
            if (closed) {
                return;
            }
            closed = true;
            try {
                if (!kernel32().CloseHandle(handle)) {
                    throw failure("handle_close");
                }
            } catch (RuntimeException | LinkageError exception) {
                throw genericFailure("handle_close", exception);
            }
        }
    }

    private static final class Kernel32Holder {

        private static final Kernel32 INSTANCE = Kernel32.INSTANCE;
    }
}
