package net.jfloren.raceday

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import net.jfloren.raceday.timer.StartSequenceTimer
import net.jfloren.raceday.timer.StepType
import net.jfloren.raceday.timer.TimerSnapshot
import net.jfloren.raceday.timer.TimerSnapshotStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class StartSequenceTimerTest {

    private val testScope = CoroutineScope(Dispatchers.Unconfined)

    private class MemoryStore(var snapshot: TimerSnapshot? = null) : TimerSnapshotStore {
        override fun load() = snapshot
        override fun save(snapshot: TimerSnapshot?) {
            this.snapshot = snapshot
        }
    }

    @Test
    fun testStartSequenceInitialState() {
        val timer = StartSequenceTimer(testScope, clock = { 0L })
        assertEquals(300, timer.state.value.remainingSeconds)
        assertEquals(StepType.READY, timer.state.value.stepType)
    }

    @Test
    fun testStartSequenceSteps() {
        val timer = StartSequenceTimer(testScope, clock = { 0L })

        timer.startAt(300)
        assertEquals(StepType.WARNING_5M, timer.state.value.stepType)

        timer.startAt(240)
        assertEquals(StepType.PREP_4M, timer.state.value.stepType)

        timer.startAt(60)
        assertEquals(StepType.ONE_MINUTE, timer.state.value.stepType)

        timer.startAt(0)
        assertEquals(StepType.START_0M, timer.state.value.stepType)
        timer.stop()
    }

    @Test
    fun testNextLegIgnoredBeforeStart() {
        val timer = StartSequenceTimer(testScope, clock = { 0L })

        timer.nextLeg()
        assertEquals(1, timer.state.value.legNumber)

        timer.startAt(60)
        timer.nextLeg()
        assertEquals(1, timer.state.value.legNumber)
        timer.stop()
    }

    @Test
    fun testNextLegDuringRace() {
        val timer = StartSequenceTimer(testScope, clock = { 0L })

        timer.startAt(0)
        assertTrue(timer.state.value.isRacing)
        assertEquals(1, timer.state.value.legNumber)

        timer.nextLeg()
        assertEquals(2, timer.state.value.legNumber)
        assertEquals(0, timer.state.value.legElapsedSeconds)
        timer.stop()
    }

    @Test
    fun testFinishRace() {
        var now = 0L
        val timer = StartSequenceTimer(testScope, clock = { now })

        timer.startAt(0)
        now = 125_400L
        timer.finishRace()

        val state = timer.state.value
        assertEquals(StepType.FINISHED, state.stepType)
        assertFalse(state.isRacing)
        assertEquals(125, state.raceElapsedSeconds)

        // A finished race isn't restarted by nextLeg
        timer.nextLeg()
        assertEquals(1, timer.state.value.legNumber)
    }

    @Test
    fun testStartOrSyncIgnoredWhileRacing() {
        val timer = StartSequenceTimer(testScope, clock = { 0L })

        timer.startAt(0)
        timer.startOrSync()
        assertTrue(timer.state.value.isRacing)
        timer.stop()
    }

    @Test
    fun testResumeFromSnapshot() {
        val store = MemoryStore()
        var now = 1_000_000L
        val first = StartSequenceTimer(testScope, clock = { now }, store = store)
        first.startAt(300)

        // Process restarted 100.5s later, mid-countdown
        now += 100_500L
        val resumed = StartSequenceTimer(testScope, clock = { now }, store = store)
        assertTrue(resumed.state.value.isRunning)
        assertEquals(200, resumed.state.value.remainingSeconds)
        assertEquals(StepType.PREP_4M, resumed.state.value.stepType)

        first.stop()
        assertNull(store.snapshot)
        resumed.stop()
    }

    @Test
    fun testResumeRaceLegs() {
        val store = MemoryStore()
        var now = 0L
        val first = StartSequenceTimer(testScope, clock = { now }, store = store)
        first.startAt(0)
        first.nextLeg()
        first.stop()

        // Rebuild mid-race from a snapshot: started at 0, leg 3 began at 600s
        store.snapshot = TimerSnapshot(startTime = 0L, legStartSeconds = listOf(0, 250, 600), finishSecond = null)
        now = 754_000L
        val resumed = StartSequenceTimer(testScope, clock = { now }, store = store)
        val state = resumed.state.value
        assertTrue(state.isRacing)
        assertEquals(754, state.raceElapsedSeconds)
        assertEquals(3, state.legNumber)
        assertEquals(154, state.legElapsedSeconds)
        assertEquals(listOf(250, 350, 154), state.legDurations)
        resumed.stop()
    }

    @Test
    fun testLegTimesAtFinish() {
        var now = 0L
        val timer = StartSequenceTimer(testScope, clock = { now })

        timer.startAt(0)
        now = 90_200L
        timer.nextLeg()
        now = 200_900L
        timer.nextLeg()
        now = 245_000L
        timer.finishRace()

        val state = timer.state.value
        assertEquals(3, state.legNumber)
        assertEquals(listOf(0, 90, 200), state.legStartSeconds)
        assertEquals(listOf(90, 110, 45), state.legDurations)
        assertEquals(state.raceElapsedSeconds, state.legDurations.sum())

        // A new sequence starts with no leg history
        timer.startAt(300)
        assertEquals(listOf(0), timer.state.value.legStartSeconds)
        timer.stop()
    }
}
