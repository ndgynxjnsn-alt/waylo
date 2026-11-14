package dev.waylo.gateway.dhl

import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

@Configuration(proxyBeanMethods = false)
class DhlConfiguration {

    @Bean(destroyMethod = "close")
    fun dhlConnection(properties: DhlProperties) = DhlConnection(properties)

    /** The handshake starts as soon as the context is up; requests wait for it. */
    @Bean(initMethod = "start", destroyMethod = "close")
    fun dhlSession(connection: DhlConnection, properties: DhlProperties) = DhlSession(connection, properties)

    @Bean
    fun dhlGateway(connection: DhlConnection, session: DhlSession, properties: DhlProperties) =
        DhlGateway(connection, session, properties)
}
