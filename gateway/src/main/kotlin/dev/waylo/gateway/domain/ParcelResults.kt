package dev.waylo.gateway.domain

import com.fasterxml.jackson.annotation.JsonSubTypes
import com.fasterxml.jackson.annotation.JsonTypeInfo
import java.time.Instant

/* What the carrier answered. Serialized by Jackson directly as the JSON response body. */

@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "type")
@JsonSubTypes(
    JsonSubTypes.Type(value = TrackingResult::class, name = "tracking"),
    JsonSubTypes.Type(value = QuoteResult::class, name = "quote"),
)
sealed interface ParcelResult

data class TrackingResult(val parcels: List<ParcelTracking>) : ParcelResult

data class ParcelTracking(val trackingNumber: String, val events: List<TrackingEvent>) {
    /** Status of the latest event; null for unknown parcels. Also part of the JSON. */
    val status: ShipmentStatus?
        get() = events.maxByOrNull { it.timestamp }?.status
}

data class TrackingEvent(val timestamp: Instant, val status: ShipmentStatus, val location: String)

enum class ShipmentStatus { LABEL_CREATED, PICKED_UP, IN_TRANSIT, OUT_FOR_DELIVERY, DELIVERED, EXCEPTION }

data class QuoteResult(val price: Money) : ParcelResult

data class Money(val amountCents: Long, val currency: String)
