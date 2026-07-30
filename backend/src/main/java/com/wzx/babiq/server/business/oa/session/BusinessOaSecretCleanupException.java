package com.wzx.babiq.server.business.oa.session;

/** 不携带 SecretStore 引用、路径或底层 cause 的固定清理异常。 */
public final class BusinessOaSecretCleanupException extends IllegalStateException {
    private final String resultCode;

    public BusinessOaSecretCleanupException(String resultCode, String message) {
        super(message);
        this.resultCode = resultCode;
    }

    public String resultCode() {
        return resultCode;
    }
}
