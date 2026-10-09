package net.jfloren.raceday.timer

import android.content.Context
import android.os.SystemClock
import androidx.core.content.edit
import kotlin.math.abs

/**
 * Keeps the timer in SharedPreferences so a countdown or race survives the process being killed.
 * Times are elapsedRealtime-based, which resets on reboot, so a snapshot from an earlier boot is discarded.
 */
class PreferencesSnapshotStore(context: Context) : TimerSnapshotStore {

    private val prefs = context.applicationContext.getSharedPreferences("start_sequence_timer", Context.MODE_PRIVATE)

    override fun load(): TimerSnapshot? {
        if (!prefs.contains(KEY_START_TIME)) return null
        // Wall-clock time of boot shifts by at least the previous uptime across a reboot,
        // but only by clock adjustments within one boot
        if (abs(bootWallTime() - prefs.getLong(KEY_BOOT_WALL_TIME, 0)) > BOOT_TOLERANCE_MS) {
            save(null)
            return null
        }
        return TimerSnapshot(
            startTime = prefs.getLong(KEY_START_TIME, 0),
            legStartSeconds = prefs.getString(KEY_LEG_START_SECONDS, null)
                ?.split(',')?.mapNotNull { it.toIntOrNull() }.orEmpty(),
            finishSecond = if (prefs.contains(KEY_FINISH_SECOND)) prefs.getInt(KEY_FINISH_SECOND, 0) else null
        )
    }

    override fun save(snapshot: TimerSnapshot?) {
        prefs.edit {
            clear()
            if (snapshot != null) {
                putLong(KEY_BOOT_WALL_TIME, bootWallTime())
                putLong(KEY_START_TIME, snapshot.startTime)
                putString(KEY_LEG_START_SECONDS, snapshot.legStartSeconds.joinToString(","))
                snapshot.finishSecond?.let { putInt(KEY_FINISH_SECOND, it) }
            }
        }
    }

    private fun bootWallTime(): Long = System.currentTimeMillis() - SystemClock.elapsedRealtime()

    companion object {
        private const val KEY_BOOT_WALL_TIME = "boot_wall_time"
        private const val KEY_START_TIME = "start_time"
        private const val KEY_LEG_START_SECONDS = "leg_start_seconds"
        private const val KEY_FINISH_SECOND = "finish_second"
        private const val BOOT_TOLERANCE_MS = 60_000L
    }
}
