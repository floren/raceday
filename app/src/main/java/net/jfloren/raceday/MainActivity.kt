package net.jfloren.raceday

import android.content.Intent
import android.net.wifi.WifiManager
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.Window
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.glass.view.WindowUtils
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import net.jfloren.raceday.nmea.Direction
import net.jfloren.raceday.nmea.DirectionKind
import net.jfloren.raceday.nmea.NmeaData
import net.jfloren.raceday.nmea.NmeaParser
import net.jfloren.raceday.nmea.NmeaReceiverStatus
import net.jfloren.raceday.nmea.SelfTest
import net.jfloren.raceday.nmea.UdpNmeaReceiver
import net.jfloren.raceday.timer.StartSequenceState
import net.jfloren.raceday.timer.StartSequenceTimer
import net.jfloren.raceday.timer.StepType
import java.util.Locale

// A plain ComponentActivity (not AppCompat) so menus are the platform's, drawn as Glass cards
class MainActivity : ComponentActivity() {

    private lateinit var udpNmeaReceiver: UdpNmeaReceiver
    private lateinit var sogValueTextView: TextView
    private lateinit var headingValueTextView: TextView
    private lateinit var headingLabelTextView: TextView
    private lateinit var nmeaStatusTextView: TextView

    private lateinit var timerLabelTextView: TextView
    private lateinit var timerValueTextView: TextView
    private lateinit var legLabelTextView: TextView
    private lateinit var legValueTextView: TextView
    private lateinit var flagStatusTextView: TextView
    private lateinit var rightPanel: LinearLayout

    // Lives in the application so it keeps running while this activity is closed
    private val startSequenceTimer: StartSequenceTimer
        get() = (application as RacedayApp).startSequenceTimer

    private var screenTimeoutJob: Job? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // "ok glass" voice menu inside the activity. Must be requested before the decor view exists,
        // so before anything touches window.decorView (as the immersive flags below do).
        window.requestFeature(WindowUtils.FEATURE_VOICE_COMMANDS)

        // Hide status bars for full immersive display on Glass
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility = (
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                        or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                        or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                        or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        or View.SYSTEM_UI_FLAG_FULLSCREEN
                        or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                )

        setContentView(R.layout.activity_main)

        sogValueTextView = findViewById(R.id.sogValue)
        headingValueTextView = findViewById(R.id.headingValue)
        headingLabelTextView = findViewById(R.id.headingLabel)
        nmeaStatusTextView = findViewById(R.id.nmeaStatus)
        timerLabelTextView = findViewById(R.id.timerLabel)
        timerValueTextView = findViewById(R.id.timerValue)
        legLabelTextView = findViewById(R.id.legLabel)
        legValueTextView = findViewById(R.id.legValue)
        flagStatusTextView = findViewById(R.id.flagStatus)
        rightPanel = findViewById(R.id.rightPanel)

        rightPanel.setOnClickListener {
            val stepType = startSequenceTimer.state.value.stepType
            if (stepType == StepType.START_0M || stepType == StepType.FINISHED) {
                // Lap and finish go through a menu, so a stray tap can't trigger them
                openOptionsMenu()
            } else {
                // A single tap is kept for starting and syncing, which have to be quick
                startSequenceTimer.startOrSync()
            }
        }

        udpNmeaReceiver = UdpNmeaReceiver(this, 10110)

