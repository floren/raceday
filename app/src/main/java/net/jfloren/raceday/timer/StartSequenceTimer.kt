package net.jfloren.raceday.timer

import android.os.SystemClock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

sealed class StepEvent {
    object ShortSignal : StepEvent()
    object LongSignal : StepEvent()
}

data class StartSequenceState(
    // True only while counting down to the start
    val isRunning: Boolean = false,
    val remainingSeconds: Int = 300,
    val stepType: StepType = StepType.READY,
    // Stopwatch, once the race has started
    val raceElapsedSeconds: Int = 0,
    val legNumber: Int = 1,
    val legElapsedSeconds: Int = 0,
    // Race second at which each leg began, first leg first
    val legStartSeconds: List<Int> = listOf(0)
) {
    val isRacing: Boolean get() = stepType == StepType.START_0M

    /** How long each leg took (the last one so far, or up to the finish), first leg first. */
    val legDurations: List<Int>
        get() = legStartSeconds.mapIndexed { i, start ->
            (legStartSeconds.getOrNull(i + 1) ?: raceElapsedSeconds) - start
        }
}

enum class StepType {
    READY,
    WARNING_5M,
    PREP_4M,
    ONE_MINUTE,
    START_0M,
    FINISHED
}

/** Everything needed to rebuild the timer, e.g. after the process has been killed. */
data class TimerSnapshot(
    // Clock time of the start gun
    val startTime: Long,
    // Race second at which each leg began, first leg first
    val legStartSeconds: List<Int>,
    val finishSecond: Int?
)

interface TimerSnapshotStore {
    fun load(): TimerSnapshot?
    /** Saves the snapshot, or clears it when null. */
    fun save(snapshot: TimerSnapshot?)
}

class StartSequenceTimer(
    // Owns the ticking coroutine; should outlive any one screen so the timer keeps running
    private val scope: CoroutineScope,
    // Monotonic milliseconds that keep counting through deep sleep
    private val clock: () -> Long = { SystemClock.elapsedRealtime() },
    private val store: TimerSnapshotStore? = null
) {

    private val _state = MutableStateFlow(StartSequenceState())
    val state: StateFlow<StartSequenceState> = _state.asStateFlow()

    private val _eventFlow = MutableSharedFlow<StepEvent>()
    val eventFlow: SharedFlow<StepEvent> = _eventFlow.asSharedFlow()

    private var timerJob: Job? = null

    // Guards the fields below, and state updates from the ticking coroutine against calls from the main thread
    private val lock = Any()

    // Null when no sequence has been started
    private var startTime: Long? = null
    // Race second at which each leg began; the last is the current leg
    private var legStartSeconds = listOf(0)
    // Race second at which the race was finished, once it has been
    private var finishSecond: Int? = null

    init {
        store?.load()?.let { snapshot ->
            synchronized(lock) {
                startTime = snapshot.startTime
                legStartSeconds = snapshot.legStartSeconds.ifEmpty { listOf(0) }
                finishSecond = snapshot.finishSecond
                val tick = currentTick(snapshot.startTime)
                _state.value = stateAt(tick)
                if (finishSecond == null) {
                    launchTicker(snapshot.startTime, tick)
                }
            }
        }
    }

    /** Starts a new sequence, or syncs a running countdown to its next signal. Ignored mid-race. */
    fun startOrSync() {
        val current = _state.value
        when {
            current.isRacing -> return
            current.isRunning -> {
                val syncTarget = when {
                    current.remainingSeconds > 240 -> 240
                    current.remainingSeconds > 60 -> 60
                    else -> 0
                }
                startAt(syncTarget)
            }
            else -> startAt(300)
        }
    }

    fun startAt(initialSeconds: Int) {
        synchronized(lock) {
            timerJob?.cancel()
            // Each tick is scheduled against the fixed start time rather than chaining delay(1000)s,
            // so scheduling latency doesn't accumulate over the sequence or the race.
            val start = clock() + initialSeconds * 1000L
            startTime = start
            legStartSeconds = listOf(0)
            finishSecond = null
            _state.value = stateAt(-initialSeconds)
            persist()
            triggerStepSignal(scope, initialSeconds)
            launchTicker(start, -initialSeconds)
        }
    }

    /** Starts a new leg of the race. Ignored unless the race has started. */
    fun nextLeg() {
        synchronized(lock) {
            val start = startTime ?: return
            if (!_state.value.isRacing) return
            // From the clock rather than the last tick, which can lag it slightly
            val tick = currentTick(start)
            legStartSeconds = legStartSeconds + tick
            _state.value = stateAt(tick)
            persist()
        }
    }

    /** Stops the race stopwatch, keeping the final times on display. Ignored unless racing. */
    fun finishRace() {
        synchronized(lock) {
            val start = startTime ?: return
            if (!_state.value.isRacing) return
            timerJob?.cancel()
            timerJob = null
            finishSecond = currentTick(start)
            _state.value = stateAt(finishSecond!!)
            persist()
        }
    }

    fun stop() {
        synchronized(lock) {
            timerJob?.cancel()
            timerJob = null
            startTime = null
            finishSecond = null
            _state.value = StartSequenceState()
            persist()
        }
    }

    /**
     * Ticks once a second, aligned to [start]. Ticks count seconds relative to the start:
     * negative while counting down, the race time after.
     */
    private fun launchTicker(start: Long, fromTick: Int) {
        timerJob = scope.launch(Dispatchers.Default) {
            var tick = fromTick
            while (isActive) {
                delay(start + (tick + 1) * 1000L - clock())
                tick++
                synchronized(lock) {
                    // Cancelled while waiting for the lock; whoever cancelled has already set the state
                    if (!isActive) return@launch
                    _state.value = stateAt(tick)
                }

                if (tick == -240 || tick == -60 || tick == 0) {
                    triggerStepSignal(this, -tick)
                }
            }
        }
    }

    private fun currentTick(start: Long): Int = (clock() - start).floorDiv(1000L).toInt()

    private fun persist() {
        val start = startTime
        store?.save(start?.let { TimerSnapshot(it, legStartSeconds, finishSecond) })
    }

    private fun stateAt(tick: Int): StartSequenceState {
        val finish = finishSecond
        return when {
            finish != null -> StartSequenceState(
                remainingSeconds = 0,
                stepType = StepType.FINISHED,
                raceElapsedSeconds = finish,
                legNumber = legStartSeconds.size,
                legElapsedSeconds = finish - legStartSeconds.last(),
                legStartSeconds = legStartSeconds
            )
            tick < 0 -> StartSequenceState(
                isRunning = true,
                remainingSeconds = -tick,
                stepType = getStepType(-tick)
            )
            else -> StartSequenceState(
                remainingSeconds = 0,
                stepType = StepType.START_0M,
                raceElapsedSeconds = tick,
                legNumber = legStartSeconds.size,
                legElapsedSeconds = tick - legStartSeconds.last(),
                legStartSeconds = legStartSeconds
            )
        }
    }

    private fun triggerStepSignal(scope: CoroutineScope, seconds: Int) {
        scope.launch {
            if (seconds == 0) {
                _eventFlow.emit(StepEvent.LongSignal)
            } else {
                _eventFlow.emit(StepEvent.ShortSignal)
            }
        }
    }

    private fun getStepType(seconds: Int): StepType {
        return when {
            seconds > 240 -> StepType.WARNING_5M
            seconds in 61..240 -> StepType.PREP_4M
            seconds in 1..60 -> StepType.ONE_MINUTE
            else -> StepType.START_0M
        }
    }
}
