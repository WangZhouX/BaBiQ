package com.wzx.babiq.server.business.oa.client;
public class OaAuthenticationException extends IllegalStateException {
    private final OaAuthenticationError error;
    public OaAuthenticationException(OaAuthenticationError error) { super(error.name()); this.error = error; }
    public OaAuthenticationError error() { return error; }
}
