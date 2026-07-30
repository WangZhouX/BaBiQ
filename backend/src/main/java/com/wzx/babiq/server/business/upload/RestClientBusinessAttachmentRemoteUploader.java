package com.wzx.babiq.server.business.upload;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wzx.babiq.server.business.oa.config.BusinessOaProperties;
import com.wzx.babiq.server.business.oa.session.OaAuthenticatedRequestExecutor;
import com.wzx.babiq.server.business.oa.session.OaRemoteRequestException;
import com.wzx.babiq.server.business.oa.session.ReadyOaSessionLease;
import org.springframework.core.io.FileSystemResource;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import java.net.http.HttpClient;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** Real OA multipart adapter for /infra/file/upload-return-ids. */
public final class RestClientBusinessAttachmentRemoteUploader implements BusinessAttachmentRemoteUploader {
    static final String STORAGE_NAME = "ht-law-file-management";
    private final RestClient client;
    private final ObjectMapper mapper = new ObjectMapper();
    private final AuthenticatedWriteExecutor executor;

    public RestClientBusinessAttachmentRemoteUploader(BusinessOaProperties properties,
                                                      OaAuthenticatedRequestExecutor executor) {
        this(properties, (lease, operation) -> executor.execute(
                lease, OaAuthenticatedRequestExecutor.RequestKind.WRITE, operation::execute));
    }

    RestClientBusinessAttachmentRemoteUploader(BusinessOaProperties properties,
                                               AuthenticatedWriteExecutor executor) {
        Objects.requireNonNull(properties, "properties");
        this.executor = Objects.requireNonNull(executor, "executor");
        HttpClient http = HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NEVER)
                .connectTimeout(Duration.ofMillis(properties.requestTimeoutMs()))
                .build();
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(http);
        factory.setReadTimeout(Duration.ofMillis(properties.requestTimeoutMs()));
        this.client = RestClient.builder().requestFactory(factory)
                .baseUrl(properties.endpointBase()).build();
    }

    @Override
    public UploadedRemoteFiles upload(ReadyOaSessionLease lease, List<StagedFile> files) {
        Objects.requireNonNull(lease, "lease");
        List<StagedFile> immutable = files == null ? List.of() : List.copyOf(files);
        if (immutable.isEmpty() || immutable.size() > BusinessAttachmentTicketService.MAX_FILE_COUNT) {
            throw new RemoteUploadRejectedException();
        }
        return executor.execute(lease, token -> uploadWithToken(lease.tenantId(), token, immutable));
    }

    private UploadedRemoteFiles uploadWithToken(String tenantId, char[] accessToken, List<StagedFile> files) {
        try {
            MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
            for (StagedFile file : files) {
                body.add("files", new SafeFilenameResource(file));
            }
            body.add("fileStorageName", STORAGE_NAME);
            String response = client.post()
                    .uri("/infra/file/upload-return-ids")
                    .contentType(MediaType.MULTIPART_FORM_DATA)
                    .header("X-Platform-Type", "pc")
                    .header("tenant-id", tenantId)
                    .header("Authorization", "Bearer " + new String(accessToken))
                    .body(body)
                    .retrieve()
                    .body(String.class);
            JsonNode root = mapper.readTree(response);
            if (root == null || !"0".equals(root.path("code").asText()) || !root.path("data").isArray()
                    || root.path("data").size() != files.size()) {
                throw new RemoteUploadOutcomeUnknownException();
            }
            List<char[]> ids = new ArrayList<>(files.size());
            try {
                for (JsonNode value : root.path("data")) {
                    if (!value.isTextual() || value.textValue().isBlank() || value.textValue().length() > 512
                            || value.textValue().indexOf('\n') >= 0 || value.textValue().indexOf('\r') >= 0) {
                        throw new RemoteUploadOutcomeUnknownException();
                    }
                    ids.add(value.textValue().toCharArray());
                }
                return new UploadedRemoteFiles(ids);
            } finally {
                ids.forEach(value -> java.util.Arrays.fill(value, '\0'));
            }
        } catch (OaRemoteRequestException | RemoteUploadOutcomeUnknownException exception) {
            throw exception;
        } catch (RestClientResponseException exception) {
            int status = exception.getStatusCode().value();
            if (status == 401 || status == 499) throw OaRemoteRequestException.authenticationExpired(status);
            throw new RemoteUploadRejectedException();
        } catch (ResourceAccessException exception) {
            throw new RemoteUploadOutcomeUnknownException();
        } catch (RuntimeException exception) {
            throw new RemoteUploadOutcomeUnknownException();
        } catch (Exception exception) {
            throw new RemoteUploadOutcomeUnknownException();
        }
    }

    @FunctionalInterface
    interface AuthenticatedWriteExecutor {
        UploadedRemoteFiles execute(ReadyOaSessionLease lease, TokenUploadOperation operation);
    }

    @FunctionalInterface
    interface TokenUploadOperation {
        UploadedRemoteFiles execute(char[] accessToken);
    }

    private static final class SafeFilenameResource extends FileSystemResource {
        private final String fileName;

        private SafeFilenameResource(StagedFile file) {
            super(file.path());
            this.fileName = BusinessAttachmentTicketService.sanitizeFileName(file.fileName());
        }

        @Override public String getFilename() { return fileName; }
    }

    public static final class RemoteUploadRejectedException extends IllegalStateException {
        public RemoteUploadRejectedException() { super("OA attachment upload was rejected"); }
    }

    public static final class RemoteUploadOutcomeUnknownException extends IllegalStateException {
        public RemoteUploadOutcomeUnknownException() { super("OA attachment upload outcome is unknown"); }
    }
}
