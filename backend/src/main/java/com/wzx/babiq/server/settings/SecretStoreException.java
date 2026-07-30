package com.wzx.babiq.server.settings;

/** 不携带 secretRef、alias、存储路径或底层 cause 的稳定 SecretStore 失败。 */
public final class SecretStoreException extends IllegalStateException {
    private final String resultCode;

    public SecretStoreException(String resultCode, String message) {
        super(message);
        this.resultCode = resultCode;
    }

    public String resultCode() {
        return resultCode;
    }
}
