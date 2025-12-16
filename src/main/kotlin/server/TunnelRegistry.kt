package server

import com.alibaba.fastjson2.JSON
import common.Messages
import common.MsgTypes
import common.Protocol
import io.netty.channel.Channel
import io.netty.handler.codec.http.websocketx.TextWebSocketFrame
import io.netty.util.AttributeKey
import io.netty.util.concurrent.ScheduledFuture
import io.netty.util.internal.StringUtil
import logger
import java.time.Duration
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

class TunnelRegistry(
    private val token: String,
    pendingTimeout: Duration?,
    private val agentRegistry: AgentRegistry,
) {
    private val log = logger<TunnelRegistry>()
    private val pendingTimeout: Duration = pendingTimeout ?: Duration.ofSeconds(10)

    private val pending = ConcurrentHashMap<String, PendingTunnel>()
    private val active = ConcurrentHashMap<String, ActiveTunnel>()

    private fun cancelTimeout(p: PendingTunnel) {
        p.timeoutTask?.cancel(false)
    }

    private fun sendClientErrAndClose(clientCh: Channel?, tunnelId: String, code: Int, message: String) {
        if (clientCh == null) {
            return
        }
        clientCh
            .writeAndFlush(
                TextWebSocketFrame(
                    JSON.toJSONString(Messages.ClientTunnelErr(MsgTypes.CLIENT_TUNNEL_ERR, tunnelId, code, message)),
                ),
            ).addListener { clientCh.close() }
    }

    private fun sendAgentDataBindErrAndClose(agentDataCh: Channel, tunnelId: String, code: Int, message: String) {
        agentDataCh
            .writeAndFlush(
                TextWebSocketFrame(
                    JSON.toJSONString(Messages.AgentDataBindErr(MsgTypes.AGENT_DATA_BIND_ERR, tunnelId, code, message)),
                ),
            ).addListener { agentDataCh.close() }
    }

    fun getActive(tunnelId: String): ActiveTunnel? = active[tunnelId]

    fun handleClientOpen(open: Messages.ClientTunnelOpen, clientCh: Channel) {
        log.info(
            "client tunnel open: tunnelId={}, agentId={}, target={}:{}, remote={}",
            open.tunnelId,
            open.agentId,
            open.targetHost,
            open.targetPort,
            clientCh.remoteAddress(),
        )

        if (token != open.token) {
            log.warn(
                "client tunnel rejected (bad token): tunnelId={}, remote={}",
                open.tunnelId,
                clientCh.remoteAddress()
            )
            sendClientErrAndClose(clientCh, open.tunnelId, Protocol.CODE_BAD_TOKEN, Protocol.MSG_BAD_TOKEN)
            return
        }

        if (StringUtil.isNullOrEmpty(open.agentId)) {
            log.warn(
                "client tunnel rejected (missing agentId): tunnelId={}, remote={}",
                open.tunnelId,
                clientCh.remoteAddress()
            )
            sendClientErrAndClose(clientCh, open.tunnelId, Protocol.CODE_NOT_FOUND, Protocol.MSG_AGENT_OFFLINE)
            return
        }

        val agentControl = agentRegistry.get(open.agentId)
        if (agentControl == null || !agentControl.channel.isActive) {
            log.warn(
                "client tunnel rejected (agent offline): tunnelId={}, agentId={}, remote={}",
                open.tunnelId,
                open.agentId,
                clientCh.remoteAddress(),
            )
            sendClientErrAndClose(clientCh, open.tunnelId, Protocol.CODE_NOT_FOUND, Protocol.MSG_AGENT_OFFLINE)
            return
        }

        if (active.containsKey(open.tunnelId)) {
            log.warn(
                "client tunnel rejected (duplicate active tunnelId): tunnelId={}, remote={}",
                open.tunnelId,
                clientCh.remoteAddress(),
            )
            sendClientErrAndClose(clientCh, open.tunnelId, Protocol.CODE_DUPLICATE, Protocol.MSG_DUPLICATE_TUNNEL_ID)
            return
        }

        clientCh.attr(ATTR_TUNNEL_ID).set(open.tunnelId)
        clientCh.attr(ATTR_AGENT_ID).set(open.agentId)

        val timeoutTask =
            clientCh.eventLoop().schedule(
                { onPendingTimeout(open.tunnelId) },
                pendingTimeout.toMillis(),
                TimeUnit.MILLISECONDS,
            )

        val p =
            PendingTunnel(
                tunnelId = open.tunnelId,
                agentId = open.agentId,
                targetHost = open.targetHost,
                targetPort = open.targetPort,
                clientCh = clientCh,
                timeoutTask = timeoutTask,
                createdAt = Instant.now(),
            )

        val prev = pending.putIfAbsent(open.tunnelId, p)
        if (prev != null) {
            cancelTimeout(p)
            log.warn(
                "client tunnel rejected (duplicate pending tunnelId): tunnelId={}, remote={}",
                open.tunnelId,
                clientCh.remoteAddress()
            )
            sendClientErrAndClose(clientCh, open.tunnelId, Protocol.CODE_DUPLICATE, Protocol.MSG_DUPLICATE_TUNNEL_ID)
            return
        }

        log.info(
            "tunnel create requested: tunnelId={}, agentId={}, target={}:{}",
            open.tunnelId,
            open.agentId,
            open.targetHost,
            open.targetPort,
        )

        val create = Messages.TunnelCreate(MsgTypes.TUNNEL_CREATE, open.tunnelId, open.targetHost, open.targetPort)
        agentControl.channel
            .writeAndFlush(TextWebSocketFrame(JSON.toJSONString(create)))
            .addListener { f ->
                if (f.isSuccess) {
                    return@addListener
                }
                log.warn("tunnel create send failed: tunnelId={}, agentId={}", open.tunnelId, open.agentId, f.cause())
                val removed = pending.remove(open.tunnelId)
                if (removed != null) {
                    cancelTimeout(removed)
                    sendClientErrAndClose(
                        removed.clientCh,
                        open.tunnelId,
                        Protocol.CODE_NOT_FOUND,
                        Protocol.MSG_AGENT_OFFLINE
                    )
                }
            }
    }

    fun handleAgentCreateErr(err: Messages.TunnelCreateErr) {
        val p = pending.remove(err.tunnelId) ?: return
        cancelTimeout(p)
        log.warn(
            "tunnel create failed: tunnelId={}, agentId={}, target={}:{}, code={}, message={}",
            err.tunnelId,
            p.agentId,
            p.targetHost,
            p.targetPort,
            err.code,
            err.message,
        )
        sendClientErrAndClose(p.clientCh, err.tunnelId, err.code, err.message)
    }

    fun handleAgentDataBind(bind: Messages.AgentDataBind, agentDataCh: Channel) {
        log.info(
            "agent data bind: tunnelId={}, agentId={}, remote={}",
            bind.tunnelId,
            bind.agentId,
            agentDataCh.remoteAddress()
        )

        if (token != bind.token) {
            log.warn(
                "agent data bind rejected (bad token): tunnelId={}, agentId={}, remote={}",
                bind.tunnelId,
                bind.agentId,
                agentDataCh.remoteAddress(),
            )
            sendAgentDataBindErrAndClose(agentDataCh, bind.tunnelId, Protocol.CODE_BAD_TOKEN, Protocol.MSG_BAD_TOKEN)
            return
        }

        val p = pending.remove(bind.tunnelId)
        if (p == null) {
            log.warn(
                "agent data bind rejected (no such tunnel): tunnelId={}, agentId={}, remote={}",
                bind.tunnelId,
                bind.agentId,
                agentDataCh.remoteAddress(),
            )
            sendAgentDataBindErrAndClose(
                agentDataCh,
                bind.tunnelId,
                Protocol.CODE_NOT_FOUND,
                Protocol.MSG_NO_SUCH_TUNNEL
            )
            return
        }

        if (p.agentId != bind.agentId) {
            cancelTimeout(p)
            log.warn(
                "agent data bind rejected (agent mismatch): tunnelId={}, expectedAgentId={}, actualAgentId={}",
                bind.tunnelId,
                p.agentId,
                bind.agentId,
            )
            sendAgentDataBindErrAndClose(
                agentDataCh,
                bind.tunnelId,
                Protocol.CODE_AGENT_MISMATCH,
                Protocol.MSG_AGENT_MISMATCH
            )
            sendClientErrAndClose(p.clientCh, bind.tunnelId, Protocol.CODE_AGENT_MISMATCH, Protocol.MSG_AGENT_MISMATCH)
            return
        }

        cancelTimeout(p)

        p.clientCh.attr(ATTR_TUNNEL_ID).set(bind.tunnelId)
        agentDataCh.attr(ATTR_TUNNEL_ID).set(bind.tunnelId)

        val tunnel = ActiveTunnel(bind.tunnelId, p.clientCh, agentDataCh)
        val existing = active.putIfAbsent(bind.tunnelId, tunnel)
        if (existing != null) {
            log.warn("agent data bind rejected (duplicate active tunnelId): tunnelId={}", bind.tunnelId)
            sendAgentDataBindErrAndClose(
                agentDataCh,
                bind.tunnelId,
                Protocol.CODE_DUPLICATE,
                Protocol.MSG_DUPLICATE_TUNNEL_ID
            )
            sendClientErrAndClose(p.clientCh, bind.tunnelId, Protocol.CODE_DUPLICATE, Protocol.MSG_DUPLICATE_TUNNEL_ID)
            return
        }

        val handshakeMillis = Duration.between(p.createdAt, Instant.now()).toMillis()
        log.info(
            "tunnel active (forwarding start): tunnelId={}, agentId={}, target={}:{}, handshakeMs={}",
            bind.tunnelId,
            p.agentId,
            p.targetHost,
            p.targetPort,
            handshakeMillis,
        )

        p.clientCh.writeAndFlush(
            TextWebSocketFrame(
                JSON.toJSONString(
                    Messages.ClientTunnelOk(
                        MsgTypes.CLIENT_TUNNEL_OK,
                        bind.tunnelId
                    )
                )
            )
        )
        agentDataCh.writeAndFlush(
            TextWebSocketFrame(
                JSON.toJSONString(
                    Messages.AgentDataBindOk(
                        MsgTypes.AGENT_DATA_BIND_OK,
                        bind.tunnelId
                    )
                )
            )
        )
    }

    fun handleClientChannelInactive(clientCh: Channel) {
        val tunnelId = clientCh.attr(ATTR_TUNNEL_ID).get() ?: return
        val a = active.remove(tunnelId)
        if (a != null) {
            log.info(
                "tunnel inactive (client closed, forwarding end): tunnelId={}, clientRemote={}",
                tunnelId,
                clientCh.remoteAddress()
            )
            if (a.agentDataCh.isActive) {
                a.agentDataCh.close()
            }
            return
        }
        val p = pending.remove(tunnelId)
        if (p != null) {
            cancelTimeout(p)
            log.info(
                "pending tunnel canceled (client closed): tunnelId={}, clientRemote={}",
                tunnelId,
                clientCh.remoteAddress()
            )
        }
    }

    fun handleAgentDataChannelInactive(agentDataCh: Channel) {
        val tunnelId = agentDataCh.attr(ATTR_TUNNEL_ID).get() ?: return
        val a = active.remove(tunnelId)
        if (a != null) {
            log.info(
                "tunnel inactive (agentData closed, forwarding end): tunnelId={}, agentDataRemote={}",
                tunnelId,
                agentDataCh.remoteAddress(),
            )
            if (a.clientCh.isActive) {
                a.clientCh.close()
            }
        }
    }

    private fun onPendingTimeout(tunnelId: String) {
        val p = pending.remove(tunnelId) ?: return
        log.info(
            "pending tunnel timeout: tunnelId={}, agentId={}, target={}:{}",
            tunnelId,
            p.agentId,
            p.targetHost,
            p.targetPort
        )
        sendClientErrAndClose(p.clientCh, tunnelId, Protocol.CODE_HANDSHAKE_TIMEOUT, Protocol.MSG_HANDSHAKE_TIMEOUT)
    }

    data class PendingTunnel(
        val tunnelId: String,
        val agentId: String,
        val targetHost: String,
        val targetPort: Int,
        val clientCh: Channel,
        val timeoutTask: ScheduledFuture<*>?,
        val createdAt: Instant,
    )

    data class ActiveTunnel(val tunnelId: String, val clientCh: Channel, val agentDataCh: Channel)

    companion object {
        private val ATTR_TUNNEL_ID: AttributeKey<String> = AttributeKey.valueOf("tunnelId")
        private val ATTR_AGENT_ID: AttributeKey<String> = AttributeKey.valueOf("agentId")
    }
}
