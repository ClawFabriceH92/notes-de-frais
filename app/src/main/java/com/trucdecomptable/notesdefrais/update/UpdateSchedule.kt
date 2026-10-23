package com.trucdecomptable.notesdefrais.update

/**
 * When an automatic check may run. A check that never reached GitHub does not
 * hold the hourly slot: the caller resets the timestamp on failure.
 */
internal object UpdateSchedule {

    const val MIN_INTERVAL_MS = 60 * 60_000L
    const val NEVER = 0L

    fun isDue(manual: Boolean, lastCheckAt: Long, now: Long): Boolean =
        manual || lastCheckAt == NEVER || now - lastCheckAt >= MIN_INTERVAL_MS
}
