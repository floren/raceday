package net.jfloren.raceday

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import net.jfloren.raceday.nmea.SelfTest
import net.jfloren.raceday.nmea.UdpNmeaReceiver
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress

class UdpNmeaReceiverTest {

    @Test
    fun testOpenSocketBindsRequestedPortAndReceives() {
        // Find a free port, then release it for the socket under test
        val port = DatagramSocket(0).use { it.localPort }

        UdpNmeaReceiver.openSocket(port, timeoutMs = 2000).use { socket ->
            assertEquals(port, socket.localPort)

            val sentence = "\$GPVTG,054.7,T,034.4,M,005.5,N,010.2,K,A*25\r\n".toByteArray(Charsets.US_ASCII)
            DatagramSocket().use { sender ->
                sender.send(DatagramPacket(sentence, sentence.size, InetAddress.getLoopbackAddress(), port))
            }

            val buffer = ByteArray(1024)
            val packet = DatagramPacket(buffer, buffer.size)
            socket.receive(packet)
            assertEquals(String(sentence, Charsets.US_ASCII), String(packet.data, 0, packet.length, Charsets.US_ASCII))
        }
    }

    @Test
    fun testOpenSocketRebindsImmediatelyAfterClose() {
        val port = DatagramSocket(0).use { it.localPort }
        UdpNmeaReceiver.openSocket(port).close()
        UdpNmeaReceiver.openSocket(port).use { assertEquals(port, it.localPort) }
    }

    private fun send(port: Int, sentence: String) {
        val bytes = sentence.toByteArray(Charsets.US_ASCII)
        DatagramSocket().use { it.send(DatagramPacket(bytes, bytes.size, InetAddress.getLoopbackAddress(), port)) }
    }

    /** Sends [sentence] repeatedly (the receiver may not be bound yet) until data shows up. */
    private fun receiveVia(receiver: UdpNmeaReceiver, port: Int, sentence: String) = runBlocking {
        withTimeout(5000) {
            val sender = launch(Dispatchers.IO) {
                while (isActive) {
                    send(port, sentence)
                    delay(50)
                }
            }
            try {
                receiver.nmeaData.first { it.sogKnots != null }
            } finally {
                sender.cancel()
            }
        }
    }

    @Test
    fun testReceiverParsesIncomingSentences() {
        val port = DatagramSocket(0).use { it.localPort }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val receiver = UdpNmeaReceiver(null, port, selfTestAddress = InetAddress.getLoopbackAddress())
        try {
            receiver.start(scope)
            val data = receiveVia(receiver, port, "\$GPVTG,054.7,T,034.4,M,005.5,N,010.2,K,A*25\r\n")
            assertEquals(5.5, data.sogKnots!!, 0.001)
            assertEquals(54.7, data.cogTrue!!, 0.001)
            assertTrue(receiver.status.value.packets > 0)
            // The self-test marker came back and wasn't parsed or counted as data
            assertEquals(SelfTest.RECEIVED, receiver.status.value.selfTest)
            assertTrue(receiver.status.value.listening)
            assertEquals("\$GPVTG", receiver.status.value.lastSentenceType)
        } finally {
            receiver.stop()
            scope.cancel()
        }
    }

    @Test
    fun testReceiverWorksAfterQuickRestart() {
        val port = DatagramSocket(0).use { it.localPort }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val first = UdpNmeaReceiver(null, port, selfTestAddress = InetAddress.getLoopbackAddress())
        val second = UdpNmeaReceiver(null, port, selfTestAddress = InetAddress.getLoopbackAddress())
        try {
            first.start(scope)
            receiveVia(first, port, "\$GPVTG,054.7,T,034.4,M,005.5,N,010.2,K,A*25\r\n")
            // Screen off then straight back on
            first.stop()
            second.start(scope)
            val data = receiveVia(second, port, "\$GPVTG,054.7,T,034.4,M,005.5,N,010.2,K,A*25\r\n")
            assertEquals(5.5, data.sogKnots!!, 0.001)
        } finally {
            first.stop()
            second.stop()
            scope.cancel()
        }
    }
}
