package dev.waylo.gateway.domain

/* What clients can ask the carrier. Plain immutable data; the HTTP layer maps its DTOs to these. */

sealed interface ParcelQuery {
    val priority: Boolean
}

data class TrackParcels(
    val trackingNumbers: List<String>,
    override val priority: Boolean,
) : ParcelQuery

data class QuoteShipment(
    val parcel: Parcel,
    val destination: Destination,
    override val priority: Boolean,
) : ParcelQuery

data class Parcel(val weightGrams: Int, val dimensions: Dimensions)

data class Dimensions(val lengthMm: Int, val widthMm: Int, val heightMm: Int)

/** [country] is an ISO 3166-1 alpha-2 code such as "DE". */
data class Destination(val country: String, val postalCode: String)
