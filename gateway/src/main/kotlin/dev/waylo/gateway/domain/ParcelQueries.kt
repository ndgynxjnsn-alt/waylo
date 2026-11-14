package dev.waylo.gateway.domain

import java.util.Locale

/*
 * Domain model of what clients can ask. Constructors are private: the only way to obtain an
 * instance is through the `parse` functions, so every instance is valid by construction.
 */

/** A question to the parcel carrier. */
sealed interface ParcelQuery {
    val priority: Boolean
}

@ConsistentCopyVisibility
data class TrackParcels private constructor(
    val trackingNumbers: List<TrackingNumber>,
    override val priority: Boolean,
) : ParcelQuery {
    companion object {
        const val MAX_PARCELS = 20

        fun of(trackingNumbers: List<TrackingNumber>, priority: Boolean): Parsed<TrackParcels> = when {
            trackingNumbers.isEmpty() -> Parsed.Invalid("trackingNumbers", "must not be empty")
            trackingNumbers.size > MAX_PARCELS -> Parsed.Invalid("trackingNumbers", "at most $MAX_PARCELS parcels")
            trackingNumbers.toSet().size != trackingNumbers.size -> Parsed.Invalid("trackingNumbers", "must not contain duplicates")
            else -> Parsed.Valid(TrackParcels(trackingNumbers, priority))
        }
    }
}

data class QuoteShipment(
    val parcel: Parcel,
    val destination: Destination,
    override val priority: Boolean,
) : ParcelQuery

@JvmInline
value class TrackingNumber private constructor(val value: String) {
    override fun toString() = value

    companion object {
        private val FORMAT = Regex("[A-Z0-9]{8,35}")

        fun parse(raw: String): Parsed<TrackingNumber> {
            val normalized = raw.trim().uppercase(Locale.ROOT)
            return if (FORMAT.matches(normalized)) Parsed.Valid(TrackingNumber(normalized))
            else Parsed.invalid("must be 8-35 letters or digits")
        }
    }
}

data class Parcel(val weight: Weight, val dimensions: Dimensions)

@JvmInline
value class Weight private constructor(val grams: Int) {
    companion object {
        const val MAX_GRAMS = 31_500

        fun ofGrams(grams: Int): Parsed<Weight> =
            if (grams in 1..MAX_GRAMS) Parsed.Valid(Weight(grams)) else Parsed.invalid("must be 1..$MAX_GRAMS grams")
    }
}

@ConsistentCopyVisibility
data class Dimensions private constructor(val lengthMm: Int, val widthMm: Int, val heightMm: Int) {
    companion object {
        const val MAX_MM = 1_200

        fun of(lengthMm: Int?, widthMm: Int?, heightMm: Int?): Parsed<Dimensions> = parsing {
            val l = field("lengthMm", required(lengthMm, ::side))
            val w = field("widthMm", required(widthMm, ::side))
            val h = field("heightMm", required(heightMm, ::side))
            build { Dimensions(l(), w(), h()) }
        }

        private fun side(mm: Int): Parsed<Int> =
            if (mm in 1..MAX_MM) Parsed.Valid(mm) else Parsed.invalid("must be 1..$MAX_MM mm")
    }
}

data class Destination(val country: CountryCode, val postalCode: PostalCode)

@JvmInline
value class CountryCode private constructor(val value: String) {
    companion object {
        private val ISO_COUNTRIES = Locale.getISOCountries().toSet()

        fun parse(raw: String): Parsed<CountryCode> {
            val code = raw.trim().uppercase(Locale.ROOT)
            return if (code in ISO_COUNTRIES) Parsed.Valid(CountryCode(code))
            else Parsed.invalid("must be an ISO 3166-1 alpha-2 country code")
        }
    }
}

@JvmInline
value class PostalCode private constructor(val value: String) {
    companion object {
        private val FORMAT = Regex("[A-Z0-9][A-Z0-9 -]{0,11}")

        fun parse(raw: String): Parsed<PostalCode> {
            val code = raw.trim().uppercase(Locale.ROOT)
            return if (FORMAT.matches(code)) Parsed.Valid(PostalCode(code))
            else Parsed.invalid("must be 1-12 letters, digits, spaces or dashes")
        }
    }
}
