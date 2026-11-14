package dev.waylo.gateway.api

import dev.waylo.gateway.domain.Money
import dev.waylo.gateway.domain.ParcelResult
import dev.waylo.gateway.domain.QuoteResult
import dev.waylo.gateway.domain.TrackingResult
import java.math.BigDecimal

object ResponseMapper {

    fun toDto(result: ParcelResult): ParcelResponseDto = when (result) {
        is TrackingResult -> ParcelResponseDto.Tracking(
            result.parcels.map { parcel ->
                ParcelTrackingDto(
                    trackingNumber = parcel.trackingNumber.value,
                    status = parcel.currentStatus?.name,
                    events = parcel.events.map { TrackingEventDto(it.timestamp, it.status.name, it.location) },
                )
            },
        )
        is QuoteResult -> ParcelResponseDto.Quote(result.price.toDto())
    }

    private fun Money.toDto() = PriceDto(
        amount = BigDecimal.valueOf(minorUnits, currency.defaultFractionDigits).toPlainString(),
        minorUnits = minorUnits,
        currency = currency.currencyCode,
    )
}
