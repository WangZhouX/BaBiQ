package com.wzx.huitai.agent.protocol

object ApplicationProtocolLimits {
    const val MAX_ENVELOPE_BYTES: Int = 256 * 1024
    const val MAX_CATALOG_PAYLOAD_BYTES: Int = 128 * 1024
    const val MAX_CONTEXT_PAYLOAD_BYTES: Int = 128 * 1024
    const val MAX_ACTION_INPUT_BYTES: Int = 64 * 1024
    const val MAX_ACTION_RESULT_BYTES: Int = 64 * 1024
}
