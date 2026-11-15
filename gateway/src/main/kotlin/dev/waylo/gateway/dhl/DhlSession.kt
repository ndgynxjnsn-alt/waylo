package dev.waylo.gateway.dhl

import dev.waylo.protocol.Handshake
import dev.waylo.protocol.HandshakeAck
import dev.waylo.protocol.HandshakeStatus
import dev.waylo.protocol.MessageType
import dev.waylo.protocol.SessionId
import java.time.Duration
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicReference
import kotlin.random.Random
import kotlin.random.nextUInt
import org.slf4j.Logger
import org.slf4j.LoggerFactory

/**
 * Owns the DHL session. The first thing that happens on a connection is the handshake; until it
 * completes, [await] blocks callers (bounded by a timeout). Handshakes are retried forever with a
 * back-off. When DHL reports the session as invalid (e.g. after a DHL restart), [invalidate] starts
 * a new handshake.
 */
class DhlSession(
    private val connection: DhlConnection,
    private val properties: DhlProperties,
) : AutoCloseable {

    private val log: Logger = LoggerFactory.getLogger(javaClass)
    private val current = AtomicReference(CompletableFuture<SessionId>())
    @Volatile private var closed = false

    fun start() = handshakeInBackground(current.get())

    /** The established session, waiting at most [maxWait] for a pending handshake; `null` if none. */
    fun await(maxWait: Duration): SessionId? = try {
        current.get().get(maxWait.toNanos(), TimeUnit.NANOSECONDS)
    } catch (_: TimeoutException) {
        null
    }

    /** Drop [stale] and handshake again - unless someone else already replaced it. */
    fun invalidate(stale: SessionId) {
        val old = current.get()
        if (old.getNow(null) != stale) return
        val fresh = CompletableFuture<SessionId>()
        if (current.compareAndSet(old, fresh)) {
            log.info("DHL rejected session {}; handshaking again", stale)
            handshakeInBackground(fresh)
        }
    }

    private fun handshakeInBackground(target: CompletableFuture<SessionId>) {
        Thread.ofVirtual().name("dhl-handshake").start {
            while (!closed) {
                val session = handshakeOnce()
                if (session != null) {
                    log.info("DHL session {} established", session)
                    target.complete(session)
                    return@start
                }
                Thread.sleep(properties.handshake.retryBackoff)
            }
        }
    }

    private fun handshakeOnce(): SessionId? {
        val nonce = Random.nextUInt()
        val result = connection.exchange(Handshake(properties.handshake.clientName, nonce), MessageType.HANDSHAKE_ACK)
        val ack = (result as? DhlConnection.Result.Answered)?.message?.body as? HandshakeAck
        return when {
            ack == null -> null.also { log.warn("DHL handshake unanswered; retrying in {}", properties.handshake.retryBackoff) }
            ack.status != HandshakeStatus.ACCEPTED -> null.also { log.error("DHL rejected handshake: {}", ack.status) }
            ack.nonceEcho != nonce -> null.also { log.error("DHL handshake nonce mismatch") }
            else -> ack.sessionId
        }
    }

    override fun close() {
        closed = true
    }
}
