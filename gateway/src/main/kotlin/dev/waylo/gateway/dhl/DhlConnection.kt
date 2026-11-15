package dev.waylo.gateway.dhl

import dev.waylo.protocol.Answer
import dev.waylo.protocol.Body
import dev.waylo.protocol.DecodeResult
import dev.waylo.protocol.Flags
import dev.waylo.protocol.Header
import dev.waylo.protocol.Message
import dev.waylo.protocol.MessageCodec
import dev.waylo.protocol.MessageId
import dev.waylo.protocol.MessageType
import java.io.IOException
import java.net.InetSocketAddress
import java.net.PortUnreachableException
import java.nio.ByteBuffer
import java.nio.channels.AsynchronousCloseException
import java.nio.channels.ClosedChannelException
import java.nio.channels.DatagramChannel
import java.time.Clock
import java.time.Duration
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicInteger
import kotlin.random.Random
import org.slf4j.Logger
import org.slf4j.LoggerFactory

/**
 * Request/answer exchanges over UDP with the DHL gateway.
 *
 * UDP has no notion of a reply, so every outgoing message gets a fresh [MessageId] and registers a
 * pending exchange. A single receiver thread decodes incoming datagrams and completes the pending
 * exchange whose id matches the answer's `answerFor` - provided the answer has the expected type.
 * Any number of exchanges can be in flight at once.
 *
 * Lost datagrams are handled by retransmitting the identical message (same id, `retransmission`
 * flag set) after [DhlProperties.timeout], up to [DhlProperties.retries] times.
 */
class DhlConnection(
    private val properties: DhlProperties,
    private val telemetry: DhlTelemetry = DhlTelemetry(properties),
    private val clock: Clock = Clock.systemUTC(),
) : AutoCloseable {

    sealed interface Result {
        data class Answered(val message: Message, val attempts: Int) : Result
        data class TimedOut(val attempts: Int) : Result
    }

    private class Pending(val expected: MessageType, val answer: CompletableFuture<Message> = CompletableFuture())

    private val log: Logger = LoggerFactory.getLogger(javaClass)
    private val remote = InetSocketAddress(properties.host, properties.port)
    private val channel: DatagramChannel = DatagramChannel.open().bind(null).connect(remote)
    private val pending = ConcurrentHashMap<MessageId, Pending>()
    private val nextId = AtomicInteger(Random.nextInt())
    private val receiver: Thread = Thread.ofPlatform().daemon().name("dhl-receiver").start(::receiveLoop)

    /** Number of exchanges currently waiting for an answer. */
    val inFlight: Int get() = pending.size

    init {
        telemetry.registerInFlightGauge { pending.size }
    }

    /**
     * Sends [body] and blocks until the matching answer of type [expect] arrives, retransmitting on
     * timeout. Safe to call from many threads concurrently (intended for virtual threads).
     */
    fun exchange(body: Body, expect: MessageType, priority: Boolean = false): Result {
        val id = MessageId(nextId.getAndIncrement().toUInt())
        val exchange = Pending(expect)
        val span = telemetry.start(id, body, priority)
        pending[id] = exchange
        try {
            for (attempt in 1..properties.attempts) {
                if (attempt > 1) span.retransmitting(attempt)
                val flags = Flags(retransmission = attempt > 1, priority = priority)
                send(Message(Header(id, clock.instant(), flags), body))
                try {
                    val answer = exchange.answer.get(properties.timeout.toNanos(), TimeUnit.NANOSECONDS)
                    span.answered(answer, attempt)
                    return Result.Answered(answer, attempt)
                } catch (_: TimeoutException) {
                    log.debug("no {} for #{} within {} (attempt {}/{})", expect, id, properties.timeout, attempt, properties.attempts)
                }
            }
            log.warn("{} #{} unanswered after {} attempts", body.type, id, properties.attempts)
            span.timedOut(properties.attempts)
            return Result.TimedOut(properties.attempts)
        } finally {
            pending.remove(id)
        }
    }

    private fun send(message: Message) {
        try {
            channel.write(ByteBuffer.wrap(MessageCodec.encode(message)))
        } catch (e: PortUnreachableException) {
            log.debug("DHL port unreachable while sending #{}", message.header.messageId) // treated as loss
        } catch (e: IOException) {
            log.warn("failed to send #{}: {}", message.header.messageId, e.toString()) // treated as loss
        }
    }

    private fun receiveLoop() {
        val buffer = ByteBuffer.allocate(65_535)
        while (channel.isOpen) {
            buffer.clear()
            try {
                channel.receive(buffer)
            } catch (_: AsynchronousCloseException) {
                break
            } catch (_: ClosedChannelException) {
                break
            } catch (_: PortUnreachableException) {
                continue // ICMP from an earlier send while DHL is down
            } catch (e: IOException) {
                log.warn("receive failed: {}", e.toString())
                continue
            }
            buffer.flip()
            dispatch(ByteArray(buffer.remaining()).also(buffer::get))
        }
    }

    private fun dispatch(datagram: ByteArray) {
        val message = when (val result = MessageCodec.decode(datagram)) {
            is DecodeResult.Success -> result.message
            is DecodeResult.Failure -> {
                telemetry.dropped("undecodable")
                return log.warn("dropping undecodable datagram: {}", result.error)
            }
        }
        val body = message.body as? Answer ?: run {
            telemetry.dropped("unsolicited")
            return log.warn("dropping unsolicited {} #{}", message.type, message.header.messageId)
        }
        val exchange = pending[body.answerFor]
        when {
            exchange == null -> {
                telemetry.dropped("late_or_duplicate")
                log.debug("late or duplicate answer for #{} dropped", body.answerFor)
            }
            exchange.expected != message.type -> {
                telemetry.dropped("type_mismatch")
                log.warn("answer for #{} has type {}, expected {}; dropped", body.answerFor, message.type, exchange.expected)
            }
            else -> exchange.answer.complete(message)
        }
    }

    override fun close() {
        channel.close()
        receiver.join(Duration.ofSeconds(1))
    }
}
