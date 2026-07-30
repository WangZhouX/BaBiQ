package com.wzx.babiq.server.business.oa.client;

/** Stable workbench adapter failure; remote body and URL are intentionally omitted. */
public final class OaWorkbenchException extends RuntimeException {
    public OaWorkbenchException() { super("REMOTE_PROTOCOL_ERROR"); }
    public OaWorkbenchException(String message) { super(message); }
}
