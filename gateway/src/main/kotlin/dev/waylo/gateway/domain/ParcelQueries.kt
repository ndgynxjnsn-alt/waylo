package dev.waylo.gateway.domain

import com.fasterxml.jackson.annotation.JsonSubTypes
import com.fasterxml.jackson.annotation.JsonTypeInfo

/*
 * What clients can ask, deserialized by Jackson directly from the JSON request body.
 * The JSON field "type" picks the class: {"type":"track", ...} or {"type":"quote", ...}.
 *
 * Kotlin notes: properties without "?" are required - missing or mistyped JSON fields make
 * Jackson fail, which Spring answers with 400. "= false" is the default if the field is absent.
 */

@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "type")
@JsonSubTypes(
    JsonSubTypes.Type(value = TrackParcels::class, name = "track"),
    JsonSubTypes.Type(value = QuoteShipment::class, name = "quote"),
)
sealed interface ParcelQuery {
    val priority: Boolean
}

data class TrackParcels(
    val trackingNumbers: List<String>,
    override val priority: Boolean = false,
) : ParcelQuery

data class QuoteShipment(
    val parcel: Parcel,
    val destination: Destination,
    override val priority: Boolean = false,
) : ParcelQuery

data class Parcel(val weightGrams: Int, val dimensionsMm: Dimensions)

data class Dimensions(val length: Int, val width: Int, val height: Int)

/** [country] is an ISO 3166-1 alpha-2 code such as "DE". */
data class Destination(val country: String, val postalCode: String)
