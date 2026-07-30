package com.wzx.babiq.server.business.upload;

import com.wzx.babiq.server.application.auth.TrustedDesktopConnection;
import com.wzx.babiq.server.business.oa.session.ReadyOaSessionLease;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

import java.util.Optional;

/** Authenticated loopback proxy for server-registered opaque resource handles. */
@RestController
@ConditionalOnProperty(prefix = "babiq.business", name = "enabled", havingValue = "true")
public final class BusinessResourceProxyController {
    private final BusinessResourceHandleRegistry registry;

    public BusinessResourceProxyController(BusinessResourceHandleRegistry registry) {
        this.registry = registry;
    }

    @GetMapping("/business/resources/{opaqueHandle}")
    public ResponseEntity<byte[]> get(@PathVariable String opaqueHandle, HttpServletRequest request) {
        TrustedDesktopConnection connection = attribute(request, BusinessLoopbackHttpSecurityFilter.CONNECTION_ATTRIBUTE, TrustedDesktopConnection.class);
        ReadyOaSessionLease lease = attribute(request, BusinessLoopbackHttpSecurityFilter.LEASE_ATTRIBUTE, ReadyOaSessionLease.class);
        Optional<BusinessResourceHandleRegistry.StoredResource> resource = registry.resolve(opaqueHandle, connection, lease);
        if (resource.isEmpty()) {
            throw new BusinessAttachmentTicketService.TicketUnavailableException(
                    "business resource is unavailable");
        }
        BusinessResourceHandleRegistry.StoredResource value = resource.orElseThrow();
        return ResponseEntity.ok().cacheControl(CacheControl.noStore().mustRevalidate())
                .header(HttpHeaders.CONTENT_TYPE, value.mediaType())
                .header(HttpHeaders.CONTENT_LENGTH, Integer.toString(value.bytes().length))
                .body(value.bytes());
    }

    private static <T> T attribute(HttpServletRequest request, String key, Class<T> type) {
        Object value = request.getAttribute(key);
        if (!type.isInstance(value)) throw new BusinessAttachmentUploadController.BusinessUploadUnavailableException();
        return type.cast(value);
    }

    static ResponseEntity<byte[]> unavailable() {
        return ResponseEntity.status(404).header("X-Business-Code", "BUSINESS_RESOURCE_UNAVAILABLE").body(new byte[0]);
    }
}
