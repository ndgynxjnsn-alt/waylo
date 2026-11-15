package dev.waylo.gateway.dhl

import dev.waylo.protocol.Body
import dev.waylo.protocol.Handshake
import dev.waylo.protocol.HandshakeAck
import dev.waylo.protocol.Message
import dev.waylo.protocol.MessageId
import dev.waylo.protocol.Request
import dev.waylo.protocol.Response
import dev.waylo.protocol.ResponseStatus
import io.opentelemetry.api.GlobalOpenTelemetry
import io.opentelemetry.api.OpenTelemetry
import io.opentelemetry.api.common.AttributeKey
import io.opentelemetry.api.common.AttributeKey.longKey
import io.opentelemetry.api.common.AttributeKey.stringKey
import io.opentelemetry.api.common.Attributes
import io.opentelemetry.api.metrics.DoubleHistogram
import io.opentelemetry.api.metrics.LongCounter
import io.opentelemetry.api.metrics.Meter
import io.opentelemetry.api.trace.Span
import io.opentelemetry.api.trace.SpanKind
import io.opentelemetry.api.trace.StatusCode
import io.opentelemetry.api.trace.Tracer

/**
 * Traces and metrics for the DHL connection, via the OpenTelemetry API (bridged by the javaagent,
 * no-op without it).
 *
 * DHL is an external system that never takes part in our telemetry, so - like a database - it is
 * modelled as the target of CLIENT spans. `peer.service=dhl` makes Tempo's service graph draw it
 * as its own (virtual) node.
 */
class DhlTelemetry(
    private val properties: DhlProperties,
    openTelemetry: OpenTelemetry = GlobalOpenTelemetry.get(),
) {
    private val tracer: Tracer = openTelemetry.getTracer(SCOPE)
    private val meter: Meter = openTelemetry.getMeter(SCOPE)

    private val duration: DoubleHistogram = meter.histogramBuilder("dhl.client.exchange.duration")
        .setUnit("s").setDescription("Time from first send until answer or final timeout").build()
    private val retransmissions: LongCounter = meter.counterBuilder("dhl.client.retransmissions")
        .setDescription("Datagrams sent again because no answer arrived in time").build()
    private val dropped: LongCounter = meter.counterBuilder("dhl.client.dropped")
        .setDescription("Incoming datagrams that could not be matched to an in-flight exchange").build()

    fun registerInFlightGauge(inFlight: () -> Int) {
        meter.gaugeBuilder("dhl.client.in_flight").ofLongs()
            .setDescription("Exchanges waiting for an answer")
            .buildWithCallback { it.record(inFlight().toLong()) }
    }

    /** Starts the CLIENT span of one exchange; it becomes a child of the current (HTTP) span. */
    fun start(id: MessageId, body: Body, priority: Boolean): Exchange {
        val operation = operationOf(body)
        val span = tracer.spanBuilder("DHL $operation")
            .setSpanKind(SpanKind.CLIENT)
            .setAttribute(PEER_SERVICE, "dhl")
            .setAttribute(SERVER_ADDRESS, properties.host)
            .setAttribute(SERVER_PORT, properties.port.toLong())
            .setAttribute(NETWORK_TRANSPORT, "udp")
            .setAttribute(NETWORK_PROTOCOL, "wpp")
            .setAttribute(OPERATION, operation)
            .setAttribute(MESSAGE_ID, id.value.toLong())
            .setAttribute(MESSAGE_TYPE, body.type.name.lowercase())
            .setAttribute(PRIORITY, priority.toString())
            .startSpan()
        return Exchange(span, operation, System.nanoTime())
    }

    fun dropped(reason: String) = dropped.add(1, Attributes.of(REASON, reason))

    inner class Exchange internal constructor(
        private val span: Span,
        private val operation: String,
        private val startNanos: Long,
    ) {
        fun retransmitting(attempt: Int) {
            span.addEvent("retransmission", Attributes.of(ATTEMPT, attempt.toLong()))
            retransmissions.add(1, Attributes.of(OPERATION, operation))
        }

        fun answered(answer: Message, attempts: Int) {
            val status = when (val body = answer.body) {
                is Response -> body.status.name.lowercase()
                is HandshakeAck -> body.status.name.lowercase()
                else -> "unexpected"
            }
            span.setAttribute(ATTEMPTS, attempts.toLong())
            span.setAttribute(RESPONSE_STATUS, status)
            val failed = (answer.body as? Response)?.status?.let { it != ResponseStatus.OK } ?: false
            if (failed) {
                span.setAttribute(ERROR_TYPE, status)
                span.setStatus(StatusCode.ERROR, "DHL answered $status")
            }
            end(outcome = if (failed) status else "ok")
        }

        fun timedOut(attempts: Int) {
            span.setAttribute(ATTEMPTS, attempts.toLong())
            span.setAttribute(ERROR_TYPE, "timeout")
            span.setStatus(StatusCode.ERROR, "no answer after $attempts attempts")
            end(outcome = "timeout")
        }

        private fun end(outcome: String) {
            duration.record(
                (System.nanoTime() - startNanos) / 1e9,
                Attributes.of(OPERATION, operation, OUTCOME, outcome),
            )
            span.end()
        }
    }

    private fun operationOf(body: Body) = when (body) {
        is Handshake -> "handshake"
        is Request -> body.operation.name.lowercase()
        else -> body.type.name.lowercase()
    }

    private companion object {
        const val SCOPE = "dev.waylo.gateway.dhl"
        val PEER_SERVICE: AttributeKey<String> = stringKey("peer.service")
        val SERVER_ADDRESS: AttributeKey<String> = stringKey("server.address")
        val SERVER_PORT: AttributeKey<Long> = longKey("server.port")
        val NETWORK_TRANSPORT: AttributeKey<String> = stringKey("network.transport")
        val NETWORK_PROTOCOL: AttributeKey<String> = stringKey("network.protocol.name")
        val ERROR_TYPE: AttributeKey<String> = stringKey("error.type")
        val OPERATION: AttributeKey<String> = stringKey("dhl.operation")
        val MESSAGE_ID: AttributeKey<Long> = longKey("dhl.message.id")
        val MESSAGE_TYPE: AttributeKey<String> = stringKey("dhl.message.type")
        val PRIORITY: AttributeKey<String> = stringKey("dhl.priority")
        val ATTEMPTS: AttributeKey<Long> = longKey("dhl.attempts")
        val ATTEMPT: AttributeKey<Long> = longKey("dhl.attempt")
        val RESPONSE_STATUS: AttributeKey<String> = stringKey("dhl.response.status")
        val OUTCOME: AttributeKey<String> = stringKey("dhl.outcome")
        val REASON: AttributeKey<String> = stringKey("reason")
    }
}
