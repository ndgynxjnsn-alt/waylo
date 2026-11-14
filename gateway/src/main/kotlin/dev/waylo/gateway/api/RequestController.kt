package dev.waylo.gateway.api

import dev.waylo.gateway.domain.GatewayOutcome
import dev.waylo.gateway.domain.ParcelGateway
import dev.waylo.gateway.domain.Parsed
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.ProblemDetail
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RestController

@RestController
class RequestController(private val gateway: ParcelGateway) {

    @PostMapping("/request")
    fun request(@RequestBody body: ParcelRequestDto): ResponseEntity<Any> {
        val query = when (val parsed = RequestParser.parse(body)) {
            is Parsed.Valid -> parsed.value
            is Parsed.Invalid -> return problem(HttpStatus.BAD_REQUEST, "Invalid request") {
                setProperty("errors", parsed.errors)
            }
        }
        return when (val outcome = gateway.execute(query)) {
            is GatewayOutcome.Success -> ResponseEntity.ok(ResponseMapper.toDto(outcome.result))
            is GatewayOutcome.Timeout -> problem(
                HttpStatus.GATEWAY_TIMEOUT,
                "DHL did not answer within ${outcome.perAttempt.toMillis()} ms in ${outcome.attempts} attempts",
            )
            is GatewayOutcome.Unavailable -> problem(HttpStatus.SERVICE_UNAVAILABLE, outcome.reason, retryAfterSeconds = 5)
            is GatewayOutcome.Failed -> problem(HttpStatus.BAD_GATEWAY, outcome.reason)
        }
    }

    private fun problem(
        status: HttpStatus,
        detail: String,
        retryAfterSeconds: Int? = null,
        customize: ProblemDetail.() -> Unit = {},
    ): ResponseEntity<Any> {
        val builder = ResponseEntity.status(status)
        retryAfterSeconds?.let { builder.header(HttpHeaders.RETRY_AFTER, it.toString()) }
        return builder.body(ProblemDetail.forStatusAndDetail(status, detail).apply(customize))
    }
}
