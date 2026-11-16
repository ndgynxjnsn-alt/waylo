package dev.waylo.gateway.dhl

import dev.waylo.gateway.domain.Money
import dev.waylo.gateway.domain.ParcelQuery
import dev.waylo.gateway.domain.ParcelResult
import dev.waylo.gateway.domain.ParcelTracking
import dev.waylo.gateway.domain.QuoteResult
import dev.waylo.gateway.domain.QuoteShipment
import dev.waylo.gateway.domain.ShipmentStatus
import dev.waylo.gateway.domain.TrackParcels
import dev.waylo.gateway.domain.TrackingEvent
import dev.waylo.gateway.domain.TrackingResult
import dev.waylo.protocol.Operation
import dev.waylo.protocol.Record
import dev.waylo.protocol.Request
import dev.waylo.protocol.SessionId

/** DHL answered with something that does not fit the request. */
class UnexpectedAnswerException(message: String) : RuntimeException(message)

/**
 * The only place where domain types and wire types meet. Domain queries are turned into protocol
 * DTOs just before sending, and answers are turned back into domain results right after receiving.
 */
object DhlTranslator {

    /**
     * @throws IllegalArgumentException if a value does not fit the binary protocol (e.g. a number
     *   too large for its field, or more than 255 records).
     */
    fun toRequest(session: SessionId, query: ParcelQuery): Request = when (query) {
        is TrackParcels -> Request(
            session,
            Operation.TRACK,
            query.trackingNumbers.map { Record.TrackingNumber(it) },
        )
        is QuoteShipment -> Request(
            session,
            Operation.QUOTE,
            listOf(
                Record.Weight(u32(query.parcel.weightGrams, "weightGrams")),
                Record.Dimensions(
                    u16(query.parcel.dimensions.lengthMm, "length"),
                    u16(query.parcel.dimensions.widthMm, "width"),
                    u16(query.parcel.dimensions.heightMm, "height"),
                ),
                Record.Destination(query.destination.country, query.destination.postalCode),
            ),
        )
    }

    /** @throws UnexpectedAnswerException if the records do not answer [query]. */
    fun toResult(query: ParcelQuery, records: List<Record>): ParcelResult = when (query) {
        is TrackParcels -> tracking(records)
        is QuoteShipment -> quote(records)
    }

    /** Records come as `tracking_number, event, event, ...` groups, one group per parcel. */
    private fun tracking(records: List<Record>): TrackingResult {
        val parcels = mutableListOf<ParcelTracking>()
        for (record in records) {
            when (record) {
                is Record.TrackingNumber -> parcels.add(ParcelTracking(record.value, emptyList()))
                is Record.TrackingEvent -> {
                    val parcel = parcels.removeLastOrNull()
                        ?: throw UnexpectedAnswerException("tracking event before any tracking number")
                    parcels.add(parcel.copy(events = parcel.events + toDomain(record)))
                }
                else -> throw UnexpectedAnswerException("unexpected ${record.recordType} record in tracking answer")
            }
        }
        return TrackingResult(parcels)
    }

    private fun quote(records: List<Record>): QuoteResult {
        val price = records.singleOrNull() as? Record.Price
            ?: throw UnexpectedAnswerException("quote answer must be exactly one price record")
        return QuoteResult(Money(price.amountCents.toLong(), price.currency))
    }

    private fun toDomain(event: Record.TrackingEvent) =
        TrackingEvent(event.timestamp, ShipmentStatus.valueOf(event.status.name), event.location)

    private fun u16(value: Int, name: String): UShort {
        require(value in 0..65535) { "$name must be 0..65535" }
        return value.toUShort()
    }

    private fun u32(value: Int, name: String): UInt {
        require(value >= 0) { "$name must not be negative" }
        return value.toUInt()
    }
}
