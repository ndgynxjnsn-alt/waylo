package dev.waylo.gateway.api

import dev.waylo.gateway.domain.Destination
import dev.waylo.gateway.domain.Dimensions
import dev.waylo.gateway.domain.Parcel
import dev.waylo.gateway.domain.QuoteResult
import dev.waylo.gateway.domain.QuoteShipment
import dev.waylo.gateway.domain.TrackParcels
import dev.waylo.gateway.domain.TrackingResult
import java.time.Instant

/*
 * The JSON shapes of the HTTP API.
 *
 * Kotlin notes: properties without "?" are required - if a field is missing or has the wrong type
 * (e.g. a string where a number belongs), Jackson fails and Spring answers 400. "= false" is the
 * value used when the field is absent.
 */

// ---- POST /request/track ---------------------------------------------------------------------

/** `{"trackingNumbers": ["JJD000390007"], "priority": false}` */
data class TrackRequest(
    val trackingNumbers: List<String>,
    val priority: Boolean = false,
) {
    fun toDomain() = TrackParcels(trackingNumbers, priority)
}

/** `{"parcels": [{"trackingNumber": "...", "status": "IN_TRANSIT", "events": [...]}]}` */
data class TrackResponse(val parcels: List<ParcelTrackingDto>) {
    companion object {
        fun from(result: TrackingResult) = TrackResponse(
            result.parcels.map { parcel ->
                ParcelTrackingDto(
                    trackingNumber = parcel.trackingNumber,
                    status = parcel.currentStatus?.name,
                    events = parcel.events.map { TrackingEventDto(it.timestamp, it.status.name, it.location) },
                )
            },
        )
    }
}

data class ParcelTrackingDto(val trackingNumber: String, val status: String?, val events: List<TrackingEventDto>)

data class TrackingEventDto(val timestamp: Instant, val status: String, val location: String)

// ---- POST /request/quote ---------------------------------------------------------------------

/**
 * ```
 * {"parcel": {"weightGrams": 1200, "dimensionsMm": {"length": 300, "width": 200, "height": 100}},
 *  "destination": {"country": "DE", "postalCode": "10115"}, "priority": false}
 * ```
 */
data class QuoteRequest(
    val parcel: ParcelDto,
    val destination: DestinationDto,
    val priority: Boolean = false,
) {
    fun toDomain() = QuoteShipment(
        parcel = Parcel(
            weightGrams = parcel.weightGrams,
            dimensions = Dimensions(parcel.dimensionsMm.length, parcel.dimensionsMm.width, parcel.dimensionsMm.height),
        ),
        destination = Destination(destination.country, destination.postalCode),
        priority = priority,
    )
}

data class ParcelDto(val weightGrams: Int, val dimensionsMm: DimensionsDto)

data class DimensionsDto(val length: Int, val width: Int, val height: Int)

data class DestinationDto(val country: String, val postalCode: String)

/** `{"price": {"amountCents": 739, "currency": "EUR"}}` */
data class QuoteResponse(val price: PriceDto) {
    companion object {
        fun from(result: QuoteResult) = QuoteResponse(PriceDto(result.price.amountCents, result.price.currency))
    }
}

data class PriceDto(val amountCents: Long, val currency: String)
