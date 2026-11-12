package com.salahoard.mustnotify.poll

/**
 * Pull right after the inverter's data refreshes. Observed: new data every ~5 min 10 s, so the next poll is aimed
 * 330 s after the timestamp of the data we just got (age 2 min -> wait 3.5 min, age 4 min -> wait 1.5 min, ...).
 */
object PollSchedule {
    const val TARGET_AGE_SECONDS = 330L
    const val MIN_DELAY_SECONDS = 30L
    const val FALLBACK_DELAY_SECONDS = 300L

    /** Seconds to wait after a successful poll whose data was [ageSeconds] old. */
    fun delayAfterSuccessSeconds(ageSeconds: Long?): Long = when {
        ageSeconds == null || ageSeconds < 0 -> FALLBACK_DELAY_SECONDS // no/odd timestamp: plain 5-minute cadence
        ageSeconds >= 15 * 60 -> FALLBACK_DELAY_SECONDS                // stale: the inverter stopped reporting, don't hammer
        ageSeconds >= 10 * 60 -> 120L                                  // very late: check a bit more often
        else -> maxOf(MIN_DELAY_SECONDS, TARGET_AGE_SECONDS - ageSeconds)
    }

    /** Back-off for consecutive failures (no internet, server down): 60, 120, 240, then 300 s. */
    fun delayAfterFailureSeconds(consecutiveFailures: Int): Long {
        val step = (consecutiveFailures - 1).coerceIn(0, 3)
        return minOf(60L shl step, FALLBACK_DELAY_SECONDS)
    }
}
