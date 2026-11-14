package dev.waylo.gateway.domain

import java.time.Instant
import java.util.Currency

/** What the carrier answered, in domain terms. */
sealed interface ParcelResult

data class TrackingResult(val parcels: List<ParcelTracking>) : ParcelResult

data class ParcelTracking(val trackingNumber: TrackingNumber, val events: List<TrackingEvent>) {
    val currentStatus: ShipmentStatus? get() = events.maxByOrNull { it.timestamp }?.status
}

data class TrackingEvent(val timestamp: Instant, val status: ShipmentStatus, val location: String)

enum class ShipmentStatus { LABEL_CREATED, PICKED_UP, IN_TRANSIT, OUT_FOR_DELIVERY, DELIVERED, EXCEPTION }

data class QuoteResult(val price: Money) : ParcelResult

data class Money(val minorUnits: Long, val currency: Currency)
