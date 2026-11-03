package com.rokidos885.protocol

/**
 * CXR channel keys. Phone sends with `CxrSession.sendCustomCmd(PHONE_TO_GLASSES, ...)`, glasses
 * `subscribe(PHONE_TO_GLASSES, ...)`. Glasses reply with `CXRServiceBridge.sendMessage(GLASSES_TO_PHONE, ...)`,
 * phone filters `onCustomCmdResult(GLASSES_TO_PHONE, ...)`.
 *
 * Every frame on either channel is a Caps of exactly two strings: [MAGIC, json]. See [ProtocolCodec].
 */
object Channels {
    const val PHONE_TO_GLASSES = "rknav_down"
    const val GLASSES_TO_PHONE = "rknav_up"
}

object ProtocolVersion {
    const val MAGIC = "rknav/1"
    const val CURRENT = 1
}

/**
 * Soft cap per frame. The CXR docs warn about an underlying transport limit without stating it.
 * TODO(M3): measure the real limit on hardware and tune.
 */
const val MAX_FRAME_BYTES = 8 * 1024
