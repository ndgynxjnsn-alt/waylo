package dev.waylo.gateway.api

import dev.waylo.gateway.domain.GatewayOutcome
import dev.waylo.gateway.domain.ParcelGateway
import dev.waylo.gateway.domain.ParcelResult
import dev.waylo.gateway.domain.QuoteResult
import dev.waylo.gateway.domain.TrackingResult
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.ProblemDetail
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

/**
 * One endpoint per query. A JSON body that does not match the DTO (missing fields, wrong types)
 * is rejected by Spring with 400 before these methods run.
 */
@RestController
@RequestMapping("/request")
class RequestController(private val gateway: ParcelGateway) {

    @PostMapping("/track")
    fun track(@RequestBody request: TrackRequest): ResponseEntity<Any> =
        respond(gateway.execute(request.toDomain()))

    @PostMapping("/quote")
    fun quote(@RequestBody request: QuoteRequest): ResponseEntity<Any> =
        respond(gateway.execute(request.toDomain()))

    private fun respond(outcome: GatewayOutcome): ResponseEntity<Any> = when (outcome) {
        is GatewayOutcome.Success -> ResponseEntity.ok(toResponse(outcome.result))
        is GatewayOutcome.Rejected -> problem(HttpStatus.BAD_REQUEST, outcome.reason)
        is GatewayOutcome.Timeout -> problem(
            HttpStatus.GATEWAY_TIMEOUT,
            "DHL did not answer within ${outcome.perAttempt.toMillis()} ms in ${outcome.attempts} attempts",
        )
        is GatewayOutcome.Unavailable -> problem(HttpStatus.SERVICE_UNAVAILABLE, outcome.reason, retryAfterSeconds = 5)
        is GatewayOutcome.Failed -> problem(HttpStatus.BAD_GATEWAY, outcome.reason)
    }

    private fun toResponse(result: ParcelResult): Any = when (result) {
        is TrackingResult -> TrackResponse.from(result)
        is QuoteResult -> QuoteResponse.from(result)
    }

    private fun problem(status: HttpStatus, detail: String, retryAfterSeconds: Int? = null): ResponseEntity<Any> {
        val response = ResponseEntity.status(status)
        if (retryAfterSeconds != null) response.header(HttpHeaders.RETRY_AFTER, retryAfterSeconds.toString())
        return response.body(ProblemDetail.forStatusAndDetail(status, detail))
    }
}
