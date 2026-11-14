package dev.waylo.protocol

import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.nio.BufferUnderflowException
import java.nio.ByteBuffer
import java.time.Instant
import java.util.zip.CRC32

/** Why a datagram could not be decoded. */
sealed interface DecodeError {
    data class TooShort(val size: Int) : DecodeError
    data object BadMagic : DecodeError
    data class UnsupportedVersion(val version: Int) : DecodeError
    data class UnknownMessageType(val code: Int) : DecodeError
    data class LengthMismatch(val declared: Int, val actual: Int) : DecodeError
    data class ChecksumMismatch(val declared: UInt, val actual: UInt) : DecodeError
    data object ReservedFlagsSet : DecodeError
    data class Malformed(val reason: String) : DecodeError
}

sealed interface DecodeResult {
    data class Success(val message: Message) : DecodeResult
    data class Failure(val error: DecodeError) : DecodeResult
}

/**
 * Encodes/decodes [Message]s to/from WPP v1 datagrams.
 *
 * Decoding parses the untrusted bytes into the immutable model or returns a [DecodeError];
 * it never throws.
 */
object MessageCodec {
    private val MAGIC = byteArrayOf('W'.code.toByte(), 'Y'.code.toByte())
    private const val VERSION = 1

    fun encode(message: Message): ByteArray {
        val body = encodeBody(message.body)
        val length = Limits.HEADER_SIZE + body.size + Limits.CHECKSUM_SIZE
        check(length <= Limits.MAX_MESSAGE_SIZE) { "message too large: $length bytes" }

        val buffer = ByteBuffer.allocate(length)
        buffer.put(MAGIC)
        buffer.put(VERSION.toByte())
        buffer.put(message.type.code.toByte())
        buffer.putShort(length.toShort())
        buffer.putInt(message.header.messageId.value.toInt())
        buffer.putLong(message.header.timestamp.toEpochMilli())
        buffer.put(encodeFlags(message.header.flags))
        buffer.put(body)
        buffer.putInt(crc32(buffer.array(), length - Limits.CHECKSUM_SIZE).toInt())
        return buffer.array()
    }

    fun decode(datagram: ByteArray): DecodeResult = try {
        DecodeResult.Success(decodeOrThrow(datagram))
    } catch (e: DecodeException) {
        DecodeResult.Failure(e.error)
    } catch (_: BufferUnderflowException) {
        DecodeResult.Failure(DecodeError.Malformed("truncated body"))
    } catch (e: IllegalArgumentException) {
        // invariant of the immutable model violated by the payload
        DecodeResult.Failure(DecodeError.Malformed(e.message ?: "invalid value"))
    }

    private fun decodeOrThrow(datagram: ByteArray): Message {
        if (datagram.size < Limits.MIN_MESSAGE_SIZE) fail(DecodeError.TooShort(datagram.size))
        val buf = ByteBuffer.wrap(datagram)

        if (buf.get() != MAGIC[0] || buf.get() != MAGIC[1]) fail(DecodeError.BadMagic)
        val version = buf.u1()
        if (version != VERSION) fail(DecodeError.UnsupportedVersion(version))
        val typeCode = buf.u1()
        val type = MessageType.of(typeCode) ?: fail(DecodeError.UnknownMessageType(typeCode))
        val length = buf.u2()
        if (length != datagram.size) fail(DecodeError.LengthMismatch(length, datagram.size))

        val declaredCrc = ByteBuffer.wrap(datagram, length - Limits.CHECKSUM_SIZE, 4).int.toUInt()
        val actualCrc = crc32(datagram, length - Limits.CHECKSUM_SIZE)
        if (declaredCrc != actualCrc) fail(DecodeError.ChecksumMismatch(declaredCrc, actualCrc))

        val messageId = MessageId(buf.int.toUInt())
        val timestamp = buf.long.also { if (it < 0) malformed("negative timestamp") }
        val flags = decodeFlags(buf.u1())

        val bodyBuf = buf.slice(buf.position(), length - Limits.HEADER_SIZE - Limits.CHECKSUM_SIZE)
        val body = decodeBody(type, bodyBuf)
        if (bodyBuf.hasRemaining()) malformed("${bodyBuf.remaining()} trailing body bytes")

        return Message(Header(messageId, Instant.ofEpochMilli(timestamp), flags), body)
    }

    // ---- flags -------------------------------------------------------------------------------

    private fun encodeFlags(flags: Flags): Byte {
        var bits = 0
        if (flags.retransmission) bits = bits or 0x80
        if (flags.priority) bits = bits or 0x40
        if (flags.error) bits = bits or 0x20
        if (flags.simulated) bits = bits or 0x10
        return bits.toByte()
    }

    private fun decodeFlags(bits: Int): Flags {
        if (bits and 0x0F != 0) fail(DecodeError.ReservedFlagsSet)
        return Flags(
            retransmission = bits and 0x80 != 0,
            priority = bits and 0x40 != 0,
            error = bits and 0x20 != 0,
            simulated = bits and 0x10 != 0,
        )
    }

    // ---- bodies ------------------------------------------------------------------------------

    private fun encodeBody(body: Body): ByteArray = bytes {
        when (body) {
            is Handshake -> {
                writeShortString(body.clientName)
                writeInt(body.nonce.toInt())
            }
            is HandshakeAck -> {
                writeInt(body.answerFor.value.toInt())
                writeByte(body.status.code)
                writeInt(body.sessionId.value.toInt())
                writeInt(body.nonceEcho.toInt())
                writeShortString(body.serverName)
            }
            is Request -> {
                writeInt(body.sessionId.value.toInt())
                writeByte(body.operation.code)
                writeRecords(body.records)
            }
            is Response -> {
                writeInt(body.answerFor.value.toInt())
                writeByte(body.status.code)
                writeRecords(body.records)
            }
        }
    }

