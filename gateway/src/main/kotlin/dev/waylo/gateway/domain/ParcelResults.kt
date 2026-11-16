package dev.waylo.gateway.domain

import java.time.Instant

/* What the carrier answered. */

sealed interface ParcelResult

data class TrackingResult(val parcels: List<ParcelTracking>) : ParcelResult

data class ParcelTracking(val trackingNumber: String, val events: List<TrackingEvent>) {
    /** Status of the latest event; null for unknown parcels. */
    val currentStatus: ShipmentStatus?
        get() = events.maxByOrNull { it.timestamp }?.status
}

data class TrackingEvent(val timestamp: Instant, val status: ShipmentStatus, val location: String)

enum class ShipmentStatus { LABEL_CREATED, PICKED_UP, IN_TRANSIT, OUT_FOR_DELIVERY, DELIVERED, EXCEPTION }

data class QuoteResult(val price: Money) : ParcelResult

data class Money(val amountCents: Long, val currency: String)
