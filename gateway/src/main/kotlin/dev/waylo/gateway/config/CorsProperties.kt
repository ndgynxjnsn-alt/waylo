package dev.waylo.gateway.config

import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.web.cors.CorsConfiguration
import org.springframework.web.cors.CorsConfigurationSource
import org.springframework.web.cors.UrlBasedCorsConfigurationSource
import java.time.Duration

/**
 * CORS for browser frontends. Spring Boot has CORS properties only for Actuator, hence this class.
 * Disabled (no cross-origin access) while [allowedOrigins] is empty.
 */
@ConfigurationProperties("waylo.cors")
data class CorsProperties(
    /** Allowed origins: exact (`https://app.example.com`) or patterns with a `*` subdomain wildcard. */
    val allowedOrigins: List<String> = emptyList(),
    val allowedMethods: List<String> = listOf("POST", "OPTIONS"),
    /** Request headers a browser may send. Includes W3C Trace Context / Baggage for frontend tracing. */
    val allowedHeaders: List<String> = listOf(
        "Content-Type", "Authorization", "X-API-Key", "traceparent", "tracestate", "baggage",
    ),
    /** Response headers the browser may read. */
    val exposedHeaders: List<String> = listOf("Retry-After", "WWW-Authenticate"),
    val allowCredentials: Boolean = false,
    val maxAge: Duration = Duration.ofMinutes(30),
) {
    fun toSource(): CorsConfigurationSource = UrlBasedCorsConfigurationSource().apply {
        if (allowedOrigins.isEmpty()) return@apply
        registerCorsConfiguration(
            "/request/**",
            CorsConfiguration().also {
                it.allowedOriginPatterns = allowedOrigins
                it.allowedMethods = allowedMethods
                it.allowedHeaders = allowedHeaders
                it.exposedHeaders = exposedHeaders
                it.allowCredentials = allowCredentials
                it.maxAge = maxAge.seconds
            },
        )
    }
}
