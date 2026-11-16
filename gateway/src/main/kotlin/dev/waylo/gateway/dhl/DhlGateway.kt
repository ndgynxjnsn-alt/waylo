package dev.waylo.gateway.dhl

import dev.waylo.gateway.domain.GatewayOutcome
import dev.waylo.gateway.domain.ParcelGateway
import dev.waylo.gateway.domain.ParcelQuery
import dev.waylo.protocol.MessageType
import dev.waylo.protocol.Request
import dev.waylo.protocol.Response
import dev.waylo.protocol.ResponseStatus
import org.slf4j.Logger
import org.slf4j.LoggerFactory

/** [ParcelGateway] backed by the DHL UDP protocol. */
class DhlGateway(
    private val connection: DhlConnection,
    private val session: DhlSession,
    private val properties: DhlProperties,
) : ParcelGateway {

    private val log: Logger = LoggerFactory.getLogger(javaClass)

    override fun execute(query: ParcelQuery): GatewayOutcome = execute(query, sessionRenewals = 1)

    private fun execute(query: ParcelQuery, sessionRenewals: Int): GatewayOutcome {
        val sessionId = session.await(properties.handshake.maxWait)
            ?: return GatewayOutcome.Unavailable("no DHL session established yet")

        val request: Request = try {
            DhlTranslator.toRequest(sessionId, query)
        } catch (e: IllegalArgumentException) {
            return GatewayOutcome.Rejected("request cannot be sent to DHL: ${e.message}")
        }

        val answer = when (val result = connection.exchange(request, MessageType.RESPONSE, query.priority)) {
            is DhlConnection.Result.TimedOut -> return GatewayOutcome.Timeout(result.attempts, properties.timeout)
            is DhlConnection.Result.Answered -> result.message.body as Response
        }

        return when (answer.status) {
            ResponseStatus.OK -> try {
                GatewayOutcome.Success(DhlTranslator.toResult(query, answer.records))
            } catch (e: UnexpectedAnswerException) {
                log.warn("unexpected DHL answer: {}", e.message)
                GatewayOutcome.Failed("unexpected DHL answer: ${e.message}")
            }
            ResponseStatus.INVALID_SESSION -> {
                session.invalidate(sessionId)
                if (sessionRenewals > 0) execute(query, sessionRenewals - 1)
                else GatewayOutcome.Unavailable("DHL keeps rejecting the session")
            }
            ResponseStatus.BAD_REQUEST -> GatewayOutcome.Rejected("DHL rejected the request")
            ResponseStatus.INTERNAL_ERROR -> GatewayOutcome.Failed("DHL answered INTERNAL_ERROR")
        }
    }
}
