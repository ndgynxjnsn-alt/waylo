package dev.waylo.mock

import dev.waylo.protocol.DecodeResult
import dev.waylo.protocol.Handshake
import dev.waylo.protocol.HandshakeAck
import dev.waylo.protocol.HandshakeStatus
import dev.waylo.protocol.Header
import dev.waylo.protocol.Message
import dev.waylo.protocol.MessageCodec
import dev.waylo.protocol.MessageId
import dev.waylo.protocol.Operation
import dev.waylo.protocol.Record
import dev.waylo.protocol.Request
import dev.waylo.protocol.Response
import dev.waylo.protocol.ResponseStatus
import dev.waylo.protocol.SessionId
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetSocketAddress
import java.time.Instant
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class DhlMockServerTest {
    private val server = DhlMockServer(MockConfig(port = 0)).start()
    private val client = DatagramSocket().apply { soTimeout = 2_000 }

    @AfterTest
    fun tearDown() {
        client.close()
        server.close()
    }

    @Test
    fun `handshake yields a session that requests can use`() {
        val ack = exchange(Message(header(1u), Handshake("test", 77u))).body
        assertIs<HandshakeAck>(ack)
        assertEquals(MessageId(1u), ack.answerFor)
        assertEquals(HandshakeStatus.ACCEPTED, ack.status)
        assertEquals(77u, ack.nonceEcho)

        val answer = exchange(
            Message(header(2u), Request(ack.sessionId, Operation.QUOTE, quote("DE"))),
        )
        val response = assertIs<Response>(answer.body)
        assertEquals(MessageId(2u), response.answerFor)
        assertEquals(ResponseStatus.OK, response.status)
        assertTrue(answer.header.flags.simulated)
    }

    @Test
    fun `requests without a handshake are rejected`() {
        val answer = exchange(Message(header(5u), Request(SessionId(1u), Operation.QUOTE, quote("DE"))))
        assertEquals(ResponseStatus.INVALID_SESSION, (answer.body as Response).status)
        assertTrue(answer.header.flags.error)
    }

    private fun header(id: UInt) = Header(MessageId(id), Instant.now())

    private fun quote(country: String) = listOf(
        Record.Weight(1000u), Record.Dimensions(100u, 100u, 100u), Record.Destination(country, "12345"),
    )

    private fun exchange(message: Message): Message {
        val bytes = MessageCodec.encode(message)
        client.send(DatagramPacket(bytes, bytes.size, InetSocketAddress("127.0.0.1", server.port)))
        val packet = DatagramPacket(ByteArray(65_535), 65_535)
        client.receive(packet)
        val result = MessageCodec.decode(packet.data.copyOf(packet.length))
        return assertIs<DecodeResult.Success>(result).message
    }
}
