package com.rokidos885.phone.traffic

import com.rokidos885.phone.nav.RouteOption

/**
 * Decides whether an alternative is worth interrupting the driver for. Pure logic: unit-test it.
 */
class DetourEvaluator(
    private val minSavingsSeconds: Int = 180,
    private val minSavingsFraction: Double = 0.08,
    /** Don't re-offer a route the user just dismissed for this long. */
    private val dismissCooldownMs: Long = 10 * 60_000,
) {
    private val dismissed = mutableMapOf<String, Long>()

    data class Decision(val option: RouteOption, val savesSeconds: Int, val extraMeters: Int)

    fun evaluate(
        currentRemainingSeconds: Int,
        currentRemainingMeters: Int,
        alternatives: List<RouteOption>,
        nowMs: Long,
    ): Decision? {
        val best = alternatives
            .filterNot { isCoolingDown(it, nowMs) }
            .minByOrNull { it.durationSeconds } ?: return null
        val saves = currentRemainingSeconds - best.durationSeconds
        val threshold = maxOf(minSavingsSeconds, (currentRemainingSeconds * minSavingsFraction).toInt())
        if (saves < threshold) return null
        return Decision(best, saves, best.distanceMeters - currentRemainingMeters)
    }

    fun markDismissed(option: RouteOption, nowMs: Long) {
        dismissed[signature(option)] = nowMs
    }

    private fun isCoolingDown(option: RouteOption, nowMs: Long): Boolean =
        dismissed[signature(option)]?.let { nowMs - it < dismissCooldownMs } == true

    // Route tokens change every request; the description ("via I-280") is stable enough to dedupe.
    private fun signature(option: RouteOption) = option.description
}
