package com.salahoard.mustnotify.poll

import kotlin.test.Test
import kotlin.test.assertEquals

class PollScheduleTest {
    @Test fun pullsRightAfterTheNextExpectedUpdate() {
        assertEquals(210L, PollSchedule.delayAfterSuccessSeconds(120))   // updated 2 min ago -> 3.5 min later
        assertEquals(150L, PollSchedule.delayAfterSuccessSeconds(180))   // 3 min -> 2.5 min
        assertEquals(90L, PollSchedule.delayAfterSuccessSeconds(240))    // 4 min -> 1.5 min
        assertEquals(30L, PollSchedule.delayAfterSuccessSeconds(300))    // 5 min -> 0.5 min
        assertEquals(330L, PollSchedule.delayAfterSuccessSeconds(0))
    }

    @Test fun neverHammersTheServer() {
        assertEquals(30L, PollSchedule.delayAfterSuccessSeconds(310))    // update is late: retry soon, but not faster than 30 s
        assertEquals(30L, PollSchedule.delayAfterSuccessSeconds(599))
        assertEquals(120L, PollSchedule.delayAfterSuccessSeconds(600))
        assertEquals(120L, PollSchedule.delayAfterSuccessSeconds(899))
        assertEquals(300L, PollSchedule.delayAfterSuccessSeconds(900))   // stale: plain 5-minute cadence
        assertEquals(300L, PollSchedule.delayAfterSuccessSeconds(null))
        assertEquals(300L, PollSchedule.delayAfterSuccessSeconds(-5))
    }

    @Test fun failuresBackOff() {
        assertEquals(listOf(60L, 120L, 240L, 300L, 300L), listOf(1, 2, 3, 4, 10).map { PollSchedule.delayAfterFailureSeconds(it) })
    }
}
