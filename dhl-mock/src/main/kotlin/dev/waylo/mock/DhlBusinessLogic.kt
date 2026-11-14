package dev.waylo.mock

import dev.waylo.protocol.Operation
import dev.waylo.protocol.ParcelStatus
import dev.waylo.protocol.Record
import dev.waylo.protocol.ResponseStatus
import java.time.Clock
import java.time.Duration
import kotlin.math.absoluteValue
import kotlin.math.max

/** Fake, deterministic parcel data. Pure: same input, same output (relative to [clock]). */
class DhlBusinessLogic(private val clock: Clock = Clock.systemUTC()) {

    data class Outcome(val status: ResponseStatus, val records: List<Record> = emptyList())

    fun handle(operation: Operation, records: List<Record>): Outcome = when (operation) {
        Operation.TRACK -> track(records)
        Operation.QUOTE -> quote(records)
    }

    private fun track(records: List<Record>): Outcome {
        val numbers = records.filterIsInstance<Record.TrackingNumber>()
        if (numbers.isEmpty() || numbers.size != records.size) return Outcome(ResponseStatus.BAD_REQUEST)
        val answer = numbers.flatMap { listOf(it) + eventsFor(it.value) }
        if (answer.size > MAX_RECORDS) return Outcome(ResponseStatus.BAD_REQUEST)
        return Outcome(ResponseStatus.OK, answer)
    }

    /** Unknown parcels (every 7th hash, or numbers starting with "UNKNOWN") have no events. */
    private fun eventsFor(trackingNumber: String): List<Record.TrackingEvent> {
        val hash = trackingNumber.hashCode().absoluteValue
        if (trackingNumber.startsWith("UNKNOWN") || hash % 7 == 0) return emptyList()
        val progress = 1 + hash % PROGRESSION.size
        val now = clock.instant()
        return (0 until progress).map { step ->
            Record.TrackingEvent(
                timestamp = now.minus(Duration.ofHours(6L * (progress - step))),
                status = PROGRESSION[step],
                location = LOCATIONS[(hash / (step + 1)) % LOCATIONS.size],
            )
        }
    }

    private fun quote(records: List<Record>): Outcome {
        val weight = records.singleOf<Record.Weight>()
        val dimensions = records.singleOf<Record.Dimensions>()
        val destination = records.singleOf<Record.Destination>()
        if (weight == null || dimensions == null || destination == null || records.size != 3) {
            return Outcome(ResponseStatus.BAD_REQUEST)
        }
        val volumetricGrams = dimensions.lengthMm.toLong() * dimensions.widthMm.toLong() *
            dimensions.heightMm.toLong() / 5_000L
        val billableKg = (max(weight.grams.toLong(), volumetricGrams) + 999) / 1000
        val base = when (destination.countryCode) {
            "DE" -> 499L
            in EU -> 1_299L
            else -> 2_999L
        }
        val cents = base + billableKg * 120L
        if (cents > UInt.MAX_VALUE.toLong()) return Outcome(ResponseStatus.BAD_REQUEST)
        return Outcome(ResponseStatus.OK, listOf(Record.Price(cents.toUInt(), "EUR")))
    }

    private inline fun <reified T : Record> List<Record>.singleOf(): T? =
        filterIsInstance<T>().singleOrNull()

    private companion object {
        const val MAX_RECORDS = 255
        val PROGRESSION = listOf(
            ParcelStatus.LABEL_CREATED, ParcelStatus.PICKED_UP, ParcelStatus.IN_TRANSIT,
            ParcelStatus.IN_TRANSIT, ParcelStatus.OUT_FOR_DELIVERY, ParcelStatus.DELIVERED,
        )
        val LOCATIONS = listOf(
            "Leipzig Hub", "Köln-Eifeltor", "Hamburg-Allermöhe", "München-Aschheim",
            "Berlin-Tempelhof", "Frankfurt am Main", "Bremen", "Nürnberg-Feucht",
        )
        val EU = setOf(
            "AT", "BE", "BG", "CY", "CZ", "DK", "EE", "ES", "FI", "FR", "GR", "HR", "HU", "IE",
            "IT", "LT", "LU", "LV", "MT", "NL", "PL", "PT", "RO", "SE", "SI", "SK",
        )
    }
}
