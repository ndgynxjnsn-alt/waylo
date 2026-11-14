package dev.waylo.gateway.api

import dev.waylo.gateway.domain.CountryCode
import dev.waylo.gateway.domain.Destination
import dev.waylo.gateway.domain.Dimensions
import dev.waylo.gateway.domain.Parcel
import dev.waylo.gateway.domain.ParcelQuery
import dev.waylo.gateway.domain.Parsed
import dev.waylo.gateway.domain.PostalCode
import dev.waylo.gateway.domain.QuoteShipment
import dev.waylo.gateway.domain.TrackParcels
import dev.waylo.gateway.domain.TrackingNumber
import dev.waylo.gateway.domain.Weight
import dev.waylo.gateway.domain.parseEach
import dev.waylo.gateway.domain.parsing
import dev.waylo.gateway.domain.required

/** Parses the loose HTTP DTOs into the domain model, reporting every problem at once. */
object RequestParser {

    fun parse(dto: ParcelRequestDto): Parsed<ParcelQuery> = when (dto) {
        is ParcelRequestDto.Track -> track(dto)
        is ParcelRequestDto.Quote -> quote(dto)
    }

    private fun track(dto: ParcelRequestDto.Track): Parsed<ParcelQuery> = parsing {
        val numbers = field("trackingNumbers", required(dto.trackingNumbers) { it.parseEach(TrackingNumber::parse) })
        build { numbers() }.flatMap { TrackParcels.of(it, dto.priority ?: false) }
    }

    private fun quote(dto: ParcelRequestDto.Quote): Parsed<ParcelQuery> = parsing {
        val parcel = field("parcel", required(dto.parcel, ::parcel))
        val destination = field("destination", required(dto.destination, ::destination))
        build { QuoteShipment(parcel(), destination(), dto.priority ?: false) }
    }

    private fun parcel(dto: ParcelDto): Parsed<Parcel> = parsing {
        val weight = field("weightGrams", required(dto.weightGrams, Weight::ofGrams))
        val dimensions = field("dimensionsMm", required(dto.dimensionsMm) { Dimensions.of(it.length, it.width, it.height) })
        build { Parcel(weight(), dimensions()) }
    }.renameDimensionFields()

    private fun destination(dto: DestinationDto): Parsed<Destination> = parsing {
        val country = field("country", required(dto.country, CountryCode::parse))
        val postalCode = field("postalCode", required(dto.postalCode, PostalCode::parse))
        build { Destination(country(), postalCode()) }
    }

    /** The domain says `lengthMm`, the JSON (inside `dimensionsMm`) says `length`. */
    private fun <T> Parsed<T>.renameDimensionFields(): Parsed<T> = when (this) {
        is Parsed.Valid -> this
        is Parsed.Invalid -> Parsed.Invalid(errors.map { it.copy(field = it.field.replace(Regex("(dimensionsMm\\.\\w+)Mm$"), "$1")) })
    }
}
