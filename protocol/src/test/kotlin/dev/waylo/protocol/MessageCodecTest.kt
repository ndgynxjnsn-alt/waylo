package dev.waylo.protocol

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class MessageCodecTest {

    @Test
    fun `every sample message survives a round trip`() {
        (SampleMessages.all + SampleMessages.random(seed = 1, count = 500)).forEach { message ->
            val decoded = MessageCodec.decode(MessageCodec.encode(message))
            assertEquals(DecodeResult.Success(message), decoded)
        }
    }

    @Test
    fun `header layout is as documented`() {
        val bytes = MessageCodec.encode(SampleMessages.trackRequest)
        assertEquals('W'.code.toByte(), bytes[0])
        assertEquals('Y'.code.toByte(), bytes[1])
        assertEquals(1, bytes[2].toInt())                       // version
        assertEquals(0x10, bytes[3].toInt())                    // request
        assertEquals(bytes.size, ((bytes[4].toInt() and 0xFF) shl 8) or (bytes[5].toInt() and 0xFF))
        assertEquals(listOf(-1, -1, -1, -1), bytes.slice(6..9).map { it.toInt() }) // message id u4 max
        assertEquals(0b1100_0000.toByte(), bytes[18])           // retransmission + priority
    }

    @Test
    fun `rejects datagrams that are too short`() {
        assertEquals(DecodeResult.Failure(DecodeError.TooShort(5)), MessageCodec.decode(ByteArray(5)))
    }

    @Test
    fun `rejects bad magic`() {
        val bytes = MessageCodec.encode(SampleMessages.handshake).also { it[0] = 'X'.code.toByte() }
        assertEquals(DecodeResult.Failure(DecodeError.BadMagic), MessageCodec.decode(bytes))
    }

    @Test
    fun `rejects truncated datagrams via the length field`() {
        val bytes = MessageCodec.encode(SampleMessages.trackResponse)
        val truncated = bytes.copyOf(bytes.size - 1)
        assertEquals(
            DecodeResult.Failure(DecodeError.LengthMismatch(bytes.size, bytes.size - 1)),
            MessageCodec.decode(truncated),
        )
    }

    @Test
    fun `rejects corrupted payload via checksum`() {
        val bytes = MessageCodec.encode(SampleMessages.trackResponse).also { it[30] = (it[30] + 1).toByte() }
        assertIs<DecodeError.ChecksumMismatch>((MessageCodec.decode(bytes) as DecodeResult.Failure).error)
    }

    @Test
    fun `rejects reserved flag bits`() {
        val bytes = MessageCodec.encode(SampleMessages.handshake).also { it[18] = 0x01 }.withFixedChecksum()
        assertEquals(DecodeResult.Failure(DecodeError.ReservedFlagsSet), MessageCodec.decode(bytes))
    }

    @Test
    fun `rejects unknown message type`() {
        val bytes = MessageCodec.encode(SampleMessages.handshake).also { it[3] = 0x7F }.withFixedChecksum()
        assertEquals(DecodeResult.Failure(DecodeError.UnknownMessageType(0x7F)), MessageCodec.decode(bytes))
    }

    @Test
    fun `rejects record lengths that run past the body`() {
        val bytes = MessageCodec.encode(SampleMessages.quoteResponse)
        // body: answer_for(4) status(1) num_records(1) | record_type(1) body_len(1) ...
        bytes[19 + 7] = 100
        assertIs<DecodeError.Malformed>((MessageCodec.decode(bytes.withFixedChecksum()) as DecodeResult.Failure).error)
    }

    @Test
    fun `garbage never throws`() {
        val r = kotlin.random.Random(7)
        repeat(10_000) { MessageCodec.decode(r.nextBytes(r.nextInt(0, 128))) }
        // mutate valid datagrams byte-by-byte
        SampleMessages.all.map(MessageCodec::encode).forEach { valid ->
            for (i in valid.indices) {
                MessageCodec.decode(valid.copyOf().also { it[i] = r.nextInt().toByte() }.withFixedChecksum())
            }
        }
    }
}

/** Recomputes the trailing CRC-32 so a deliberately mutated datagram reaches the later checks. */
fun ByteArray.withFixedChecksum(): ByteArray {
    val crc = MessageCodec.crc32(this, size - 4).toInt()
    for (i in 0..3) this[size - 4 + i] = (crc ushr (24 - 8 * i)).toByte()
    return this
}
