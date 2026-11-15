package dev.waylo.gateway.api

import dev.waylo.gateway.domain.FieldError
import dev.waylo.gateway.domain.Parsed
import dev.waylo.gateway.domain.QuoteShipment
import dev.waylo.gateway.domain.TrackParcels
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class RequestParserTest {

    @Test
    fun `parses and normalises a track request`() {
        val parsed = RequestParser.parse(ParcelRequestDto.Track(listOf(" jjd000390007 "), priority = true))
        val query = assertIs<TrackParcels>(assertIs<Parsed.Valid<*>>(parsed).value)
        assertEquals(listOf("JJD000390007"), query.trackingNumbers.map { it.value })
        assertEquals(true, query.priority)
    }

    @Test
    fun `reports errors per list element`() {
        val parsed = RequestParser.parse(ParcelRequestDto.Track(listOf("JJD000390007", null, "x")))
        assertEquals(
            Parsed.Invalid(
                listOf(
                    FieldError("trackingNumbers[1]", "is required"),
                    FieldError("trackingNumbers[2]", "must be 8-35 letters or digits"),
                ),
            ),
            parsed,
        )
    }

    @Test
    fun `rejects empty and oversized track requests`() {
        assertEquals(
            Parsed.Invalid("trackingNumbers", "must not be empty"),
            RequestParser.parse(ParcelRequestDto.Track(emptyList())),
        )
        val tooMany = (1..21).map { "JJD%09d".format(it) }
        assertEquals(
            Parsed.Invalid("trackingNumbers", "at most 20 parcels"),
            RequestParser.parse(ParcelRequestDto.Track(tooMany)),
        )
    }

    @Test
    fun `parses a quote request`() {
        val parsed = RequestParser.parse(
            ParcelRequestDto.Quote(ParcelDto(500, DimensionsDto(10, 20, 30)), DestinationDto("de", "10115")),
        )
        val quote = assertIs<QuoteShipment>(assertIs<Parsed.Valid<*>>(parsed).value)
        assertEquals("DE", quote.destination.country.value)
        assertEquals(30, quote.parcel.dimensions.heightMm)
    }

    @Test
    fun `reports missing nested objects`() {
        assertEquals(
            Parsed.Invalid(listOf(FieldError("parcel", "is required"), FieldError("destination", "is required"))),
            RequestParser.parse(ParcelRequestDto.Quote(null, null)),
        )
    }
}
