package dev.waylo.mock

import dev.waylo.protocol.Operation
import dev.waylo.protocol.Record
import dev.waylo.protocol.ResponseStatus
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DhlBusinessLogicTest {
    private val logic = DhlBusinessLogic(Clock.fixed(Instant.parse("2026-11-14T12:00:00Z"), ZoneOffset.UTC))

    @Test
    fun `tracking is deterministic and echoes every tracking number`() {
        val request = listOf(Record.TrackingNumber("JJD000390007"), Record.TrackingNumber("UNKNOWN-1"))
        val first = logic.handle(Operation.TRACK, request)
        assertEquals(first, logic.handle(Operation.TRACK, request))
        assertEquals(ResponseStatus.OK, first.status)
        assertEquals(request, first.records.filterIsInstance<Record.TrackingNumber>())
        assertEquals(Record.TrackingNumber("UNKNOWN-1"), first.records.last(), "unknown parcels have no events")
    }

    @Test
    fun `quote prices by zone and billable weight`() {
        fun price(country: String, grams: UInt, mm: UShort = 100u) = logic.handle(
            Operation.QUOTE,
            listOf(Record.Weight(grams), Record.Dimensions(mm, mm, mm), Record.Destination(country, "1")),
        ).records.single() as Record.Price

        assertEquals(499u + 120u, price("DE", 1000u).amountCents)
        assertEquals(1299u + 2u * 120u, price("FR", 1001u).amountCents)
        assertEquals(2999u + 120u, price("US", 10u).amountCents)
        // 500mm cube: volumetric 25 kg beats 1 kg actual weight
        assertEquals(499u + 25u * 120u, price("DE", 1000u, 500u).amountCents)
    }

    @Test
    fun `malformed operations are bad requests`() {
        assertEquals(ResponseStatus.BAD_REQUEST, logic.handle(Operation.TRACK, emptyList()).status)
        assertEquals(ResponseStatus.BAD_REQUEST, logic.handle(Operation.QUOTE, listOf(Record.Weight(1u))).status)
        assertTrue(logic.handle(Operation.TRACK, listOf(Record.Weight(1u))).records.isEmpty())
    }
}
