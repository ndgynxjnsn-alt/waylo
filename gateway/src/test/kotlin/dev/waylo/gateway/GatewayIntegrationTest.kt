package dev.waylo.gateway

import com.jayway.jsonpath.JsonPath
import dev.waylo.mock.DhlMockServer
import dev.waylo.mock.MockConfig
import dev.waylo.protocol.Handshake
import dev.waylo.protocol.Request
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.AfterEach
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.concurrent.Executors
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class GatewayIntegrationTest {

    @LocalServerPort
    private var port: Int = 0

    private val http = HttpClient.newHttpClient()

    @AfterEach
    fun resetMock() {
        mock.config = MockConfig(port = 0)
        mock.received.clear()
    }

    @Test
    fun `tracks parcels through the DHL mock`() {
        val response = post("""{"type":"track","trackingNumbers":["JJD000390007","UNKNOWN0001"]}""")

        assertEquals(200, response.statusCode(), response.body())
        assertEquals("tracking", json(response, "$.type"))
        assertEquals("JJD000390007", json(response, "$.parcels[0].trackingNumber"))
        assertTrue(json<List<Any>>(response, "$.parcels[0].events").isNotEmpty())
        assertEquals("UNKNOWN0001", json(response, "$.parcels[1].trackingNumber"))
        assertEquals(emptyList<Any>(), json(response, "$.parcels[1].events"))
    }

    @Test
    fun `quotes a shipment`() {
        val response = post(
            """{"type":"quote","parcel":{"weightGrams":1200,"dimensionsMm":{"length":300,"width":200,"height":100}},
               "destination":{"country":"FR","postalCode":"75001"}}""",
        )

        assertEquals(200, response.statusCode(), response.body())
        assertEquals("quote", json(response, "$.type"))
        assertEquals(1539, json(response, "$.price.amountCents")) // 12.99 EU + 2 kg * 1.20
        assertEquals("EUR", json(response, "$.price.currency"))
    }

    @Test
    fun `rejects requests without a valid API key`() {
        assertEquals(401, post("""{"type":"track","trackingNumbers":["JJD000390007"]}""", apiKey = null).statusCode())
        assertEquals(401, post("""{"type":"track","trackingNumbers":["JJD000390007"]}""", apiKey = "nope").statusCode())
        assertTrue(mock.received.none { it.body is Request }, "nothing must reach DHL")
    }

    @Test
    fun `rejects JSON that does not match the domain objects`() {
        val cases = listOf(
            """{"type":"track","trackingNumbers":""",                                    // malformed JSON
            """{"type":"teleport"}""",                                                  // unknown type
            """{"type":"track"}""",                                                     // missing field
            """{"type":"track","trackingNumbers":["JJD000390007",null]}""",             // null element
            """{"type":"quote","parcel":{"weightGrams":"heavy","dimensionsMm":{"length":1,"width":1,"height":1}},
               "destination":{"country":"DE","postalCode":"1"}}""",                       // wrong type
        )
        cases.forEach { assertEquals(400, post(it).statusCode(), it) }
        assertTrue(mock.received.none { it.body is Request }, "invalid requests must not reach DHL")
    }

    @Test
    fun `rejects values the protocol cannot carry and requests DHL refuses`() {
        val tooLong = post(
            """{"type":"quote","parcel":{"weightGrams":1000,"dimensionsMm":{"length":70000,"width":1,"height":1}},
               "destination":{"country":"DE","postalCode":"1"}}""",
        )
        assertEquals(400, tooLong.statusCode(), tooLong.body())
        assertEquals("request cannot be sent to DHL: length must be 0..65535", json(tooLong, "$.detail"))

        val empty = post("""{"type":"track","trackingNumbers":[]}""") // DHL answers bad_request
        assertEquals(400, empty.statusCode(), empty.body())
    }

    @Test
    fun `answers 504 after the configured retries when DHL stays silent`() {
        mock.config = MockConfig(port = 0, lossRate = 1.0)

        val response = post("""{"type":"track","trackingNumbers":["JJD000390007"]}""")

        assertEquals(504, response.statusCode())
        val attempts = mock.received.filter { it.body is Request }
        assertEquals(3, attempts.size, "1 attempt + 2 retries")
        assertEquals(1, attempts.map { it.header.messageId }.distinct().size, "retries reuse the message id")
        assertEquals(listOf(false, true, true), attempts.map { it.header.flags.retransmission })
    }

    @Test
    fun `serves many concurrent requests with out-of-order answers`() {
        mock.config = MockConfig(port = 0, minDelay = Duration.ZERO, maxDelay = Duration.ofMillis(60))
        val numbers = (1..100).map { "JJD%09d".format(it) }

        val responses = Executors.newVirtualThreadPerTaskExecutor().use { pool ->
            numbers.map { n -> pool.submit<HttpResponse<String>> { post("""{"type":"track","trackingNumbers":["$n"]}""") } }
                .map { it.get() }
        }

        responses.zip(numbers).forEach { (response, number) ->
            assertEquals(200, response.statusCode(), response.body())
            assertEquals(number, json(response, "$.parcels[0].trackingNumber"), "answer routed to the right request")
        }
    }

    @Test
    fun `re-establishes the session when DHL forgets it`() {
        mock.invalidateSessions()

        val response = post("""{"type":"track","trackingNumbers":["JJD000390007"]}""")

        assertEquals(200, response.statusCode(), response.body())
        assertTrue(mock.received.any { it.body is Handshake }, "a new handshake took place")
    }

    @Test
    fun `health endpoint is public`() {
        val request = HttpRequest.newBuilder(URI("http://localhost:$port/actuator/health")).GET().build()
        assertEquals(200, http.send(request, HttpResponse.BodyHandlers.ofString()).statusCode())
    }

    private fun post(body: String, apiKey: String? = API_KEY): HttpResponse<String> {
        val request = HttpRequest.newBuilder(URI("http://localhost:$port/request"))
            .header("Content-Type", "application/json")
            .apply { apiKey?.let { header("X-API-Key", it) } }
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .build()
        return http.send(request, HttpResponse.BodyHandlers.ofString())
    }

    private fun <T> json(response: HttpResponse<String>, path: String): T = JsonPath.read(response.body(), path)

    companion object {
        private const val API_KEY = "test-key"
        private val mock = DhlMockServer(MockConfig(port = 0)).start()

        @JvmStatic
        @DynamicPropertySource
        fun properties(registry: DynamicPropertyRegistry) {
            registry.add("waylo.dhl.port") { mock.port }
            registry.add("waylo.dhl.timeout") { "150ms" }
            registry.add("waylo.dhl.retries") { 2 }
            registry.add("waylo.security.api-keys") { "other-key,$API_KEY" }
        }

        @JvmStatic
        @AfterAll
        fun stopMock() = mock.close()
    }
}
