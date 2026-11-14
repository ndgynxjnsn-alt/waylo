package dev.waylo.mock

import java.time.Duration

/**
 * @property lossRate probability (0..1) that a datagram is lost, applied independently to the
 *   incoming request and to the outgoing answer - so the gateway sees both kinds of UDP loss.
 * @property minDelay lower bound of the simulated processing time per answer.
 * @property maxDelay upper bound; answers to concurrent requests therefore arrive out of order.
 */
data class MockConfig(
    val port: Int = 9999,
    val lossRate: Double = 0.0,
    val minDelay: Duration = Duration.ZERO,
    val maxDelay: Duration = Duration.ZERO,
) {
    init {
        require(port in 0..65535) { "port out of range" }
        require(lossRate in 0.0..1.0) { "lossRate must be within 0..1" }
        require(!minDelay.isNegative && minDelay <= maxDelay) { "need 0 <= minDelay <= maxDelay" }
    }

    companion object {
        fun fromEnvironment(env: Map<String, String> = System.getenv()) = MockConfig(
            port = env["DHL_MOCK_PORT"]?.toInt() ?: 9999,
            lossRate = env["DHL_MOCK_LOSS_RATE"]?.toDouble() ?: 0.0,
            minDelay = Duration.ofMillis(env["DHL_MOCK_MIN_DELAY_MS"]?.toLong() ?: 5),
            maxDelay = Duration.ofMillis(env["DHL_MOCK_MAX_DELAY_MS"]?.toLong() ?: 50),
        )
    }
}
