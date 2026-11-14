package dev.waylo.gateway.dhl

import org.springframework.boot.context.properties.ConfigurationProperties
import java.time.Duration

@ConfigurationProperties("waylo.dhl")
data class DhlProperties(
    val host: String,
    val port: Int,
    /** Wait for an answer per datagram before retransmitting. */
    val timeout: Duration,
    /** Retransmissions after the first attempt. */
    val retries: Int,
    val handshake: Handshake,
) {
    val attempts: Int get() = 1 + retries

    data class Handshake(
        val clientName: String,
        val retryBackoff: Duration,
        val maxWait: Duration,
    )

    init {
        require(port in 1..65535) { "waylo.dhl.port must be 1..65535" }
        require(!timeout.isNegative && !timeout.isZero) { "waylo.dhl.timeout must be positive" }
        require(retries >= 0) { "waylo.dhl.retries must be >= 0" }
    }
}
