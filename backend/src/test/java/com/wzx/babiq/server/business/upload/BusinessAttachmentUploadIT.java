package com.wzx.babiq.server.business.upload;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import com.wzx.babiq.server.application.auth.TrustedDesktopConnection;
import com.wzx.babiq.server.application.auth.BusinessDesktopConnectionRegistry;
import com.wzx.babiq.server.application.auth.DesktopSessionTokenProvider;
import com.wzx.babiq.server.business.oa.config.BusinessOaProperties;
import com.wzx.babiq.server.business.oa.session.BusinessOaSessionRegistry;
import com.wzx.babiq.server.business.oa.session.ReadyOaSessionLease;
import com.wzx.babiq.server.persistence.config.MyBatisPlusConfig;
import com.wzx.babiq.server.persistence.config.SQLiteConnectionInitializer;
import com.wzx.babiq.server.persistence.config.SQLiteDataSourceConfig;
import com.wzx.babiq.server.settings.SecretStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.autoconfigure.flyway.FlywayAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.boot.web.context.WebServerApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockMultipartFile;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class BusinessAttachmentUploadIT {
    private FakeOaServer server;
    private static final AtomicReference<FakeOaServer> CROSS_LAYER_SERVER = new AtomicReference<>();
    private static final AtomicReference<ReadyOaSessionLease> CROSS_LAYER_LEASE =
            new AtomicReference<>(lease(1));
    private static final AtomicReference<Runnable> CROSS_LAYER_AFTER_RESPONSE =
            new AtomicReference<>(() -> { });
    private static final AtomicLong CROSS_LAYER_TIMEOUT_MILLIS = new AtomicLong(50);
    private static final MapSecretStore CROSS_LAYER_SECRETS = new MapSecretStore();

    @AfterEach
    void stopServer() {
        if (server != null) server.close();
    }

    @Test
    void fake_oa_server_close_terminates_its_executor() throws Exception {
        server = FakeOaServer.start("{\"code\":0,\"data\":[]}", 200, 0);

        server.close();

        assertThat(server.executorTerminated()).isTrue();
        server = null;
    }

    @Test
    void posts_real_multipart_contract_and_returns_server_only_file_ids() throws Exception {
        server = FakeOaServer.start("""
                {"code":0,"data":["oa-file-id-canary-a","oa-file-id-canary-b"]}
                """, 200, 0);
        RestClientBusinessAttachmentRemoteUploader uploader = uploader(2_000);
        List<BusinessAttachmentRemoteUploader.StagedFile> files = List.of(
                staged("a.pdf", "%PDF-a"),
                staged("b.pdf", "%PDF-b"));

        try (BusinessAttachmentRemoteUploader.UploadedRemoteFiles uploaded =
                     uploader.upload(lease(1), files)) {
            assertThat(uploaded.fileCount()).isEqualTo(2);
            assertThat(uploaded.toString()).doesNotContain("oa-file-id-canary");
        } finally {
            files.forEach(file -> delete(file.path()));
        }

        assertThat(server.path()).isEqualTo("/law-api/infra/file/upload-return-ids");
        assertThat(server.authorization()).isEqualTo("Bearer access-token");
        assertThat(server.body()).contains("name=\"files\"")
                .contains("filename=\"a.pdf\"")
                .contains("filename=\"b.pdf\"")
                .contains("name=\"fileStorageName\"")
                .contains("ht-law-file-management");
    }

    @Test
    void malformed_response_401_timeout_and_disconnect_are_never_reported_as_success() throws Exception {
        server = FakeOaServer.start("{\"code\":0,\"data\":[null]}", 200, 0);
        assertUploadFails(uploader(2_000));
        server.close();

        server = FakeOaServer.start("", 401, 0);
        assertUploadFails(uploader(2_000));
        server.close();

        server = FakeOaServer.start("{\"code\":0,\"data\":[\"late\"]}", 200, 250);
        assertUploadFails(uploader(50));
        server.close();

        server = FakeOaServer.startDisconnecting();
        assertUploadFails(uploader(2_000));
    }

    @Test
    void stale_lease_after_remote_success_is_outcome_unknown_and_temporary_files_are_deleted() throws Exception {
        Path runtime = Files.createTempDirectory("business-attachment-upload-it");
        Path staged = Files.writeString(runtime.resolve("upload-part.part"), "%PDF");
        try {
            BusinessAttachmentTicketService tickets =
                    new BusinessAttachmentTicketService(Clock.fixed(Instant.parse("2026-07-29T00:00:00Z"), ZoneOffset.UTC));
            TrustedDesktopConnection connection = connection();
            ReadyOaSessionLease original = lease(1);
            var prepared = tickets.prepare(connection, original, "SCHEDULE_CREATE", "operation-1", "case-1",
                    List.of(new BusinessAttachmentTicketService.FileDeclaration(
                            "a.pdf", Files.size(staged), "application/pdf", null)));
            var claim = tickets.claim(prepared.batchId(), prepared.ticket(), connection, original);

            tickets.validateBeforeRemote(claim, List.of(new BusinessAttachmentTicketService.UploadedFile(
                    "a.pdf", Files.size(staged), "application/pdf",
                    BusinessAttachmentTicketService.sha256Hex(Files.readAllBytes(staged)))));
            tickets.outcomeUnknown(claim);

            assertThat(tickets.status(prepared.batchId()).ticket())
                    .isEqualTo(BusinessAttachmentTicketService.TicketStatus.OUTCOME_UNKNOWN);
            assertThat(tickets.consumeForScheduleCreate(
                    prepared.batchId(), connection, lease(2), "operation-1", "case-1")).isFalse();
        } finally {
            Files.deleteIfExists(staged);
            Files.deleteIfExists(runtime);
        }
        assertThat(Files.exists(staged)).isFalse();
    }

    @Test
    void controller_deletes_all_staged_files_after_success() throws Exception {
        Path runtime = Files.createTempDirectory("business-upload-controller-it");
        BusinessAttachmentTicketService tickets =
                new BusinessAttachmentTicketService(Clock.fixed(Instant.parse("2026-07-29T00:00:00Z"), ZoneOffset.UTC));
        byte[] bytes = "%PDF-a".getBytes(StandardCharsets.UTF_8);
        var prepared = tickets.prepare(connection(), lease(1), "SCHEDULE_CREATE", "operation-1", "case-1",
                List.of(new BusinessAttachmentTicketService.FileDeclaration(
                        "a.pdf", bytes.length, "application/pdf",
                        BusinessAttachmentTicketService.sha256Hex(bytes))));
        BusinessAttachmentRemoteUploader remote = mock(BusinessAttachmentRemoteUploader.class);
        when(remote.upload(org.mockito.ArgumentMatchers.eq(lease(1)), org.mockito.ArgumentMatchers.anyList()))
                .thenReturn(new BusinessAttachmentRemoteUploader.UploadedRemoteFiles(
                        List.of("oa-file-id-canary-controller".toCharArray())));
        @SuppressWarnings("unchecked")
        ObjectProvider<BusinessAttachmentRemoteUploader> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(remote);
        BusinessAttachmentUploadController controller =
                new BusinessAttachmentUploadController(tickets, provider, runtime.toString());
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setAttribute(BusinessLoopbackHttpSecurityFilter.CONNECTION_ATTRIBUTE, connection());
        request.setAttribute(BusinessLoopbackHttpSecurityFilter.LEASE_ATTRIBUTE, lease(1));
        request.setAttribute(BusinessLoopbackHttpSecurityFilter.UPLOAD_CLAIM_ATTRIBUTE,
                tickets.claim(prepared.batchId(), prepared.ticket(), connection(), lease(1)));

        controller.upload(prepared.batchId(), prepared.ticket(),
                List.of(new MockMultipartFile("files", "a.pdf", "application/pdf", bytes)), request);

        Path uploadRoot = runtime.resolve("attachments").resolve("uploads");
        try (var files = Files.list(uploadRoot)) {
            assertThat(files).isEmpty();
        }
        Files.deleteIfExists(uploadRoot);
        Files.deleteIfExists(uploadRoot.getParent());
        Files.deleteIfExists(runtime);
    }

    @Test
    void real_spring_boot_loopback_filter_claims_ticket_before_multipart_controller() throws Exception {
        Path runtime = Files.createTempDirectory("business-upload-spring-it");
        ConfigurableApplicationContext context = new SpringApplication(UploadHttpTestApplication.class)
                .run("--server.address=127.0.0.1", "--server.port=0",
                        "--babiq.business.enabled=true",
                        "--babiq.business.runtime-dir=" + runtime,
                        "--babiq.business.allowed-origins=http://127.0.0.1");
        try {
            int port = ((WebServerApplicationContext) context).getWebServer().getPort();
            BusinessAttachmentTicketService ticketService =
                    context.getBean(BusinessAttachmentTicketService.class);
            byte[] bytes = "%PDF-a".getBytes(StandardCharsets.UTF_8);
            var prepared = ticketService.prepare(connection(), lease(1), "SCHEDULE_CREATE",
                    "operation-http", "case-http",
                    List.of(new BusinessAttachmentTicketService.FileDeclaration(
                            "a.pdf", bytes.length, "application/pdf",
                            BusinessAttachmentTicketService.sha256Hex(bytes))));
            String boundary = "babiq-boundary-7f31";
            byte[] multipart = ("--" + boundary + "\r\n"
                    + "Content-Disposition: form-data; name=\"files\"; filename=\"a.pdf\"\r\n"
                    + "Content-Type: application/pdf\r\n\r\n"
                    + new String(bytes, StandardCharsets.ISO_8859_1) + "\r\n"
                    + "--" + boundary + "--\r\n").getBytes(StandardCharsets.ISO_8859_1);
            HttpRequest request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port
                            + "/business/attachments/uploads/" + prepared.batchId()))
                    .header("Origin", "http://127.0.0.1:" + port)
                    .header("Authorization", "Bearer desktop-token")
                    .header("X-Desktop-Instance-Id", "instance-1")
                    .header("X-Desktop-Session-Id", "desktop-1")
                    .header("X-Business-Upload-Ticket", prepared.ticket())
                    .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                    .POST(HttpRequest.BodyPublishers.ofByteArray(multipart)).build();

            HttpResponse<String> response = HttpClient.newHttpClient()
                    .send(request, HttpResponse.BodyHandlers.ofString());

            assertThat(response.statusCode()).isEqualTo(200);
            assertThat(ticketService.status(prepared.batchId())).isEqualTo(
                    new BusinessAttachmentTicketService.Status(
                            BusinessAttachmentTicketService.TicketStatus.SUCCEEDED,
                            BusinessAttachmentTicketService.BatchStatus.READY));
            try (var staged = Files.list(runtime.resolve("attachments").resolve("uploads"))) {
                assertThat(staged).isEmpty();
            }
        } finally {
            context.close();
        }
    }

    @Test
    void claimed_ticket_is_rejected_when_multipart_has_no_files_part() throws Exception {
        Path runtime = Files.createTempDirectory("business-upload-missing-part-it");
        ConfigurableApplicationContext context = new SpringApplication(UploadHttpTestApplication.class)
                .run("--server.address=127.0.0.1", "--server.port=0",
                        "--babiq.business.enabled=true",
                        "--babiq.business.runtime-dir=" + runtime,
                        "--babiq.business.allowed-origins=http://127.0.0.1");
        try {
            int port = ((WebServerApplicationContext) context).getWebServer().getPort();
            BusinessAttachmentTicketService tickets = context.getBean(BusinessAttachmentTicketService.class);
            byte[] bytes = "%PDF-a".getBytes(StandardCharsets.UTF_8);
            var prepared = tickets.prepare(connection(), lease(1), "SCHEDULE_CREATE",
                    "operation-missing", "case-missing",
                    List.of(new BusinessAttachmentTicketService.FileDeclaration(
                            "a.pdf", bytes.length, "application/pdf",
                            BusinessAttachmentTicketService.sha256Hex(bytes))));
            String boundary = "babiq-boundary-missing";
            byte[] multipart = ("--" + boundary + "\r\n"
                    + "Content-Disposition: form-data; name=\"wrong\"; filename=\"a.pdf\"\r\n"
                    + "Content-Type: application/pdf\r\n\r\n"
                    + new String(bytes, StandardCharsets.ISO_8859_1) + "\r\n"
                    + "--" + boundary + "--\r\n").getBytes(StandardCharsets.ISO_8859_1);

            HttpResponse<String> response = sendUpload(port, prepared, multipart, boundary, "POST");

            assertThat(response.statusCode()).isEqualTo(400);
            assertThat(tickets.status(prepared.batchId())).isEqualTo(
                    new BusinessAttachmentTicketService.Status(
                            BusinessAttachmentTicketService.TicketStatus.REJECTED,
                            BusinessAttachmentTicketService.BatchStatus.FAILED));
        } finally {
            context.close();
        }
    }

    @Test
    void non_post_upload_method_is_rejected_without_claiming_ticket() throws Exception {
        Path runtime = Files.createTempDirectory("business-upload-method-it");
        ConfigurableApplicationContext context = new SpringApplication(UploadHttpTestApplication.class)
                .run("--server.address=127.0.0.1", "--server.port=0",
                        "--babiq.business.enabled=true",
                        "--babiq.business.runtime-dir=" + runtime,
                        "--babiq.business.allowed-origins=http://127.0.0.1");
        try {
            int port = ((WebServerApplicationContext) context).getWebServer().getPort();
            BusinessAttachmentTicketService tickets = context.getBean(BusinessAttachmentTicketService.class);
            byte[] bytes = "%PDF-a".getBytes(StandardCharsets.UTF_8);
            var prepared = tickets.prepare(connection(), lease(1), "SCHEDULE_CREATE",
                    "operation-method", "case-method",
                    List.of(new BusinessAttachmentTicketService.FileDeclaration(
                            "a.pdf", bytes.length, "application/pdf",
                            BusinessAttachmentTicketService.sha256Hex(bytes))));

            HttpResponse<String> response = sendUpload(port, prepared, new byte[0], "unused", "PUT");

            assertThat(response.statusCode()).isEqualTo(401);
            assertThat(tickets.status(prepared.batchId())).isEqualTo(
                    new BusinessAttachmentTicketService.Status(
                            BusinessAttachmentTicketService.TicketStatus.ISSUED,
                            BusinessAttachmentTicketService.BatchStatus.PENDING));
        } finally {
            context.close();
        }
    }

    @Test
    void true_cross_layer_sqlite_http_and_real_oa_adapter_cover_all_remote_outcomes_and_lease_drift()
            throws Exception {
        assertCrossLayerOutcome("{\"code\":0,\"data\":[\"oa-file-id-success\"]}", 200, 0, false,
                false, 200, BusinessAttachmentTicketService.BatchStatus.READY);
        assertCrossLayerOutcome("{\"code\":0,\"data\":[null]}", 200, 0, false,
                false, 502, BusinessAttachmentTicketService.BatchStatus.OUTCOME_UNKNOWN);
        assertCrossLayerOutcome("", 401, 0, false,
                false, 502, BusinessAttachmentTicketService.BatchStatus.OUTCOME_UNKNOWN);
        assertCrossLayerOutcome("{\"code\":0,\"data\":[\"late\"]}", 200, 250, false,
                false, 502, BusinessAttachmentTicketService.BatchStatus.OUTCOME_UNKNOWN);
        assertCrossLayerOutcome("", 200, 0, true,
                false, 502, BusinessAttachmentTicketService.BatchStatus.OUTCOME_UNKNOWN);
        assertCrossLayerOutcome("{\"code\":0,\"data\":[\"oa-file-id-stale\"]}", 200, 0, false,
                true, 502, BusinessAttachmentTicketService.BatchStatus.OUTCOME_UNKNOWN);
    }

    @Test
    void application_ready_restart_recovers_durable_ticket_and_secret() throws Exception {
        server = FakeOaServer.start("{\"code\":0,\"data\":[\"unused\"]}", 200, 0);
        CROSS_LAYER_SERVER.set(server);
        CROSS_LAYER_LEASE.set(lease(1));
        Path runtime = Files.createTempDirectory("business-upload-ready-recovery-it");
        Path database = runtime.resolve("attachments.db");
        String[] args = {"--server.address=127.0.0.1", "--server.port=0",
                "--babiq.business.enabled=true", "--babiq.business.runtime-dir=" + runtime,
                "--babiq.business.allowed-origins=http://127.0.0.1",
                "--babiq.persistence.database-path=" + database};
        String batchId;
        String declarationRef;
        try (ConfigurableApplicationContext first =
                     new SpringApplication(DurableUploadHttpTestApplication.class).run(args)) {
            BusinessAttachmentTicketService tickets = first.getBean(BusinessAttachmentTicketService.class);
            var prepared = tickets.prepare(connection(), lease(1), "SCHEDULE_CREATE",
                    "operation-restart", "case-restart",
                    List.of(new BusinessAttachmentTicketService.FileDeclaration(
                            "a.pdf", 3, "application/pdf", null)));
            batchId = prepared.batchId();
            declarationRef = first.getBean(BusinessAttachmentRepository.class)
                    .findBatch(batchId).orElseThrow().declarationSecretRef();
            assertThat(CROSS_LAYER_SECRETS.load(declarationRef)).isPresent();
        }

        try (ConfigurableApplicationContext restarted =
                     new SpringApplication(DurableUploadHttpTestApplication.class).run(args)) {
            BusinessAttachmentRepository repository =
                    restarted.getBean(BusinessAttachmentRepository.class);
            assertThat(repository.findTicketByBatchId(batchId).orElseThrow().state())
                    .isEqualTo(BusinessAttachmentTicketService.TicketStatus.REVOKED);
            assertThat(repository.findBatch(batchId).orElseThrow().state())
                    .isEqualTo(BusinessAttachmentTicketService.BatchStatus.REVOKED);
            assertThat(CROSS_LAYER_SECRETS.load(declarationRef)).isEmpty();
        }
    }

    @Test
    @ResourceLock("logback-root")
    void real_canary_lifecycle_consumes_cancels_and_cleans_every_non_remote_boundary() throws Exception {
        String marker = java.util.UUID.randomUUID().toString().replace("-", "");
        String fileNameCanary = "task17-private-" + marker + ".pdf";
        String pathCanary = "task17-path-" + marker;
        String fileIdCanary = "oa-file-id-" + marker;
        String oaErrorCanary = "oa-error-" + marker;
        byte[] bytes = ("%PDF-1.7\n" + marker).getBytes(StandardCharsets.UTF_8);
        String shaCanary = BusinessAttachmentTicketService.sha256Hex(bytes);
        Path runtime = Files.createTempDirectory("business-upload-canary-runtime");
        Path selectedRoot = Files.createTempDirectory(pathCanary);
        Path selectedFile = Files.write(selectedRoot.resolve(fileNameCanary), bytes);
        Path database = runtime.resolve("attachments.db");
        String[] fixedCanaries = {
                fileNameCanary, selectedFile.toString(), pathCanary, shaCanary, fileIdCanary, oaErrorCanary
        };

        CROSS_LAYER_SECRETS.clear();
        server = FakeOaServer.start(
                "{\"code\":0,\"data\":[\"" + fileIdCanary + "\"]}", 200, 0);
        CROSS_LAYER_SERVER.set(server);
        CROSS_LAYER_LEASE.set(lease(1));
        CROSS_LAYER_TIMEOUT_MILLIS.set(2_000);
        CROSS_LAYER_AFTER_RESPONSE.set(() -> { });
        Logger root = (Logger) LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        root.addAppender(appender);
        String ticketCanary = null;
        String diagnosticReport = "";
        ConfigurableApplicationContext context = new SpringApplication(DurableUploadHttpTestApplication.class)
                .run("--server.address=127.0.0.1", "--server.port=0",
                        "--babiq.business.enabled=true",
                        "--babiq.business.runtime-dir=" + runtime,
                        "--babiq.business.allowed-origins=http://127.0.0.1",
                        "--babiq.persistence.database-path=" + database);
        try {
            int port = ((WebServerApplicationContext) context).getWebServer().getPort();
            BusinessAttachmentTicketService tickets = context.getBean(BusinessAttachmentTicketService.class);
            BusinessAttachmentRepository repository = context.getBean(BusinessAttachmentRepository.class);
            String operationId = "operation-" + marker;
            String parentId = "case-" + marker;
            var prepared = tickets.prepare(connection(), lease(1), "SCHEDULE_CREATE",
                    operationId, parentId,
                    List.of(new BusinessAttachmentTicketService.FileDeclaration(
                            fileNameCanary, bytes.length, "application/pdf", shaCanary)));
            ticketCanary = prepared.ticket();

            HttpResponse<String> success = sendUpload(
                    port, prepared, multipart(fileNameCanary, bytes, "task17-boundary-" + marker),
                    "task17-boundary-" + marker, "POST");

            assertThat(success.statusCode()).isEqualTo(200);
            assertThat(server.path()).isEqualTo("/law-api/infra/file/upload-return-ids");
            assertThat(server.body()).contains("name=\"files\"")
                    .contains("filename=\"" + fileNameCanary + "\"")
                    .contains("name=\"fileStorageName\"")
                    .contains("ht-law-file-management");
            assertThat(tickets.status(prepared.batchId()).batch())
                    .isEqualTo(BusinessAttachmentTicketService.BatchStatus.READY);
            try (BusinessAttachmentTicketService.ScheduleAttachmentConsumption consumption =
                         tickets.beginScheduleCreate(
                                 prepared.batchId(), connection(), lease(1), operationId, parentId)) {
                assertThat(consumption.fileIds()).singleElement()
                        .satisfies(value -> assertThat(new String(value)).isEqualTo(fileIdCanary));
                tickets.finishScheduleCreate(
                        consumption, BusinessAttachmentTicketService.BatchStatus.CONSUMED);
            }
            assertThat(repository.findBatch(prepared.batchId()).orElseThrow().state())
                    .isEqualTo(BusinessAttachmentTicketService.BatchStatus.CONSUMED);

            var cancelled = tickets.prepare(connection(), lease(1), "SCHEDULE_CREATE",
                    "cancel-" + marker, parentId,
                    List.of(new BusinessAttachmentTicketService.FileDeclaration(
                            fileNameCanary, bytes.length, "application/pdf", shaCanary)));
            assertThat(tickets.revokeForConnection(connection(), lease(1))).isEqualTo(1);
            assertThat(tickets.status(cancelled.batchId()).batch())
                    .isEqualTo(BusinessAttachmentTicketService.BatchStatus.REVOKED);

            server.respondWith(
                    "{\"code\":500,\"msg\":\"" + oaErrorCanary + "\",\"data\":null}", 500, 0);
            var failed = tickets.prepare(connection(), lease(1), "SCHEDULE_CREATE",
                    "failed-" + marker, parentId,
                    List.of(new BusinessAttachmentTicketService.FileDeclaration(
                            fileNameCanary, bytes.length, "application/pdf", shaCanary)));
            HttpResponse<String> failure = sendUpload(
                    port, failed, multipart(fileNameCanary, bytes, "task17-error-boundary-" + marker),
                    "task17-error-boundary-" + marker, "POST");
            diagnosticReport = failure.body() + "\n" + tickets.status(failed.batchId());
            assertThat(failure.statusCode()).isEqualTo(502);
            assertThat(tickets.status(failed.batchId()).batch())
                    .isEqualTo(BusinessAttachmentTicketService.BatchStatus.OUTCOME_UNKNOWN);

            assertUploadRootEmpty(runtime);
            assertThat(CROSS_LAYER_SECRETS.aliases()).isEmpty();
        } finally {
            context.close();
            root.detachAppender(appender);
            appender.stop();
            Files.deleteIfExists(selectedFile);
            Files.deleteIfExists(selectedRoot);
            CROSS_LAYER_AFTER_RESPONSE.set(() -> { });
            CROSS_LAYER_TIMEOUT_MILLIS.set(50);
        }

        String[] allCanaries = Stream.concat(
                Stream.of(ticketCanary),
                Stream.of(fixedCanaries)).toArray(String[]::new);
        String logs = appender.list.stream()
                .map(event -> event.getFormattedMessage() + "\n"
                        + (event.getThrowableProxy() == null ? "" : event.getThrowableProxy().getMessage()))
                .reduce("", (left, right) -> left + "\n" + right);
        assertTextDoesNotContain(logs, allCanaries);
        assertTextDoesNotContain(diagnosticReport, allCanaries);
        assertBinaryFileDoesNotContain(database, allCanaries);
        assertTreeDoesNotContain(runtime, allCanaries);
        assertThat(CROSS_LAYER_SECRETS.aliases()).isEmpty();
    }

    private void assertCrossLayerOutcome(
            String responseBody,
            int oaStatus,
            long oaDelay,
            boolean disconnect,
            boolean driftLease,
            int expectedHttpStatus,
            BusinessAttachmentTicketService.BatchStatus expectedBatchStatus) throws Exception {
        if (server != null) server.close();
        server = disconnect ? FakeOaServer.startDisconnecting()
                : FakeOaServer.start(responseBody, oaStatus, oaDelay);
        CROSS_LAYER_SERVER.set(server);
        CROSS_LAYER_LEASE.set(lease(1));
        CROSS_LAYER_AFTER_RESPONSE.set(driftLease ? () -> CROSS_LAYER_LEASE.set(lease(2)) : () -> { });
        Path runtime = Files.createTempDirectory("business-upload-cross-layer-it");
        Path database = runtime.resolve("attachments.db");
        ConfigurableApplicationContext context = new SpringApplication(DurableUploadHttpTestApplication.class)
                .run("--server.address=127.0.0.1", "--server.port=0",
                        "--babiq.business.enabled=true",
                        "--babiq.business.runtime-dir=" + runtime,
                        "--babiq.business.allowed-origins=http://127.0.0.1",
                        "--babiq.persistence.database-path=" + database);
        try {
            int port = ((WebServerApplicationContext) context).getWebServer().getPort();
            BusinessAttachmentTicketService tickets = context.getBean(BusinessAttachmentTicketService.class);
            byte[] bytes = "%PDF-cross-layer".getBytes(StandardCharsets.UTF_8);
            var prepared = tickets.prepare(connection(), lease(1), "SCHEDULE_CREATE",
                    "operation-" + java.util.UUID.randomUUID(), "case-cross-layer",
                    List.of(new BusinessAttachmentTicketService.FileDeclaration(
                            "a.pdf", bytes.length, "application/pdf",
                            BusinessAttachmentTicketService.sha256Hex(bytes))));
            String boundary = "babiq-boundary-cross-layer";
            byte[] multipart = ("--" + boundary + "\r\n"
                    + "Content-Disposition: form-data; name=\"files\"; filename=\"a.pdf\"\r\n"
                    + "Content-Type: application/pdf\r\n\r\n"
                    + new String(bytes, StandardCharsets.ISO_8859_1) + "\r\n"
                    + "--" + boundary + "--\r\n").getBytes(StandardCharsets.ISO_8859_1);

            HttpResponse<String> response = sendUpload(port, prepared, multipart, boundary, "POST");

            assertThat(response.statusCode()).isEqualTo(expectedHttpStatus);
            assertThat(tickets.status(prepared.batchId()).batch()).isEqualTo(expectedBatchStatus);
            assertThat(context.getBean(BusinessAttachmentRepository.class)
                    .findBatch(prepared.batchId()).orElseThrow().state()).isEqualTo(expectedBatchStatus);
        } finally {
            context.close();
            CROSS_LAYER_AFTER_RESPONSE.set(() -> { });
        }
    }

    private static HttpResponse<String> sendUpload(
            int port,
            BusinessAttachmentTicketService.PreparedBatch prepared,
            byte[] body,
            String boundary,
            String method) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port
                        + "/business/attachments/uploads/" + prepared.batchId()))
                .header("Origin", "http://127.0.0.1:" + port)
                .header("Authorization", "Bearer desktop-token")
                .header("X-Desktop-Instance-Id", "instance-1")
                .header("X-Desktop-Session-Id", "desktop-1")
                .header("X-Business-Upload-Ticket", prepared.ticket())
                .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                .method(method, HttpRequest.BodyPublishers.ofByteArray(body)).build();
        return HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString());
    }

    private static byte[] multipart(String fileName, byte[] bytes, String boundary) {
        return ("--" + boundary + "\r\n"
                + "Content-Disposition: form-data; name=\"files\"; filename=\"" + fileName + "\"\r\n"
                + "Content-Type: application/pdf\r\n\r\n"
                + new String(bytes, StandardCharsets.ISO_8859_1) + "\r\n"
                + "--" + boundary + "--\r\n").getBytes(StandardCharsets.ISO_8859_1);
    }

    private static void assertUploadRootEmpty(Path runtime) throws IOException {
        Path uploadRoot = runtime.resolve("attachments").resolve("uploads");
        if (Files.notExists(uploadRoot)) return;
        try (var staged = Files.list(uploadRoot)) {
            assertThat(staged).isEmpty();
        }
    }

    private static void assertTextDoesNotContain(String actual, String... canaries) {
        for (String canary : canaries) {
            assertThat(actual).as("diagnostic boundary leaked canary").doesNotContain(canary);
        }
    }

    private static void assertBinaryFileDoesNotContain(Path file, String... canaries) throws IOException {
        byte[] content = Files.readAllBytes(file);
        for (String canary : canaries) {
            assertThat(indexOf(content, canary.getBytes(StandardCharsets.UTF_8)))
                    .as("SQLite leaked plaintext canary").isEqualTo(-1);
        }
    }

    private static void assertTreeDoesNotContain(Path root, String... canaries) throws IOException {
        try (Stream<Path> paths = Files.walk(root)) {
            for (Path path : paths.filter(Files::isRegularFile).toList()) {
                assertBinaryFileDoesNotContain(path, canaries);
            }
        }
    }

    private static int indexOf(byte[] haystack, byte[] needle) {
        outer: for (int offset = 0; offset <= haystack.length - needle.length; offset++) {
            for (int index = 0; index < needle.length; index++) {
                if (haystack[offset + index] != needle[index]) continue outer;
            }
            return offset;
        }
        return -1;
    }

    private RestClientBusinessAttachmentRemoteUploader uploader(long timeoutMillis) {
        return new RestClientBusinessAttachmentRemoteUploader(
                new BusinessOaProperties(server.baseUrl(), "/law-api", 2, timeoutMillis, true),
                (ignored, operation) -> operation.execute("access-token".toCharArray()));
    }

    private void assertUploadFails(RestClientBusinessAttachmentRemoteUploader uploader) throws Exception {
        var staged = staged("a.pdf", "%PDF-a");
        try {
            assertThatThrownBy(() -> uploader.upload(lease(1), List.of(staged)))
                    .isInstanceOf(RuntimeException.class);
        } finally {
            delete(staged.path());
        }
    }

    private static BusinessAttachmentRemoteUploader.StagedFile staged(String name, String content) throws Exception {
        Path path = Files.createTempFile("oa-upload-", ".part");
        byte[] bytes = content.getBytes(StandardCharsets.UTF_8);
        Files.write(path, bytes);
        return new BusinessAttachmentRemoteUploader.StagedFile(
                name, "application/pdf", bytes.length,
                BusinessAttachmentTicketService.sha256Hex(bytes), path);
    }

    private static void delete(Path path) {
        try { Files.deleteIfExists(path); } catch (IOException ignored) { }
    }

    private static TrustedDesktopConnection connection() {
        return new TrustedDesktopConnection("reservation-1", "instance-1", "desktop-1", "ws-1");
    }

    private static ReadyOaSessionLease lease(long generation) {
        return new ReadyOaSessionLease("auth-1", "instance-1", "desktop-1", "ws-1",
                "user-1", "tenant-1", "2", generation, "credential-" + generation,
                1, Instant.parse("2026-07-29T00:00:00Z"));
    }

    private static final class FakeOaServer implements AutoCloseable {
        private final HttpServer server;
        private final ExecutorService executor;
        private final AtomicReference<String> body = new AtomicReference<>();
        private final AtomicReference<String> path = new AtomicReference<>();
        private final AtomicReference<String> authorization = new AtomicReference<>();
        private final AtomicReference<ResponseSpec> responseSpec = new AtomicReference<>();

        private FakeOaServer(HttpServer server, ExecutorService executor) {
            this.server = server;
            this.executor = executor;
        }

        static FakeOaServer start(String response, int status, long delayMillis) throws IOException {
            HttpServer raw = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
            ExecutorService executor = Executors.newSingleThreadExecutor();
            FakeOaServer fixture = new FakeOaServer(raw, executor);
            fixture.respondWith(response, status, delayMillis);
            raw.createContext("/", fixture::respond);
            raw.setExecutor(executor);
            raw.start();
            return fixture;
        }

        static FakeOaServer startDisconnecting() throws IOException {
            HttpServer raw = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
            ExecutorService executor = Executors.newSingleThreadExecutor();
            FakeOaServer fixture = new FakeOaServer(raw, executor);
            raw.createContext("/", exchange -> {
                fixture.capture(exchange);
                exchange.close();
            });
            raw.setExecutor(executor);
            raw.start();
            return fixture;
        }

        String baseUrl() {
            return "http://" + server.getAddress().getHostString() + ":" + server.getAddress().getPort();
        }

        String body() { return body.get(); }
        String path() { return path.get(); }
        String authorization() { return authorization.get(); }
        boolean executorTerminated() { return executor.isTerminated(); }

        void respondWith(String response, int status, long delayMillis) {
            responseSpec.set(new ResponseSpec(response, status, delayMillis));
        }

        private void respond(HttpExchange exchange) throws IOException {
            ResponseSpec current = responseSpec.get();
            if (current == null) throw new IllegalStateException("fake OA response is not configured");
            capture(exchange);
            if (current.delayMillis() > 0) {
                try { Thread.sleep(current.delayMillis()); }
                catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
            }
            byte[] bytes = current.body().getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(current.status(), bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
            CROSS_LAYER_AFTER_RESPONSE.get().run();
        }

        private void capture(HttpExchange exchange) throws IOException {
            path.set(exchange.getRequestURI().getPath());
            authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
            body.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.ISO_8859_1));
        }

        @Override public void close() {
            server.stop(0);
            executor.shutdown();
            try {
                if (!executor.awaitTermination(5, TimeUnit.SECONDS)) {
                    executor.shutdownNow();
                    if (!executor.awaitTermination(5, TimeUnit.SECONDS)) {
                        throw new AssertionError("fake OA executor did not terminate");
                    }
                }
            } catch (InterruptedException interrupted) {
                executor.shutdownNow();
                Thread.currentThread().interrupt();
                throw new AssertionError("interrupted while awaiting fake OA executor shutdown", interrupted);
            }
        }

        private record ResponseSpec(String body, int status, long delayMillis) { }
    }

    @Configuration(proxyBeanMethods = false)
    @EnableAutoConfiguration
    @Import({SQLiteDataSourceConfig.class, SQLiteConnectionInitializer.class, MyBatisPlusConfig.class,
            SQLiteBusinessAttachmentRepository.class,
            SQLiteBusinessAttachmentSecretCleanupRepository.class,
            BusinessAttachmentTicketService.class, BusinessAttachmentSecretCleanupService.class,
            BusinessResourceBlobStore.class, BusinessResourceHandleRegistry.class,
            BusinessAttachmentRecoveryService.class,
            BusinessLoopbackHttpSecurityFilter.class, BusinessAttachmentUploadController.class,
            BusinessUploadExceptionHandler.class})
    static class DurableUploadHttpTestApplication {
        @Bean SecretStore secretStore() { return CROSS_LAYER_SECRETS; }
        @Bean BusinessAttachmentFileIdStore attachmentFileIdStore(SecretStore secrets) {
            return new BusinessAttachmentFileIdStore(secrets);
        }
        @Bean DesktopSessionTokenProvider tokenProvider() {
            DesktopSessionTokenProvider provider = mock(DesktopSessionTokenProvider.class);
            when(provider.matches("desktop-token")).thenReturn(true);
            return provider;
        }
        @Bean BusinessDesktopConnectionRegistry connections() {
            BusinessDesktopConnectionRegistry registry = mock(BusinessDesktopConnectionRegistry.class);
            when(registry.findByDesktopSessionId("desktop-1"))
                    .thenReturn(java.util.Optional.of(connection()));
            return registry;
        }
        @Bean BusinessOaSessionRegistry sessions() {
            BusinessOaSessionRegistry registry = mock(BusinessOaSessionRegistry.class);
            when(registry.captureReady(connection())).thenAnswer(ignored -> CROSS_LAYER_LEASE.get());
            return registry;
        }
        @Bean BusinessAttachmentRemoteUploader uploader() {
            BusinessOaProperties properties = new BusinessOaProperties(
                    CROSS_LAYER_SERVER.get().baseUrl(), "/law-api", 2,
                    CROSS_LAYER_TIMEOUT_MILLIS.get(), true);
            return new RestClientBusinessAttachmentRemoteUploader(properties, (lease, operation) -> {
                if (CROSS_LAYER_LEASE.get().generation() != lease.generation()) {
                    throw new com.wzx.babiq.server.business.oa.session.OaAuthenticatedRequestExecutor.StaleLeaseException();
                }
                char[] token = "access-token".toCharArray();
                try {
                    var result = operation.execute(token);
                    if (CROSS_LAYER_LEASE.get().generation() != lease.generation()) {
                        throw new com.wzx.babiq.server.business.oa.session.OaAuthenticatedRequestExecutor.StaleLeaseException();
                    }
                    return result;
                } finally {
                    java.util.Arrays.fill(token, '\0');
                }
            });
        }
    }

    static final class MapSecretStore implements SecretStore {
        private final Map<String, String> values = new ConcurrentHashMap<>();
        @Override public String save(String namespace, String secretPlainText) {
            String ref = "test://" + java.util.UUID.randomUUID();
            values.put(ref, secretPlainText);
            return ref;
        }
        @Override public Optional<String> load(String secretRef) {
            return Optional.ofNullable(values.get(secretRef));
        }
        @Override public void delete(String secretRef) {
            values.remove(secretRef);
        }
        java.util.Set<String> aliases() {
            return java.util.Set.copyOf(values.keySet());
        }
        void clear() {
            values.clear();
        }
    }

    @Configuration(proxyBeanMethods = false)
    @EnableAutoConfiguration(exclude = {DataSourceAutoConfiguration.class, FlywayAutoConfiguration.class,
            com.baomidou.mybatisplus.autoconfigure.MybatisPlusAutoConfiguration.class})
    @Import({BusinessLoopbackHttpSecurityFilter.class, BusinessAttachmentUploadController.class,
            BusinessUploadExceptionHandler.class})
    static class UploadHttpTestApplication {
        @Bean
        BusinessAttachmentTicketService tickets() {
            return new BusinessAttachmentTicketService(
                    Clock.fixed(Instant.parse("2026-07-29T00:00:00Z"), ZoneOffset.UTC));
        }

        @Bean
        DesktopSessionTokenProvider tokenProvider() {
            DesktopSessionTokenProvider provider = mock(DesktopSessionTokenProvider.class);
            when(provider.matches("desktop-token")).thenReturn(true);
            return provider;
        }

        @Bean
        BusinessDesktopConnectionRegistry connections() {
            BusinessDesktopConnectionRegistry registry = mock(BusinessDesktopConnectionRegistry.class);
            when(registry.findByDesktopSessionId("desktop-1"))
                    .thenReturn(java.util.Optional.of(connection()));
            return registry;
        }

        @Bean
        BusinessOaSessionRegistry sessions() {
            BusinessOaSessionRegistry registry = mock(BusinessOaSessionRegistry.class);
            when(registry.captureReady(connection())).thenReturn(lease(1));
            return registry;
        }

        @Bean
        BusinessAttachmentRemoteUploader uploader() {
            return (ignored, files) -> new BusinessAttachmentRemoteUploader.UploadedRemoteFiles(
                    List.of("oa-file-id-http-canary".toCharArray()));
        }
    }
}
