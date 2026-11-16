package dev.waylo.gateway.domain

import java.time.Duration

/** Port to the external carrier. Implemented by the DHL UDP adapter. */
fun interface ParcelGateway {
    fun execute(query: ParcelQuery): GatewayOutcome
}

sealed interface GatewayOutcome {
    data class Success(val result: ParcelResult) : GatewayOutcome

    /** No answer after every attempt. */
    data class Timeout(val attempts: Int, val perAttempt: Duration) : GatewayOutcome

    /** The carrier session is not (yet) established, e.g. handshake still pending. */
    data class Unavailable(val reason: String) : GatewayOutcome

    /** The request cannot be served as asked: DHL rejected it, or it does not fit the protocol. */
    data class Rejected(val reason: String) : GatewayOutcome

    /** The carrier answered, but with an error or with something we cannot make sense of. */
    data class Failed(val reason: String) : GatewayOutcome
}
