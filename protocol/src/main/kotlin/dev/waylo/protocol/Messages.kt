package dev.waylo.protocol

import java.time.Instant

/*
 * Immutable model of the WPP v1 wire protocol (see src/main/kaitai/waylo_message.ksy).
 *
 * Instances can only be constructed in a state that is encodable: every size limit of the
 * wire format is enforced on construction, so [MessageCodec.encode] cannot fail.
 */

@JvmInline
value class MessageId(val value: UInt) {
    override fun toString() = value.toString()
}

@JvmInline
value class SessionId(val value: UInt) {
    override fun toString() = value.toString()
}

data class Flags(
    val retransmission: Boolean = false,
    val priority: Boolean = false,
    val error: Boolean = false,
    val simulated: Boolean = false,
) {
    companion object {
        val NONE = Flags()
    }
}

data class Header(
    val messageId: MessageId,
    val timestamp: Instant,
    val flags: Flags = Flags.NONE,
) {
    init {
        require(timestamp.toEpochMilli() >= 0) { "timestamp must not be before the epoch" }
    }
}

data class Message(val header: Header, val body: Body) {
    val type: MessageType get() = body.type
}

/** Wire code enums. `code` is the byte on the wire. */
enum class MessageType(val code: Int) {
    HANDSHAKE(0x01), HANDSHAKE_ACK(0x02), REQUEST(0x10), RESPONSE(0x11);

    companion object {
        fun of(code: Int) = entries.firstOrNull { it.code == code }
    }
}

enum class HandshakeStatus(val code: Int) {
    ACCEPTED(0), REJECTED(1);

    companion object {
        fun of(code: Int) = entries.firstOrNull { it.code == code }
    }
}

enum class Operation(val code: Int) {
    TRACK(1), QUOTE(2);

    companion object {
        fun of(code: Int) = entries.firstOrNull { it.code == code }
    }
}

enum class ResponseStatus(val code: Int) {
    OK(0), INVALID_SESSION(1), BAD_REQUEST(2), INTERNAL_ERROR(3);

    companion object {
        fun of(code: Int) = entries.firstOrNull { it.code == code }
    }
}

enum class ParcelStatus(val code: Int) {
    LABEL_CREATED(1), PICKED_UP(2), IN_TRANSIT(3), OUT_FOR_DELIVERY(4), DELIVERED(5), EXCEPTION(6);

    companion object {
        fun of(code: Int) = entries.firstOrNull { it.code == code }
    }
}

sealed interface Body {
    val type: MessageType
}

/** A body that answers an earlier message; the correlation key for in-flight requests. */
sealed interface Answer : Body {
    val answerFor: MessageId
}

data class Handshake(val clientName: String, val nonce: UInt) : Body {
    override val type get() = MessageType.HANDSHAKE

    init {
        requireUtf8Size(clientName, Limits.MAX_NAME_BYTES, "clientName")
    }
}

data class HandshakeAck(
    override val answerFor: MessageId,
    val status: HandshakeStatus,
    val sessionId: SessionId,
    val nonceEcho: UInt,
    val serverName: String,
) : Answer {
    override val type get() = MessageType.HANDSHAKE_ACK

    init {
        requireUtf8Size(serverName, Limits.MAX_NAME_BYTES, "serverName")
    }
}

data class Request(
    val sessionId: SessionId,
    val operation: Operation,
    val records: List<Record>,
) : Body {
    override val type get() = MessageType.REQUEST

    init {
        require(records.size <= Limits.MAX_RECORDS) { "at most ${Limits.MAX_RECORDS} records" }
    }
}

data class Response(
    override val answerFor: MessageId,
    val status: ResponseStatus,
    val records: List<Record>,
) : Answer {
    override val type get() = MessageType.RESPONSE

    init {
        require(records.size <= Limits.MAX_RECORDS) { "at most ${Limits.MAX_RECORDS} records" }
    }
}

/** Type-length-value records: the variable-length array elements of requests and responses. */
sealed interface Record {
    val recordType: RecordType

    data class TrackingNumber(val value: String) : Record {
        override val recordType get() = RecordType.TRACKING_NUMBER

        init {
            requireAscii(value, "tracking number")
            require(value.length in 1..Limits.MAX_RECORD_BODY) { "tracking number length" }
        }
    }

    data class Weight(val grams: UInt) : Record {
        override val recordType get() = RecordType.WEIGHT
    }

    data class Dimensions(val lengthMm: UShort, val widthMm: UShort, val heightMm: UShort) : Record {
        override val recordType get() = RecordType.DIMENSIONS
    }

    data class Destination(val countryCode: String, val postalCode: String) : Record {
        override val recordType get() = RecordType.DESTINATION

        init {
            requireAscii(countryCode, "country code")
            requireAscii(postalCode, "postal code")
            require(countryCode.length == 2) { "country code must have 2 characters" }
            require(postalCode.length <= Limits.MAX_RECORD_BODY - 2) { "postal code too long" }
        }
    }

    data class TrackingEvent(val timestamp: Instant, val status: ParcelStatus, val location: String) : Record {
        override val recordType get() = RecordType.TRACKING_EVENT

        init {
            require(timestamp.toEpochMilli() >= 0) { "timestamp must not be before the epoch" }
            requireUtf8Size(location, Limits.MAX_RECORD_BODY - 9, "location")
        }
    }

    data class Price(val amountCents: UInt, val currency: String) : Record {
        override val recordType get() = RecordType.PRICE

        init {
            requireAscii(currency, "currency")
            require(currency.length == 3) { "currency must have 3 characters" }
        }
    }
}

enum class RecordType(val code: Int) {
    TRACKING_NUMBER(0x01), WEIGHT(0x02), DIMENSIONS(0x03), DESTINATION(0x04), TRACKING_EVENT(0x10), PRICE(0x11);

    companion object {
        fun of(code: Int) = entries.firstOrNull { it.code == code }
    }
}

object Limits {
    const val HEADER_SIZE = 19
    const val CHECKSUM_SIZE = 4
    const val MIN_MESSAGE_SIZE = HEADER_SIZE + CHECKSUM_SIZE
    const val MAX_MESSAGE_SIZE = 65_507 // largest UDP payload over IPv4
    const val MAX_NAME_BYTES = 255
    const val MAX_RECORDS = 255
    const val MAX_RECORD_BODY = 255
}

private fun requireUtf8Size(value: String, max: Int, name: String) =
    require(value.encodeToByteArray().size <= max) { "$name exceeds $max UTF-8 bytes" }

private fun requireAscii(value: String, name: String) =
    require(value.all { it.code in 0x20..0x7e }) { "$name must be printable ASCII" }
