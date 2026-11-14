package dev.waylo.mock

import dev.waylo.protocol.Answer
import dev.waylo.protocol.DecodeResult
import dev.waylo.protocol.Flags
import dev.waylo.protocol.Handshake
import dev.waylo.protocol.HandshakeStatus
import dev.waylo.protocol.Header
import dev.waylo.protocol.HandshakeAck
import dev.waylo.protocol.Message
import dev.waylo.protocol.MessageCodec
import dev.waylo.protocol.MessageId
import dev.waylo.protocol.Request
import dev.waylo.protocol.Response
import dev.waylo.protocol.ResponseStatus
import dev.waylo.protocol.SessionId
import java.lang.System.Logger.Level
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetSocketAddress
import java.net.SocketAddress
import java.net.SocketException
import java.time.Clock
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlin.random.Random
import kotlin.random.nextUInt

/**
 * The pretend DHL gateway. Listens on UDP, answers handshakes with a fresh session and requests
 * with fake parcel data. Simulates loss and latency according to [config]; each datagram is handled
 * on its own virtual thread, so answers to concurrent requests come back out of order.
 */
class DhlMockServer(
    @Volatile var config: MockConfig,
    private val logic: DhlBusinessLogic = DhlBusinessLogic(),
    private val clock: Clock = Clock.systemUTC(),
    private val random: Random = Random.Default,
) : AutoCloseable {

    private val log = System.getLogger(DhlMockServer::class.java.name)
    private val socket = DatagramSocket(InetSocketAddress(config.port))
    private val sessions = ConcurrentHashMap.newKeySet<SessionId>()
    private val nextId = AtomicInteger(random.nextInt())
    private val receiver = Thread.ofVirtual().name("dhl-mock-receiver").unstarted(::receiveLoop)

    /** Every successfully decoded datagram that arrived, including ones then "lost". For tests. */
    val received: MutableList<Message> = CopyOnWriteArrayList()

    val port: Int get() = socket.localPort

    fun start(): DhlMockServer = apply {
        receiver.start()
        log.log(Level.INFO, "DHL mock listening on udp/{0} ({1})", port.toString(), config)
    }

    /** Forget all sessions, as if DHL had restarted. */
    fun invalidateSessions() = sessions.clear()

    override fun close() {
        socket.close()
        receiver.join()
    }

    private fun receiveLoop() {
        val buffer = ByteArray(65_535)
        while (!socket.isClosed) {
            val packet = DatagramPacket(buffer, buffer.size)
            try {
                socket.receive(packet)
            } catch (_: SocketException) {
                break // closed
            }
            val datagram = packet.data.copyOfRange(packet.offset, packet.offset + packet.length)
            val sender = packet.socketAddress
            Thread.ofVirtual().name("dhl-mock-handler").start { handle(datagram, sender) }
        }
    }

    private fun handle(datagram: ByteArray, sender: SocketAddress) {
        val message = when (val result = MessageCodec.decode(datagram)) {
            is DecodeResult.Success -> result.message
            is DecodeResult.Failure -> {
                log.log(Level.WARNING, "dropping undecodable datagram from {0}: {1}", sender, result.error)
                return
            }
        }
        received += message
        val cfg = config
        if (lost(cfg)) {
            log.log(Level.DEBUG, "simulated loss of incoming {0} #{1}", message.type, message.header.messageId)
            return
        }

        val answer = answer(message) ?: return
        if (!message.header.flags.priority) simulateLatency(cfg)
        if (lost(cfg)) {
            log.log(Level.DEBUG, "simulated loss of answer to #{0}", message.header.messageId)
            return
        }
        val bytes = MessageCodec.encode(answer)
        socket.send(DatagramPacket(bytes, bytes.size, sender))
    }

    private fun answer(message: Message): Message? {
        val inbound = message.header.messageId
        val body: Answer = when (val body = message.body) {
            is Handshake -> {
                val session = SessionId(random.nextUInt())
                sessions += session
                log.log(Level.INFO, "handshake from ''{0}'' -> session {1}", body.clientName, session)
                HandshakeAck(inbound, HandshakeStatus.ACCEPTED, session, body.nonce, SERVER_NAME)
            }
            is Request -> if (body.sessionId !in sessions) {
                Response(inbound, ResponseStatus.INVALID_SESSION, emptyList())
            } else {
                val outcome = logic.handle(body.operation, body.records)
                Response(inbound, outcome.status, outcome.records)
            }
            is HandshakeAck, is Response -> {
                log.log(Level.WARNING, "ignoring unexpected {0} #{1}", message.type, inbound)
                return null
            }
        }
        val error = (body as? Response)?.status?.let { it != ResponseStatus.OK } ?: false
        val flags = Flags(error = error, simulated = true)
        return Message(Header(MessageId(nextId.getAndIncrement().toUInt()), clock.instant(), flags), body)
    }

    private fun lost(cfg: MockConfig) = cfg.lossRate > 0 && random.nextDouble() < cfg.lossRate

    private fun simulateLatency(cfg: MockConfig) {
        val min = cfg.minDelay.toMillis()
        val max = cfg.maxDelay.toMillis()
        if (max > 0) Thread.sleep(if (max > min) random.nextLong(min, max + 1) else max)
    }

    private companion object {
        const val SERVER_NAME = "dhl-mock/1"
    }
}
