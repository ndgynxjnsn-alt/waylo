package dev.waylo.gateway.api

import dev.waylo.gateway.domain.GatewayOutcome
import dev.waylo.gateway.domain.ParcelGateway
import dev.waylo.gateway.domain.ParcelQuery
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.ProblemDetail
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RestController

/**
 * JSON that does not match [ParcelQuery] (missing fields, wrong types, unknown "type") is
 * rejected by Spring with 400 before this method runs.
 */
@RestController
class RequestController(private val gateway: ParcelGateway) {

    @PostMapping("/request")
    fun request(@RequestBody query: ParcelQuery): ResponseEntity<Any> =
        when (val outcome = gateway.execute(query)) {
            is GatewayOutcome.Success -> ResponseEntity.ok(outcome.result)
            is GatewayOutcome.Rejected -> problem(HttpStatus.BAD_REQUEST, outcome.reason)
            is GatewayOutcome.Timeout -> problem(
                HttpStatus.GATEWAY_TIMEOUT,
                "DHL did not answer within ${outcome.perAttempt.toMillis()} ms in ${outcome.attempts} attempts",
            )
            is GatewayOutcome.Unavailable -> problem(HttpStatus.SERVICE_UNAVAILABLE, outcome.reason, retryAfterSeconds = 5)
            is GatewayOutcome.Failed -> problem(HttpStatus.BAD_GATEWAY, outcome.reason)
        }

    private fun problem(status: HttpStatus, detail: String, retryAfterSeconds: Int? = null): ResponseEntity<Any> {
        val response = ResponseEntity.status(status)
        if (retryAfterSeconds != null) response.header(HttpHeaders.RETRY_AFTER, retryAfterSeconds.toString())
        return response.body(ProblemDetail.forStatusAndDetail(status, detail))
    }
}
