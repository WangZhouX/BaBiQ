package com.wzx.huitai.desktop.auth

/** The business shell is usable only in [READY], not merely when local tokens exist. */
enum class BusinessAccessGateState {
    STARTING,
    RESTORING,
    SIGNED_OUT,
    VERIFYING,
    AUTHENTICATING,
    SELECTING_TENANT,
    REGISTERING_AGENT,
    READY,
    SIGNING_OUT,
}
