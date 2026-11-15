package dev.waylo.gateway.dhl

import dev.waylo.mock.DhlMockServer
import dev.waylo.mock.MockConfig
import dev.waylo.protocol.Handshake
import dev.waylo.protocol.MessageType
import io.opentelemetry.api.common.AttributeKey
import io.opentelemetry.api.trace.SpanKind
import io.opentelemetry.api.trace.StatusCode
import io.opentelemetry.sdk.OpenTelemetrySdk
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter
import io.opentelemetry.sdk.trace.SdkTracerProvider
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor
import java.time.Duration
import kotlin.test.AfterTest
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

class DhlTelemetryTest {
    private val spans = InMemorySpanExporter.create()
    private val otel = OpenTelemetrySdk.builder()
        .setTracerProvider(SdkTracerProvider.builder().addSpanProcessor(SimpleSpanProcessor.create(spans)).build())
        .build()
    private val mock = DhlMockServer(MockConfig(port = 0)).start()
    private val properties = DhlProperties(
        host = "127.0.0.1", port = mock.port, timeout = Duration.ofMillis(50), retries = 2,
        handshake = DhlProperties.Handshake("test", Duration.ofMillis(10), Duration.ofSeconds(1)),
    )
    private val connection = DhlConnection(properties, DhlTelemetry(properties, otel))

    @AfterTest
    fun tearDown() {
        connection.close()
        mock.close()
        otel.close()
    }

    @Test
    fun `an answered exchange is one CLIENT span towards the dhl peer service`() {
        connection.exchange(Handshake("test", 1u), MessageType.HANDSHAKE_ACK)

        val span = spans.finishedSpanItems.single()
        assertEquals("DHL handshake", span.name)
        assertEquals(SpanKind.CLIENT, span.kind)
        assertEquals("dhl", span.attributes.get(AttributeKey.stringKey("peer.service")))
        assertEquals("udp", span.attributes.get(AttributeKey.stringKey("network.transport")))
        assertEquals(1L, span.attributes.get(AttributeKey.longKey("dhl.attempts")))
        assertEquals(StatusCode.UNSET, span.status.statusCode)
    }

    @Test
    fun `a timed out exchange records retransmissions and an error`() {
        mock.config = MockConfig(port = 0, lossRate = 1.0)

        connection.exchange(Handshake("test", 1u), MessageType.HANDSHAKE_ACK)

        val span = spans.finishedSpanItems.single()
        assertEquals(StatusCode.ERROR, span.status.statusCode)
        assertEquals("timeout", span.attributes.get(AttributeKey.stringKey("error.type")))
        assertEquals(listOf("retransmission", "retransmission"), span.events.map { it.name })
    }
}
