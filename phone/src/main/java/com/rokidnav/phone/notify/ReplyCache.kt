package com.rokidnav.phone.notify

/**
 * Remembers a value per notification key for [ttlMs] (a reply must be sent while the notification is still in the
 * shade) and at most [max] keys. Pure Kotlin so the expiry rule is unit-tested.
 */
class ReplyCache<T>(private val ttlMs: Long = 120_000, private val max: Int = 20, private val clock: () -> Long = System::currentTimeMillis) {
    private val entries = LinkedHashMap<String, Pair<Long, T>>()

    fun put(key: String, value: T) {
        purge()
        entries.remove(key)
        entries[key] = clock() to value
        while (entries.size > max) entries.remove(entries.keys.first())
    }

    fun get(key: String): T? { purge(); return entries[key]?.second }

    fun remove(key: String) { entries.remove(key) }

    private fun purge() {
        val now = clock()
        entries.entries.removeAll { now - it.value.first > ttlMs }
    }
}
