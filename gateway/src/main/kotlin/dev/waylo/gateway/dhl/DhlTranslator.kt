package dev.waylo.gateway.dhl

import dev.waylo.gateway.domain.Money
import dev.waylo.gateway.domain.ParcelQuery
import dev.waylo.gateway.domain.ParcelResult
import dev.waylo.gateway.domain.ParcelTracking
import dev.waylo.gateway.domain.Parsed
import dev.waylo.gateway.domain.QuoteResult
import dev.waylo.gateway.domain.QuoteShipment
import dev.waylo.gateway.domain.ShipmentStatus
import dev.waylo.gateway.domain.TrackParcels
import dev.waylo.gateway.domain.TrackingEvent
import dev.waylo.gateway.domain.TrackingNumber
import dev.waylo.gateway.domain.TrackingResult
import dev.waylo.protocol.Operation
import dev.waylo.protocol.ParcelStatus
import dev.waylo.protocol.Record
import dev.waylo.protocol.Request
import dev.waylo.protocol.SessionId
import java.util.Currency

/**
 * The only place where domain types and wire types meet. Domain queries are turned into protocol
 * DTOs just before sending, and answers are parsed back into domain results right after receiving.
 */
object DhlTranslator {

    fun toRequest(session: SessionId, query: ParcelQuery): Request = when (query) {
        is TrackParcels -> Request(
            session, Operation.TRACK,
            query.trackingNumbers.map { Record.TrackingNumber(it.value) },
        )
        is QuoteShipment -> Request(
            session, Operation.QUOTE,
            listOf(
                Record.Weight(query.parcel.weight.grams.toUInt()),
                with(query.parcel.dimensions) { Record.Dimensions(lengthMm.toUShort(), widthMm.toUShort(), heightMm.toUShort()) },
                Record.Destination(query.destination.country.value, query.destination.postalCode.value),
            ),
        )
    }

    /** Parses the records of a successful answer into the result for [query]. */
    fun toResult(query: ParcelQuery, records: List<Record>): Parsed<ParcelResult> = when (query) {
        is TrackParcels -> tracking(query, records)
        is QuoteShipment -> quote(records)
    }

    /** Records come as `tracking_number, event*` groups, one group per requested parcel. */
    private fun tracking(query: TrackParcels, records: List<Record>): Parsed<ParcelResult> {
        val groups = mutableListOf<Pair<String, MutableList<TrackingEvent>>>()
        for (record in records) {
            when (record) {
                is Record.TrackingNumber -> groups += record.value to mutableListOf()
                is Record.TrackingEvent -> groups.lastOrNull()?.second?.add(record.toDomain())
                    ?: return Parsed.invalid("tracking event before any tracking number")
                else -> return Parsed.invalid("unexpected ${record.recordType} record in tracking answer")
            }
        }
        val requested = query.trackingNumbers.associateBy { it.value }
        if (groups.map { it.first }.toSet() != requested.keys || groups.size != requested.size) {
            return Parsed.invalid("answer does not cover exactly the requested parcels")
        }
        val parcels = groups.map { (number, events) -> ParcelTracking(requested.getValue(number), events.toList()) }
        // keep the order of the request
        val order = query.trackingNumbers.withIndex().associate { (i, n) -> n to i }
        return Parsed.Valid(TrackingResult(parcels.sortedBy { order.getValue(it.trackingNumber) }))
    }

    private fun quote(records: List<Record>): Parsed<ParcelResult> {
        val price = records.singleOrNull() as? Record.Price
            ?: return Parsed.invalid("quote answer must consist of exactly one price record")
        val currency = runCatching { Currency.getInstance(price.currency) }.getOrNull()
            ?: return Parsed.invalid("unknown currency ${price.currency}")
        return Parsed.Valid(QuoteResult(Money(price.amountCents.toLong(), currency)))
    }

    private fun Record.TrackingEvent.toDomain() = TrackingEvent(timestamp, status.toDomain(), location)

    private fun ParcelStatus.toDomain() = when (this) {
        ParcelStatus.LABEL_CREATED -> ShipmentStatus.LABEL_CREATED
        ParcelStatus.PICKED_UP -> ShipmentStatus.PICKED_UP
        ParcelStatus.IN_TRANSIT -> ShipmentStatus.IN_TRANSIT
        ParcelStatus.OUT_FOR_DELIVERY -> ShipmentStatus.OUT_FOR_DELIVERY
        ParcelStatus.DELIVERED -> ShipmentStatus.DELIVERED
        ParcelStatus.EXCEPTION -> ShipmentStatus.EXCEPTION
    }

}
