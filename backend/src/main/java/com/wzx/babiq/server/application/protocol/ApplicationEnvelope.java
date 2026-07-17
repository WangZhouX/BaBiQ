package com.wzx.babiq.server.application.protocol;

/**
 * 业务桌面应用协议的公共 envelope 字段。
 *
 * <p>这些字段只描述 wire contract，不承载处理器、持久化或可信身份判断。</p>
 */
public sealed interface ApplicationEnvelope
        permits ApplicationIdentityMessage, ApplicationCatalogMessage, ApplicationActionMessage {

    String protocolVersion();

    String desktopInstanceId();

    String desktopSessionId();

    String authSessionId();

    long identityEpoch();

    long sequence();

    String generatedAt();

    String userId();

    String tenantId();

    String platformId();
}
