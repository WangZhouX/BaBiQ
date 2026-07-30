package com.wzx.babiq.server.business.oa.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.ConstructorBinding;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.net.InetAddress;
import java.net.URI;
import java.net.URISyntaxException;

@ConfigurationProperties(prefix = "huitai.oa")
public record BusinessOaProperties(String baseUrl,
                                   @DefaultValue("/law-api") String apiPrefix,
                                   @DefaultValue("2") int platformId,
                                   @DefaultValue("30000") long requestTimeoutMs,
                                   @DefaultValue("false") boolean allowPrivateHttp) {
    public BusinessOaProperties() { this("", "/law-api", 2, 30_000, false); }

    @ConstructorBinding
    public BusinessOaProperties {
        baseUrl = baseUrl == null ? "" : baseUrl.trim();
        apiPrefix = apiPrefix == null ? "" : apiPrefix.trim();
        if (apiPrefix.isBlank() || !apiPrefix.startsWith("/") || apiPrefix.contains("..")
                || apiPrefix.contains("?") || apiPrefix.contains("#"))
            throw new IllegalArgumentException("apiPrefix must be an absolute path");
        if (platformId <= 0 || requestTimeoutMs <= 0) throw new IllegalArgumentException("invalid OA configuration");
        if (!baseUrl.isBlank()) {
            URI uri;
            try { uri = URI.create(baseUrl); } catch (IllegalArgumentException e) { throw new IllegalArgumentException("baseUrl must be HTTP(S)"); }
            if (!("http".equalsIgnoreCase(uri.getScheme()) || "https".equalsIgnoreCase(uri.getScheme()))
                    || uri.getHost() == null || uri.getRawQuery() != null || uri.getRawFragment() != null
                    || uri.getRawUserInfo() != null) throw new IllegalArgumentException("baseUrl must be HTTP(S) without credentials/query");
            if (!allowPrivateHttp && "http".equalsIgnoreCase(uri.getScheme())) throw new IllegalArgumentException("private HTTP is disabled");
            if (allowPrivateHttp && !isPrivateOrLoopback(uri.getHost())) throw new IllegalArgumentException("private HTTP host required");
        }
    }

    public String endpointBase() { return baseUrl.replaceAll("/+$", "") + apiPrefix.replaceAll("/+$", ""); }

    private static boolean isPrivateOrLoopback(String host) {
        try {
            InetAddress address = InetAddress.getByName(host);
            return address.isLoopbackAddress() || address.isSiteLocalAddress() || address.isLinkLocalAddress();
        } catch (Exception e) { return false; }
    }
}