        // NMEA data collection
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                // Re-rendered every second too, so a compass that stops sending gives way to COG
                // even if the remaining sentences don't change the data
                val everySecond = flow {
                    while (true) {
                        emit(Unit)
                        delay(1_000)
                    }
                }
                combine(udpNmeaReceiver.nmeaData, everySecond) { data, _ -> data }.collect { data ->
                    data.sogKnots?.let { sog ->
                        sogValueTextView.text = String.format(Locale.US, "%.1f", sog)
                    } ?: run {
                        sogValueTextView.setText(R.string.sog_default)
                    }
                    renderDirection(data.direction(NmeaParser.monotonicMillis()))
                }
            }
        }

        // Receiver diagnostics while no data is arriving, since the Glass can't be debugged over adb
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                combine(udpNmeaReceiver.nmeaData, udpNmeaReceiver.status) { data, status -> data to status }
                    .collect { (data, status) -> renderNmeaStatus(data, status) }
            }
        }

        // Start sequence timer state collection
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                startSequenceTimer.state.collect { state -> renderTimer(state) }
            }
        }

        // Keep the display on for the whole countdown; resume the normal timeout once it ends
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                startSequenceTimer.state
                    .map { it.isRunning }
                    .distinctUntilChanged()
                    .collect { keepScreenOnLonger() }
            }
        }

        // Voice commands are on for the whole race and off otherwise, so the "ok glass" prompt only
        // shows once the race has started
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                startSequenceTimer.state
                    .map { it.isRacing }
                    .distinctUntilChanged()
                    .collect { window.invalidatePanelMenu(WindowUtils.FEATURE_VOICE_COMMANDS) }
            }
        }

        // Wake the screen for each signal (the sounds themselves are played by RacedayApp)
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                startSequenceTimer.eventFlow.collect { keepScreenOnLonger() }
            }
        }
    }

    private fun renderDirection(direction: Direction?) {
        headingLabelTextView.setText(
            when {
                direction == null -> R.string.heading_label
                direction.kind == DirectionKind.HEADING ->
                    if (direction.magnetic) R.string.heading_label_magnetic else R.string.heading_label_true
                else -> if (direction.magnetic) R.string.cog_label_magnetic else R.string.cog_label_true
            }
        )
        if (direction != null) {
            headingValueTextView.text = String.format(Locale.US, "%03d°", direction.wholeDegrees)
        } else {
            headingValueTextView.setText(R.string.heading_default)
        }
    }

    private fun renderNmeaStatus(data: NmeaData, status: NmeaReceiverStatus) {
        val noData = !data.hasData
        nmeaStatusTextView.visibility = if (noData) View.VISIBLE else View.GONE
        if (!noData) return

        val ip = wifiIpAddress() ?: getString(R.string.nmea_status_no_wifi)
        val lines = mutableListOf(getString(R.string.nmea_status, udpNmeaReceiver.port, ip, status.packets))
        lines += getString(
            R.string.nmea_status_checks,
            getString(if (status.listening) R.string.nmea_check_yes else R.string.nmea_check_no),
            getString(if (status.multicastLockHeld) R.string.nmea_check_yes else R.string.nmea_check_no),
            when (status.selfTest) {
                SelfTest.PENDING -> getString(R.string.nmea_self_test_pending)
                SelfTest.RECEIVED -> getString(R.string.nmea_self_test_received)
                SelfTest.SEND_FAILED -> getString(R.string.nmea_self_test_failed, status.selfTestError)
            }
        )
        when {
            status.error != null -> lines += getString(R.string.nmea_status_error, status.error)
            status.lastSentenceType != null -> lines += getString(R.string.nmea_status_last, status.lastSentenceType)
        }
        nmeaStatusTextView.text = lines.joinToString("\n")
    }

    @Suppress("DEPRECATION")
    private fun wifiIpAddress(): String? {
        val wifiManager = applicationContext.getSystemService(WIFI_SERVICE) as? WifiManager ?: return null
        val ip = wifiManager.connectionInfo?.ipAddress ?: 0
        if (ip == 0) return null
        // Little-endian int
        return String.format(Locale.US, "%d.%d.%d.%d", ip and 0xff, ip shr 8 and 0xff, ip shr 16 and 0xff, ip shr 24 and 0xff)
    }

    private fun renderTimer(state: StartSequenceState) {
        val ready = state.stepType == StepType.READY
        // Race times are shown while racing, and frozen once finished
        val stopwatch = state.isRacing || state.stepType == StepType.FINISHED

        // While counting down, the countdown is the only thing shown on this side
        timerLabelTextView.visibility = if (ready || stopwatch) View.VISIBLE else View.GONE
        flagStatusTextView.visibility = if (ready) View.VISIBLE else View.GONE
        legLabelTextView.visibility = if (stopwatch) View.VISIBLE else View.GONE
        legValueTextView.visibility = if (stopwatch) View.VISIBLE else View.GONE

        rightPanel.setBackgroundResource(
            if (state.stepType == StepType.ONE_MINUTE) R.color.sequence_final_minute else R.color.black
        )

        if (stopwatch) {
            timerLabelTextView.setText(if (state.isRacing) R.string.race_label else R.string.finished_label)
            timerValueTextView.text = formatStopwatch(state.raceElapsedSeconds)
            legLabelTextView.text = getString(R.string.leg_label, state.legNumber)
            legValueTextView.text = formatStopwatch(state.legElapsedSeconds)
        } else {
            timerLabelTextView.setText(R.string.timer_label)
            val mins = state.remainingSeconds / 60
            val secs = state.remainingSeconds % 60
            timerValueTextView.text = String.format(Locale.US, "%02d:%02d", mins, secs)
        }
    }

    private fun formatStopwatch(totalSeconds: Int): String =
        String.format(Locale.US, "%d:%02d", totalSeconds / 60, totalSeconds % 60)

    // The tap menu (options panel) and "ok glass" (voice commands panel) share item ids
    private fun isRaceMenuPanel(featureId: Int) =
        featureId == WindowUtils.FEATURE_VOICE_COMMANDS || featureId == Window.FEATURE_OPTIONS_PANEL

    override fun onCreatePanelMenu(featureId: Int, menu: Menu): Boolean {
        when (featureId) {
            WindowUtils.FEATURE_VOICE_COMMANDS -> menuInflater.inflate(R.menu.voice, menu)
            Window.FEATURE_OPTIONS_PANEL -> menuInflater.inflate(R.menu.race, menu)
            else -> return super.onCreatePanelMenu(featureId, menu)
        }
        return true
    }

    override fun onPreparePanel(featureId: Int, view: View?, menu: Menu): Boolean {
        val racing = startSequenceTimer.state.value.isRacing
        when (featureId) {
            // Only during the race: starting by voice isn't precise enough, and the countdown shows
            // nothing but the countdown
            WindowUtils.FEATURE_VOICE_COMMANDS -> return racing
            Window.FEATURE_OPTIONS_PANEL -> {
                menu.findItem(R.id.action_start_sequence).isVisible = !racing
                menu.findItem(R.id.action_next_leg).isVisible = racing
                menu.findItem(R.id.action_finish_race).isVisible = racing
                menu.findItem(R.id.action_show_results).isVisible =
                    startSequenceTimer.state.value.stepType == StepType.FINISHED
                return true
            }
        }
        return super.onPreparePanel(featureId, view, menu)
    }

    override fun onMenuItemSelected(featureId: Int, item: MenuItem): Boolean {
        if (isRaceMenuPanel(featureId)) {
            when (item.itemId) {
                R.id.action_start_sequence -> startSequenceTimer.startOrSync()
                R.id.action_next_leg -> startSequenceTimer.nextLeg()
                R.id.action_finish_race -> {
                    startSequenceTimer.finishRace()
                    showResults()
                }
                R.id.action_show_results -> showResults()
                else -> return super.onMenuItemSelected(featureId, item)
            }
            return true
        }
        return super.onMenuItemSelected(featureId, item)
    }

    private fun showResults() {
        if (startSequenceTimer.state.value.stepType == StepType.FINISHED) {
            startActivity(Intent(this, ResultsActivity::class.java))
        }
    }

    override fun onStart() {
        super.onStart()
        udpNmeaReceiver.start(lifecycleScope)
    }

    override fun onResume() {
        super.onResume()
        keepScreenOnLonger()
    }

    override fun onUserInteraction() {
        super.onUserInteraction()
        keepScreenOnLonger()
    }

    override fun onStop() {
        super.onStop()
        screenTimeoutJob?.cancel()
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        udpNmeaReceiver.stop()
    }

    private fun keepScreenOnLonger() {
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        screenTimeoutJob?.cancel()
        if (startSequenceTimer.state.value.isRunning) {
            // No timeout during the countdown; it's rescheduled when the timer stops
            return
        }
        screenTimeoutJob = lifecycleScope.launch {
            delay(15_000)
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }
}
