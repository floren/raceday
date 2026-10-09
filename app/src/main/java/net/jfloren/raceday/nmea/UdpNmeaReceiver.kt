package net.jfloren.raceday.nmea

import android.content.Context
import android.net.wifi.WifiManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.util.concurrent.atomic.AtomicReference

/** What the receiver is doing, for on-screen diagnostics when no data is arriving. */
data class NmeaReceiverStatus(
    val listening: Boolean = false,
    val packets: Int = 0,
    // Sentence type of the most recent line received, e.g. "GPRMC"
    val lastSentenceType: String? = null,
    val error: String? = null,
    val multicastLockHeld: Boolean = false,
    val selfTest: SelfTest = SelfTest.PENDING,
    val selfTestError: String? = null
)

/**
 * Whether a marker packet the receiver broadcasts to its own port came back. Received means this
 * device can receive broadcasts on the port, so missing data is a problem upstream.
 */
enum class SelfTest { PENDING, RECEIVED, SEND_FAILED }

class UdpNmeaReceiver(
    // Only used for the Wi-Fi multicast lock; null skips it (e.g. in unit tests)
    private val context: Context?,
    val port: Int = 10110,
    // Where the self-test marker is sent; null skips the self-test
    private val selfTestAddress: InetAddress? = InetAddress.getByAddress(byteArrayOf(-1, -1, -1, -1))
) {
    private val parser = NmeaParser()
    private val _nmeaData = MutableStateFlow(NmeaData())
    val nmeaData: StateFlow<NmeaData> = _nmeaData.asStateFlow()

    private val _status = MutableStateFlow(NmeaReceiverStatus())
    val status: StateFlow<NmeaReceiverStatus> = _status.asStateFlow()

    private var job: Job? = null
    private var multicastLock: WifiManager.MulticastLock? = null
    // Socket of the current run only, so a cancelled run finishing late can't overwrite it
    private var currentSocket: AtomicReference<DatagramSocket?>? = null

    fun start(scope: CoroutineScope) {
        if (job?.isActive == true) return

        // Acquire MulticastLock so Wi-Fi does not filter incoming UDP broadcasts
        try {
            val wifiManager = context?.applicationContext?.getSystemService(Context.WIFI_SERVICE) as? WifiManager
            multicastLock = wifiManager?.createMulticastLock("RacedayUdpNmeaLock")?.apply {
                setReferenceCounted(true)
                acquire()
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        _status.value = _status.value.copy(multicastLockHeld = multicastLock?.isHeld == true)

        val runSocket = AtomicReference<DatagramSocket?>()
        currentSocket = runSocket
        job = scope.launch(Dispatchers.IO) {
            val buffer = ByteArray(1024)
            // Reopen the socket after any failure (e.g. the port still held by the previous socket,
            // or the network dropping while the screen was off) rather than giving up for good
            while (isActive) {
                var newSocket: DatagramSocket? = null
                try {
                    newSocket = openSocket(port)
                    runSocket.set(newSocket)
                    _status.value = _status.value.copy(listening = true, error = null)
                    sendSelfTest()

                    while (isActive) {
                        try {
                            val packet = DatagramPacket(buffer, buffer.size)
                            newSocket.receive(packet)
                            val receivedString = String(packet.data, 0, packet.length, Charsets.US_ASCII)
                            if (receivedString.startsWith(SELF_TEST_MARKER)) {
                                _status.value = _status.value.copy(selfTest = SelfTest.RECEIVED, selfTestError = null)
                                continue
                            }

                            val lines = receivedString.split("\r\n", "\n").filter { it.isNotBlank() }
                            // Status before data, so anything seeing new data also sees it counted
                            _status.value = _status.value.copy(
                                packets = _status.value.packets + 1,
                                lastSentenceType = lines.lastOrNull()?.trim()?.substringBefore(',')?.take(10)
                                    ?: _status.value.lastSentenceType
                            )
                            for (line in lines) {
                                _nmeaData.value = parser.parse(line, _nmeaData.value)
                            }
                        } catch (e: java.net.SocketTimeoutException) {
                            // Socket timeout is expected when waiting for packets; loop around to check isActive
                        }
                    }
                } catch (e: Exception) {
                    if (isActive) {
                        e.printStackTrace()
                        _status.value = _status.value.copy(listening = false, error = e.toString())
                        delay(RETRY_DELAY_MS)
                    }
                } finally {
                    newSocket?.close()
                }
            }
        }
    }

    private fun sendSelfTest() {
        val address = selfTestAddress ?: return
        try {
            val bytes = SELF_TEST_MARKER.toByteArray(Charsets.US_ASCII)
            DatagramSocket().use { sender ->
                sender.broadcast = true
                sender.send(DatagramPacket(bytes, bytes.size, address, port))
            }
        } catch (e: Exception) {
            e.printStackTrace()
            _status.value = _status.value.copy(selfTest = SelfTest.SEND_FAILED, selfTestError = e.toString())
        }
    }

    fun stop() {
        job?.cancel()
        job = null
        // Closing unblocks a pending receive() and frees the port at once, so a quick restart
        // (e.g. the screen waking right after it went off) can bind it again. A socket opened
        // after this is closed by its own run, which sees it's been cancelled.
        currentSocket?.getAndSet(null)?.close()
        currentSocket = null

        try {
            if (multicastLock?.isHeld == true) {
                multicastLock?.release()
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        multicastLock = null
        _status.value = _status.value.copy(listening = false, multicastLockHeld = false)
    }

    companion object {
        private const val RETRY_DELAY_MS = 1000L
        private const val RECEIVE_TIMEOUT_MS = 3000
        // Not valid NMEA (no '$'), so it can never be mistaken for real data
        private const val SELF_TEST_MARKER = "RACEDAY-SELF-TEST"

        internal fun openSocket(port: Int, timeoutMs: Int = RECEIVE_TIMEOUT_MS): DatagramSocket {
            // Same path as the original receiver (IPv4 wildcard, no SO_REUSEADDR). Note `port` must stay
            // outside apply: inside it, it would resolve to DatagramSocket.getPort() (-1).
            return DatagramSocket(port).apply {
                broadcast = true
                soTimeout = timeoutMs
            }
        }
    }
}
