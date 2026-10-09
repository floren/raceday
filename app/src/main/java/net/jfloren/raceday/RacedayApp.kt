package net.jfloren.raceday

import android.app.Application
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import net.jfloren.raceday.timer.PreferencesSnapshotStore
import net.jfloren.raceday.timer.SoundPlayer
import net.jfloren.raceday.timer.StartSequenceTimer
import net.jfloren.raceday.timer.StepEvent

/**
 * Owns the start sequence timer and its signals, so a countdown or race keeps running (and sounding)
 * after the activity is closed, and is picked up again when it's reopened.
 */
class RacedayApp : Application() {

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    lateinit var startSequenceTimer: StartSequenceTimer
        private set

    override fun onCreate() {
        super.onCreate()
        val soundPlayer = SoundPlayer(this)
        startSequenceTimer = StartSequenceTimer(appScope, store = PreferencesSnapshotStore(this))

        appScope.launch {
            startSequenceTimer.eventFlow.collect { event ->
                when (event) {
                    StepEvent.ShortSignal -> soundPlayer.playShortSignal()
                    StepEvent.LongSignal -> soundPlayer.playLongSignal()
                }
            }
        }
    }
}
