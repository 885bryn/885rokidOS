package com.rokidos885.protocol

import kotlinx.serialization.json.Json

/**
 * Converts [NavMessage] <-> the two strings carried in a CXR Caps frame: [ProtocolVersion.MAGIC] and JSON.
 * This module is plain JVM and never touches Caps; each app wraps these strings in Caps itself
 * (Caps.write(String) is on the SDK's ProGuard keep list, so we only use that).
 */
object ProtocolCodec {
    private val json = Json {
        ignoreUnknownKeys = true   // newer phone + older glasses must keep working
        encodeDefaults = false
        classDiscriminator = "t"
    }

    fun encode(message: NavMessage): Pair<String, String> =
        ProtocolVersion.MAGIC to json.encodeToString(NavMessage.serializer(), message)

    /** Returns null for frames that aren't ours or fail to parse; callers log and drop them. */
    fun decode(magic: String?, body: String?): NavMessage? {
        if (magic != ProtocolVersion.MAGIC || body == null) return null
        return runCatching { json.decodeFromString(NavMessage.serializer(), body) }.getOrNull()
    }
}

/** Monotonic sequence numbers per sender, so receivers can drop stale/out-of-order frames. */
class SeqCounter(start: Long = 0) {
    private var value = start
    @Synchronized fun next(): Long = ++value
}
