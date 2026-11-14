package dev.waylo.protocol

import java.time.Instant
import kotlin.random.Random
import kotlin.random.nextUInt

object SampleMessages {
    private val t0: Instant = Instant.parse("2026-11-14T10:15:30.123Z")

    val handshake = Message(
        Header(MessageId(1u), t0),
        Handshake(clientName = "waylo-gateway/0.1.0", nonce = 0xCAFEBABEu),
    )

    val handshakeAck = Message(
        Header(MessageId(9001u), t0, Flags(simulated = true)),
        HandshakeAck(MessageId(1u), HandshakeStatus.ACCEPTED, SessionId(0xDEADBEEFu), 0xCAFEBABEu, "dhl-mock"),
    )

    val trackRequest = Message(
        Header(MessageId(UInt.MAX_VALUE), t0, Flags(retransmission = true, priority = true)),
        Request(
            SessionId(42u), Operation.TRACK,
            listOf(Record.TrackingNumber("JJD000390007"), Record.TrackingNumber("00340434161094042557")),
        ),
    )

    val quoteRequest = Message(
        Header(MessageId(7u), t0),
        Request(
            SessionId(42u), Operation.QUOTE,
            listOf(
                Record.Weight(1_250u),
                Record.Dimensions(300u, 200u, UShort.MAX_VALUE),
                Record.Destination("DE", "10115"),
            ),
        ),
    )

    val trackResponse = Message(
        Header(MessageId(9002u), t0, Flags(simulated = true)),
        Response(
            MessageId(UInt.MAX_VALUE), ResponseStatus.OK,
            listOf(
                Record.TrackingNumber("JJD000390007"),
                Record.TrackingEvent(t0.minusSeconds(7200), ParcelStatus.PICKED_UP, "Leipzig"),
                Record.TrackingEvent(t0, ParcelStatus.IN_TRANSIT, "Köln-Eifeltor"), // non-ASCII UTF-8
                Record.TrackingNumber("00340434161094042557"),
            ),
        ),
    )

    val quoteResponse = Message(
        Header(MessageId(9003u), t0),
        Response(MessageId(7u), ResponseStatus.OK, listOf(Record.Price(1_499u, "EUR"))),
    )

    val errorResponse = Message(
        Header(MessageId(9004u), t0, Flags(error = true, simulated = true)),
        Response(MessageId(8u), ResponseStatus.INVALID_SESSION, emptyList()),
    )

    val all = listOf(handshake, handshakeAck, trackRequest, quoteRequest, trackResponse, quoteResponse, errorResponse)

    /** Randomised messages covering every message/record type, enum value and flag combination. */
    fun random(seed: Int, count: Int): List<Message> {
        val r = Random(seed)
        fun str(max: Int, alphabet: String = "ABCDEFGHJKLMNPQRSTUVWXYZ0123456789-") =
            (1..r.nextInt(1, max + 1)).map { alphabet[r.nextInt(alphabet.length)] }.joinToString("")
        fun fixed(n: Int) = (1..n).map { 'A' + r.nextInt(26) }.joinToString("")
        fun ts() = Instant.ofEpochMilli(r.nextLong(0, 4_102_444_800_000))
        fun flags() = Flags(r.nextBoolean(), r.nextBoolean(), r.nextBoolean(), r.nextBoolean())
        fun record(): Record = when (r.nextInt(6)) {
            0 -> Record.TrackingNumber(str(40))
            1 -> Record.Weight(r.nextUInt())
            2 -> Record.Dimensions(r.nextInt(65536).toUShort(), r.nextInt(65536).toUShort(), r.nextInt(65536).toUShort())
            3 -> Record.Destination(fixed(2), str(10))
            4 -> Record.TrackingEvent(ts(), ParcelStatus.entries.random(r), str(60, "abcäöüßé xyz-/"))
            else -> Record.Price(r.nextUInt(), fixed(3))
        }
        fun records() = List(r.nextInt(0, 20)) { record() }
        return List(count) {
            val header = Header(MessageId(r.nextUInt()), ts(), flags())
            val body = when (r.nextInt(4)) {
                0 -> Handshake(str(120, "abc/.-0123456789ü"), r.nextUInt())
                1 -> HandshakeAck(MessageId(r.nextUInt()), HandshakeStatus.entries.random(r), SessionId(r.nextUInt()), r.nextUInt(), str(30))
                2 -> Request(SessionId(r.nextUInt()), Operation.entries.random(r), records())
                else -> Response(MessageId(r.nextUInt()), ResponseStatus.entries.random(r), records())
            }
            Message(header, body)
        }
    }
}
