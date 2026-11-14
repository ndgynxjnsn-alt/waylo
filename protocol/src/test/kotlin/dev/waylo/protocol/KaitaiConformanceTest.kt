package dev.waylo.protocol

import dev.waylo.protocol.kaitai.WayloMessage
import io.kaitai.struct.ByteBufferKaitaiStream
import io.kaitai.struct.KaitaiStream
import java.time.Instant
import java.util.zip.CRC32
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * Proves that `waylo_message.ksy` describes exactly what [MessageCodec] puts on the wire:
 * every encoded datagram is parsed by the parser generated from the .ksy (by the Kaitai Struct
 * compiler, see `generateKaitaiParser`), mapped back into the Kotlin model and compared.
 */
class KaitaiConformanceTest {

    @Test
    fun `ksy parser reads every encoded message back to the original`() {
        (SampleMessages.all + SampleMessages.random(seed = 2, count = 500)).forEach { message ->
            val bytes = MessageCodec.encode(message)
            val parsed = parse(bytes)

            assertEquals(message, parsed.toModel(), "ksy view differs for $message")
            assertEquals(bytes.size, parsed.header().messageLength())
            assertEquals(crc32(bytes, bytes.size - 4), parsed.checksum(), "checksum")
        }
    }

    @Test
    fun `ksy validations reject what the codec rejects`() {
        val valid = MessageCodec.encode(SampleMessages.trackRequest)
        val cases = mapOf(
            "bad magic" to valid.copyOf().also { it[0] = 0 },
            "bad version" to valid.copyOf().also { it[2] = 2 },
            "unknown message type" to valid.copyOf().also { it[3] = 0x7F },
            "length too large" to valid.copyOf().also { it[5] = (it[5] + 1).toByte() },
            "length too small" to valid.copyOf().also { it[4] = 0; it[5] = 22 },
            "reserved flags" to valid.copyOf().also { it[18] = (it[18].toInt() or 0x01).toByte() },
            "truncated" to valid.copyOf(valid.size - 1),
        )
        cases.forEach { (name, bytes) ->
            assertFailsWith<KaitaiStream.KaitaiStructError>(name) { parse(bytes.withFixedChecksum()) }
            assert(MessageCodec.decode(bytes.withFixedChecksum()) is DecodeResult.Failure) { name }
        }
    }

    private fun parse(bytes: ByteArray) = WayloMessage(ByteBufferKaitaiStream(bytes))

    private fun crc32(bytes: ByteArray, length: Int) = CRC32().apply { update(bytes, 0, length) }.value

    // ---- Kaitai view -> Kotlin model --------------------------------------------------------

    private fun WayloMessage.toModel(): Message {
        val h = header()
        val flags = h.flags().let { Flags(it.retransmission(), it.priority(), it.error(), it.simulated()) }
        val header = Header(MessageId(h.messageId().toUInt()), Instant.ofEpochMilli(h.timestampMs()), flags)
        val body: Body = when (val b = body()) {
            is WayloMessage.Handshake -> Handshake(b.clientName(), b.nonce().toUInt())
            is WayloMessage.HandshakeAck -> HandshakeAck(
                MessageId(b.answerForId().toUInt()),
                HandshakeStatus.of(b.status().id().toInt())!!,
                SessionId(b.sessionId().toUInt()),
                b.nonceEcho().toUInt(),
                b.serverName(),
            )
            is WayloMessage.Request -> Request(
                SessionId(b.sessionId().toUInt()),
                Operation.of(b.operation().id().toInt())!!,
                b.records().map { it.toModel() },
            ).also { assertEquals(b.numRecords(), b.records().size) }
            is WayloMessage.Response -> Response(
                MessageId(b.answerForId().toUInt()),
                ResponseStatus.of(b.status().id().toInt())!!,
                b.records().map { it.toModel() },
            )
            else -> error("unexpected body $b")
        }
        assertEquals(MessageType.of(h.messageType().id().toInt()), body.type)
        return Message(header, body)
    }

    private fun WayloMessage.Record.toModel(): Record = when (val b = body()) {
        is WayloMessage.RecTrackingNumber -> Record.TrackingNumber(b.value())
        is WayloMessage.RecWeight -> Record.Weight(b.grams().toUInt())
        is WayloMessage.RecDimensions -> Record.Dimensions(b.lengthMm().toUShort(), b.widthMm().toUShort(), b.heightMm().toUShort())
        is WayloMessage.RecDestination -> Record.Destination(b.countryCode(), b.postalCode())
        is WayloMessage.RecTrackingEvent -> Record.TrackingEvent(
            Instant.ofEpochMilli(b.timestampMs()), ParcelStatus.of(b.status().id().toInt())!!, b.location(),
        )
        is WayloMessage.RecPrice -> Record.Price(b.amountCents().toUInt(), b.currency())
        else -> error("unexpected record body $b")
    }.also { assertEquals(RecordType.of(recordType().id().toInt()), it.recordType) }
}
