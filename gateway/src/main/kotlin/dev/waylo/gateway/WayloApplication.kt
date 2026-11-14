package dev.waylo.gateway

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.security.autoconfigure.UserDetailsServiceAutoConfiguration
import org.springframework.boot.context.properties.ConfigurationPropertiesScan
import org.springframework.boot.runApplication

// Clients authenticate with API keys only; no users, no generated password.
@SpringBootApplication(exclude = [UserDetailsServiceAutoConfiguration::class])
@ConfigurationPropertiesScan
class WayloApplication

fun main(args: Array<String>) {
    runApplication<WayloApplication>(*args)
}
