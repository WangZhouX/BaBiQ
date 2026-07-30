package com.wzx.babiq.server.business.upload;

import com.wzx.babiq.server.business.oa.session.ReadyOaSessionLease;

import java.nio.file.Path;
import java.util.List;

/**
 * Narrow port for the OA file-upload adapter.  The upload controller never exposes OA file ids to Compose.
 * A real OA adapter can be supplied by the workbench write phase; absence is fail-closed.
 */
public interface BusinessAttachmentRemoteUploader {
    UploadedRemoteFiles upload(ReadyOaSessionLease lease, List<StagedFile> files);

    record StagedFile(String fileName, String mediaType, long sizeBytes, String sha256, Path path) {
        public StagedFile {
            if (fileName == null || fileName.isBlank() || mediaType == null || mediaType.isBlank()
                    || sizeBytes <= 0 || path == null) throw new IllegalArgumentException("invalid staged file");
        }

        @Override public String toString() {
            return "StagedFile(fileName=[REDACTED], mediaType=" + mediaType + ", sizeBytes=" + sizeBytes + ", sha256=[REDACTED])";
        }
    }

    final class UploadedRemoteFiles implements AutoCloseable {
        private final List<char[]> fileIds;

        public UploadedRemoteFiles(List<char[]> fileIds) {
            if (fileIds == null || fileIds.isEmpty()) throw new IllegalArgumentException("remote file ids are required");
            this.fileIds = fileIds.stream().map(value -> {
                if (value == null || value.length == 0) throw new IllegalArgumentException("remote file id is invalid");
                return value.clone();
            }).toList();
        }

        public int fileCount() { return fileIds.size(); }

        List<char[]> copyFileIds() {
            return fileIds.stream().map(char[]::clone).toList();
        }

        @Override public void close() {
            fileIds.forEach(value -> java.util.Arrays.fill(value, '\0'));
        }

        @Override public String toString() {
            return "UploadedRemoteFiles(fileCount=" + fileIds.size() + ", fileIds=[REDACTED])";
        }
    }
}