    private fun decodeBody(type: MessageType, buf: ByteBuffer): Body = when (type) {
        MessageType.HANDSHAKE -> Handshake(clientName = buf.shortString(), nonce = buf.int.toUInt())
        MessageType.HANDSHAKE_ACK -> HandshakeAck(
            answerFor = MessageId(buf.int.toUInt()),
            status = buf.u1().let { HandshakeStatus.of(it) ?: malformed("unknown handshake status $it") },
            sessionId = SessionId(buf.int.toUInt()),
            nonceEcho = buf.int.toUInt(),
            serverName = buf.shortString(),
        )
        MessageType.REQUEST -> Request(
            sessionId = SessionId(buf.int.toUInt()),
            operation = buf.u1().let { Operation.of(it) ?: malformed("unknown operation $it") },
            records = buf.records(),
        )
        MessageType.RESPONSE -> Response(
            answerFor = MessageId(buf.int.toUInt()),
            status = buf.u1().let { ResponseStatus.of(it) ?: malformed("unknown response status $it") },
            records = buf.records(),
        )
    }

    // ---- records -----------------------------------------------------------------------------

    private fun DataOutputStream.writeRecords(records: List<Record>) {
        writeByte(records.size)
        for (record in records) {
            val body = encodeRecordBody(record)
            check(body.size <= Limits.MAX_RECORD_BODY) { "record body too large: ${body.size}" }
            writeByte(record.recordType.code)
            writeByte(body.size)
            write(body)
        }
    }

    private fun encodeRecordBody(record: Record): ByteArray = bytes {
        when (record) {
            is Record.TrackingNumber -> write(record.value.toByteArray(Charsets.US_ASCII))
            is Record.Weight -> writeInt(record.grams.toInt())
            is Record.Dimensions -> {
                writeShort(record.lengthMm.toInt())
                writeShort(record.widthMm.toInt())
                writeShort(record.heightMm.toInt())
            }
            is Record.Destination -> {
                write(record.countryCode.toByteArray(Charsets.US_ASCII))
                write(record.postalCode.toByteArray(Charsets.US_ASCII))
            }
            is Record.TrackingEvent -> {
                writeLong(record.timestamp.toEpochMilli())
                writeByte(record.status.code)
                write(record.location.encodeToByteArray())
            }
            is Record.Price -> {
                writeInt(record.amountCents.toInt())
                write(record.currency.toByteArray(Charsets.US_ASCII))
            }
        }
    }

    private fun ByteBuffer.records(): List<Record> = List(u1()) {
        val typeCode = u1()
        val type = RecordType.of(typeCode) ?: malformed("unknown record type $typeCode")
        val size = u1()
        if (size > remaining()) malformed("$type record of $size bytes exceeds body")
        val body = slice(position(), size).also { position(position() + size) }
        decodeRecord(type, body).also { if (body.hasRemaining()) malformed("trailing bytes in $type record") }
    }

    private fun decodeRecord(type: RecordType, buf: ByteBuffer): Record = when (type) {
        RecordType.TRACKING_NUMBER -> Record.TrackingNumber(buf.remainingString(Charsets.US_ASCII))
        RecordType.WEIGHT -> Record.Weight(buf.int.toUInt())
        RecordType.DIMENSIONS -> Record.Dimensions(buf.u2().toUShort(), buf.u2().toUShort(), buf.u2().toUShort())
        RecordType.DESTINATION -> Record.Destination(
            countryCode = buf.fixedString(2, Charsets.US_ASCII),
            postalCode = buf.remainingString(Charsets.US_ASCII),
        )
        RecordType.TRACKING_EVENT -> Record.TrackingEvent(
            timestamp = buf.long.let { if (it < 0) malformed("negative timestamp") else Instant.ofEpochMilli(it) },
            status = buf.u1().let { ParcelStatus.of(it) ?: malformed("unknown parcel status $it") },
            location = buf.remainingString(Charsets.UTF_8),
        )
        RecordType.PRICE -> Record.Price(buf.int.toUInt(), buf.fixedString(3, Charsets.US_ASCII))
    }

    // ---- primitives --------------------------------------------------------------------------

    private class DecodeException(val error: DecodeError) : RuntimeException(null, null, false, false)

    private fun fail(error: DecodeError): Nothing = throw DecodeException(error)
    private fun malformed(reason: String): Nothing = fail(DecodeError.Malformed(reason))

    private inline fun bytes(block: DataOutputStream.() -> Unit): ByteArray =
        ByteArrayOutputStream().also { DataOutputStream(it).block() }.toByteArray()

    private fun DataOutputStream.writeShortString(value: String) {
        val bytes = value.encodeToByteArray()
        writeByte(bytes.size)
        write(bytes)
    }

    private fun ByteBuffer.u1() = get().toInt() and 0xFF
    private fun ByteBuffer.u2() = short.toInt() and 0xFFFF

    private fun ByteBuffer.fixedString(size: Int, charset: java.nio.charset.Charset): String =
        ByteArray(size).also { get(it) }.toString(charset)

    private fun ByteBuffer.remainingString(charset: java.nio.charset.Charset) = fixedString(remaining(), charset)
    private fun ByteBuffer.shortString() = fixedString(u1(), Charsets.UTF_8)

    internal fun crc32(bytes: ByteArray, length: Int): UInt =
        CRC32().apply { update(bytes, 0, length) }.value.toUInt()
}
