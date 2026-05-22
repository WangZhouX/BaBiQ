package com.wzx.babiq.server.sandbox;

/**
 * 沙箱违规异常。
 */
public class SandboxViolationException extends RuntimeException {

    public SandboxViolationException(String message) {
        super(message);
    }
}
