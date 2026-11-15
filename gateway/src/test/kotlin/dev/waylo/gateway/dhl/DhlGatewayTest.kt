package dev.waylo.gateway.dhl

import dev.waylo.gateway.domain.GatewayOutcome
import dev.waylo.gateway.domain.Parsed
import dev.waylo.gateway.domain.TrackParcels
import dev.waylo.gateway.domain.TrackingNumber
import dev.waylo.gateway.domain.TrackingResult
import dev.waylo.mock.DhlMockServer
import dev.waylo.mock.MockConfig
import dev.waylo.protocol.Handshake
import dev.waylo.protocol.Request
import java.time.Duration
import java.util.concurrent.CompletableFuture
import kotlin.test.AfterTest
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** The DHL adapter against the real mock over UDP, without Spring. */
class DhlGatewayTest {
    private val mock = DhlMockServer(MockConfig(port = 0, lossRate = 1.0)).start() // DHL "down"
    private val properties = DhlProperties(
        host = "127.0.0.1", port = mock.port, timeout = Duration.ofMillis(50), retries = 2,
        handshake = DhlProperties.Handshake("test", retryBackoff = Duration.ofMillis(20), maxWait = Duration.ofSeconds(2)),
    )
    private val connection = DhlConnection(properties)
    private val session = DhlSession(connection, properties)
    private val gateway = DhlGateway(connection, session, properties)

    private val query = (TrackParcels.of(listOf(trackingNumber("JJD000390007")), priority = false) as Parsed.Valid).value

    @AfterTest
    fun tearDown() {
        session.close()
        connection.close()
        mock.close()
    }

    @Test
    fun `requests wait until the handshake is done`() {
        session.start()
        val outcome = CompletableFuture.supplyAsync { gateway.execute(query) }
        Thread.sleep(300) // several handshake rounds get lost meanwhile
        assertTrue(mock.received.none { it.body is Request }, "no request may be sent before the handshake")

        mock.config = MockConfig(port = 0) // DHL comes back

        val result = assertIs<GatewayOutcome.Success>(outcome.get()).result
        assertEquals(query.trackingNumbers, (result as TrackingResult).parcels.map { it.trackingNumber })
        val firstRequest = mock.received.indexOfFirst { it.body is Request }
        val lastHandshake = mock.received.indexOfLast { it.body is Handshake }
        assertTrue(lastHandshake < firstRequest, "handshake precedes the request")
        assertEquals(0, connection.inFlight)
    }

    @Test
    fun `is unavailable when the handshake does not complete in time`() {
        val impatient = DhlGateway(connection, session, properties.copy(handshake = properties.handshake.copy(maxWait = Duration.ofMillis(100))))
        session.start()

        assertIs<GatewayOutcome.Unavailable>(impatient.execute(query))
    }

    private fun trackingNumber(raw: String) = (TrackingNumber.parse(raw) as Parsed.Valid).value
}
