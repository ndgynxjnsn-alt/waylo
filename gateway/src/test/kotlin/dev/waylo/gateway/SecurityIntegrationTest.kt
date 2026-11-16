package dev.waylo.gateway

import com.nimbusds.jose.jwk.JWKSet
import com.nimbusds.jose.jwk.RSAKey
import com.nimbusds.jose.jwk.source.ImmutableJWKSet
import dev.waylo.mock.DhlMockServer
import dev.waylo.mock.MockConfig
import org.junit.jupiter.api.AfterAll
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm
import org.springframework.security.oauth2.jwt.JwsHeader
import org.springframework.security.oauth2.jwt.JwtClaimsSet
import org.springframework.security.oauth2.jwt.JwtEncoderParameters
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.security.KeyPairGenerator
import java.security.interfaces.RSAPrivateKey
import java.security.interfaces.RSAPublicKey
import java.time.Duration
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** OAuth2 bearer tokens (as issued by Keycloak) next to API keys, plus CORS for the frontend. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class SecurityIntegrationTest {

    @LocalServerPort
    private var port: Int = 0

    private val http = HttpClient.newHttpClient()

    @Test
    fun `accepts a valid bearer token`() {
        assertEquals(200, post(bearer = token()).statusCode())
    }

    @Test
    fun `still accepts API keys`() {
        assertEquals(200, post(apiKey = API_KEY).statusCode())
    }

    @Test
    fun `rejects tokens for another audience, from another issuer, expired or unsigned`() {
        assertEquals(401, post(bearer = token(audience = "someone-else")).statusCode())
        assertEquals(401, post(bearer = token(issuer = "https://evil.example.com/realms/waylo")).statusCode())
        assertEquals(401, post(bearer = token(expiresIn = Duration.ofSeconds(-120))).statusCode())
        assertEquals(401, post(bearer = token().substringBeforeLast('.') + ".").statusCode())
    }

    @Test
    fun `advertises both schemes when unauthenticated`() {
        val response = post()
        assertEquals(401, response.statusCode())
        assertEquals("Bearer", response.headers().firstValue("WWW-Authenticate").orElse(null))
    }

    @Test
    fun `answers CORS preflights of allowed origins, including trace context headers`() {
        val response = preflight(ORIGIN, "authorization,content-type,traceparent,tracestate,baggage")

        assertEquals(200, response.statusCode())
        assertEquals(ORIGIN, response.headers().firstValue("Access-Control-Allow-Origin").orElse(null))
        val allowed = response.headers().firstValue("Access-Control-Allow-Headers").orElse("").lowercase()
        listOf("authorization", "content-type", "traceparent", "tracestate", "baggage").forEach {
            assert(it in allowed) { "$it missing in $allowed" }
        }
    }

    @Test
    fun `rejects CORS preflights of other origins`() {
        val response = preflight("https://evil.example.com", "authorization")
        assertEquals(403, response.statusCode())
        assertNull(response.headers().firstValue("Access-Control-Allow-Origin").orElse(null))
    }

    @Test
    fun `cross-origin requests with trace context get CORS headers`() {
        val response = post(
            bearer = token(),
            headers = mapOf("Origin" to ORIGIN, "traceparent" to "00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01"),
        )
        assertEquals(200, response.statusCode())
        assertEquals(ORIGIN, response.headers().firstValue("Access-Control-Allow-Origin").orElse(null))
    }

    private fun post(apiKey: String? = null, bearer: String? = null, headers: Map<String, String> = emptyMap()): HttpResponse<String> {
        val request = HttpRequest.newBuilder(URI("http://localhost:$port/request/track"))
            .header("Content-Type", "application/json")
            .apply { apiKey?.let { header("X-API-Key", it) } }
            .apply { bearer?.let { header("Authorization", "Bearer $it") } }
            .apply { headers.forEach { (k, v) -> header(k, v) } }
            .POST(HttpRequest.BodyPublishers.ofString("""{"trackingNumbers":["JJD000390007"]}"""))
            .build()
        return http.send(request, HttpResponse.BodyHandlers.ofString())
    }

    private fun preflight(origin: String, requestHeaders: String): HttpResponse<String> {
        val request = HttpRequest.newBuilder(URI("http://localhost:$port/request/track"))
            .header("Origin", origin)
            .header("Access-Control-Request-Method", "POST")
            .header("Access-Control-Request-Headers", requestHeaders)
            .method("OPTIONS", HttpRequest.BodyPublishers.noBody())
            .build()
        return http.send(request, HttpResponse.BodyHandlers.ofString())
    }

    private fun token(
        audience: String = AUDIENCE,
        issuer: String = ISSUER,
        expiresIn: Duration = Duration.ofMinutes(5),
    ): String {
        val now = Instant.now()
        val claims = JwtClaimsSet.builder()
            .issuer(issuer)
            .subject("frontend-user")
            .audience(listOf(audience))
            .issuedAt(now.minusSeconds(300))
            .expiresAt(now.plus(expiresIn))
            .claim("azp", "waylo-frontend")
            .build()
        val header = JwsHeader.with(SignatureAlgorithm.RS256).keyId(KEY.keyID).build()
        return encoder.encode(JwtEncoderParameters.from(header, claims)).tokenValue
    }

    companion object {
        private const val API_KEY = "security-test-key"
        private const val AUDIENCE = "waylo-api"
        private const val ORIGIN = "https://app.example.com"

        private val mock = DhlMockServer(MockConfig(port = 0)).start()

        /** The realm signing key. */
        private val KEY: RSAKey = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair().let {
            RSAKey.Builder(it.public as RSAPublicKey).privateKey(it.private as RSAPrivateKey).keyID("test-key").build()
        }
        private val encoder = NimbusJwtEncoder(ImmutableJWKSet(JWKSet(KEY)))

        /** Minimal Keycloak stand-in: OIDC discovery + JWKS, so the production config path is used. */
        private val keycloak: HttpServer = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            fun json(path: String, body: () -> String) = createContext(path) { exchange ->
                val bytes = body().toByteArray()
                exchange.responseHeaders.add("Content-Type", "application/json")
                exchange.sendResponseHeaders(200, bytes.size.toLong())
                exchange.responseBody.use { it.write(bytes) }
            }
            json("/realms/waylo/.well-known/openid-configuration") {
                """{"issuer":"$ISSUER","jwks_uri":"$ISSUER/protocol/openid-connect/certs"}"""
            }
            json("/realms/waylo/protocol/openid-connect/certs") { JWKSet(KEY.toPublicJWK()).toString() }
            start()
        }
        private val ISSUER = "http://127.0.0.1:${keycloak.address.port}/realms/waylo"

        @JvmStatic
        @DynamicPropertySource
        fun properties(registry: DynamicPropertyRegistry) {
            registry.add("waylo.dhl.port") { mock.port }
            registry.add("waylo.security.api-keys") { API_KEY }
            registry.add("spring.security.oauth2.resourceserver.jwt.issuer-uri") { ISSUER }
            registry.add("spring.security.oauth2.resourceserver.jwt.audiences") { AUDIENCE }
            registry.add("waylo.cors.allowed-origins") { "$ORIGIN,http://localhost:5173" }
        }

        @JvmStatic
        @AfterAll
        fun stop() {
            mock.close()
            keycloak.stop(0)
        }
    }
}
