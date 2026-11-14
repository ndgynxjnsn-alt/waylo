package dev.waylo.gateway.api

import com.fasterxml.jackson.annotation.JsonSubTypes
import com.fasterxml.jackson.annotation.JsonTypeInfo
import java.time.Instant

/*
 * Wire format of the HTTP API. Incoming DTOs are deliberately loose (everything nullable): they
 * only describe the JSON shape. Turning them into the domain is the job of [RequestParser].
 */

@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "type")
@JsonSubTypes(
    JsonSubTypes.Type(value = ParcelRequestDto.Track::class, name = "track"),
    JsonSubTypes.Type(value = ParcelRequestDto.Quote::class, name = "quote"),
)
sealed interface ParcelRequestDto {
    val priority: Boolean?

    data class Track(
        val trackingNumbers: List<String?>?,
        override val priority: Boolean? = null,
    ) : ParcelRequestDto

    data class Quote(
        val parcel: ParcelDto?,
        val destination: DestinationDto?,
        override val priority: Boolean? = null,
    ) : ParcelRequestDto
}

data class ParcelDto(val weightGrams: Int?, val dimensionsMm: DimensionsDto?)

data class DimensionsDto(val length: Int?, val width: Int?, val height: Int?)

data class DestinationDto(val country: String?, val postalCode: String?)

// ---- responses --------------------------------------------------------------------------------

@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "type")
@JsonSubTypes(
    JsonSubTypes.Type(value = ParcelResponseDto.Tracking::class, name = "tracking"),
    JsonSubTypes.Type(value = ParcelResponseDto.Quote::class, name = "quote"),
)
sealed interface ParcelResponseDto {
    data class Tracking(val parcels: List<ParcelTrackingDto>) : ParcelResponseDto
    data class Quote(val price: PriceDto) : ParcelResponseDto
}

data class ParcelTrackingDto(val trackingNumber: String, val status: String?, val events: List<TrackingEventDto>)

data class TrackingEventDto(val timestamp: Instant, val status: String, val location: String)

data class PriceDto(val amount: String, val minorUnits: Long, val currency: String)
