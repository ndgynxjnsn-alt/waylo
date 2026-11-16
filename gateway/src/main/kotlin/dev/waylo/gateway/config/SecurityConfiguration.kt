package dev.waylo.gateway.config

import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.beans.factory.ObjectProvider
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpMethod
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ProblemDetail
import org.springframework.security.authentication.AbstractAuthenticationToken
import org.springframework.security.config.annotation.web.builders.HttpSecurity
import org.springframework.security.config.annotation.web.invoke
import org.springframework.security.config.http.SessionCreationPolicy
import org.springframework.security.core.authority.AuthorityUtils
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.security.oauth2.jwt.JwtDecoder
import org.springframework.security.web.AuthenticationEntryPoint
import org.springframework.security.web.SecurityFilterChain
import org.springframework.security.web.authentication.AnonymousAuthenticationFilter
import org.springframework.web.filter.OncePerRequestFilter
import tools.jackson.databind.json.JsonMapper
import java.security.MessageDigest

@ConfigurationProperties("waylo.security")
data class ApiKeyProperties(val apiKeys: List<String>) {
    init {
        require(apiKeys.isNotEmpty() && apiKeys.none { it.isBlank() }) { "waylo.security.api-keys must not be empty" }
    }
}

@Configuration(proxyBeanMethods = false)
class SecurityConfiguration {

    /**
     * Two ways to authenticate against the `POST /request/...` endpoints:
     *  * `X-API-Key` - for machine clients,
     *  * `Authorization: Bearer <JWT>` (OAuth2, e.g. Keycloak) - for the frontend. Only active when a
     *    JWT decoder is configured (`spring.security.oauth2.resourceserver.jwt.*`); issuer, signature,
     *    expiry and audience are validated by Spring Boot's decoder.
     */
    @Bean
    fun securityFilterChain(
        http: HttpSecurity,
        properties: ApiKeyProperties,
        cors: CorsProperties,
        jwtDecoder: ObjectProvider<JwtDecoder>,
        jsonMapper: JsonMapper,
    ): SecurityFilterChain {
        val oauth2 = jwtDecoder.ifAvailable != null
        val entryPoint = ProblemEntryPoint(jsonMapper, bearer = oauth2)
        http {
            cors { configurationSource = cors.toSource() }
            csrf { disable() } // stateless, no cookies
            httpBasic { disable() }
            formLogin { disable() }
            logout { disable() }
            sessionManagement { sessionCreationPolicy = SessionCreationPolicy.STATELESS }
            authorizeHttpRequests {
                authorize("/actuator/health/**", permitAll)
                authorize("/actuator/info", permitAll)
                authorize(HttpMethod.POST, "/request/**", authenticated) // API key or JWT
                authorize("/error", permitAll)
                authorize(anyRequest, denyAll)
            }
            addFilterBefore<AnonymousAuthenticationFilter>(ApiKeyFilter(properties.apiKeys))
            if (oauth2) {
                oauth2ResourceServer { jwt { } }
            }
            exceptionHandling {
                authenticationEntryPoint = entryPoint
            }
        }
        return http.build()
    }

    companion object {
        const val API_KEY_HEADER = "X-API-Key"
        const val API_CLIENT = "API_CLIENT"
    }
}

/** Authenticates requests carrying a known `X-API-Key`. Comparison is constant time. */
private class ApiKeyFilter(keys: List<String>) : OncePerRequestFilter() {
    private val keys = keys.map { it.toByteArray() }

    override fun doFilterInternal(request: HttpServletRequest, response: HttpServletResponse, chain: FilterChain) {
        val presented = request.getHeader(SecurityConfiguration.API_KEY_HEADER)?.toByteArray()
        val alreadyAuthenticated = SecurityContextHolder.getContext().authentication != null // e.g. by JWT
        if (!alreadyAuthenticated && presented != null && keys.fold(false) { found, key -> MessageDigest.isEqual(key, presented) or found }) {
            SecurityContextHolder.getContext().authentication = ApiKeyAuthentication
        }
        chain.doFilter(request, response)
    }
}

private object ApiKeyAuthentication : AbstractAuthenticationToken(AuthorityUtils.createAuthorityList(SecurityConfiguration.API_CLIENT)) {
    private fun readResolve(): Any = ApiKeyAuthentication
    override fun getCredentials() = null
    override fun getPrincipal() = "api-client"
    override fun isAuthenticated() = true
}

private class ProblemEntryPoint(private val jsonMapper: JsonMapper, private val bearer: Boolean) : AuthenticationEntryPoint {
    override fun commence(
        request: HttpServletRequest,
        response: HttpServletResponse,
        authException: org.springframework.security.core.AuthenticationException,
    ) {
        val accepted = if (bearer) "${SecurityConfiguration.API_KEY_HEADER} header or Bearer token" else "${SecurityConfiguration.API_KEY_HEADER} header"
        val problem = ProblemDetail.forStatusAndDetail(HttpStatus.UNAUTHORIZED, "A valid $accepted is required")
        if (bearer) response.setHeader(HttpHeaders.WWW_AUTHENTICATE, "Bearer")
        response.status = HttpStatus.UNAUTHORIZED.value()
        response.contentType = MediaType.APPLICATION_PROBLEM_JSON_VALUE
        jsonMapper.writeValue(response.outputStream, problem)
    }
}
