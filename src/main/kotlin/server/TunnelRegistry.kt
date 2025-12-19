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

/**
 * server 侧隧道注册表：管理 pending/active 隧道并负责两端绑定与清理。
 *
 * 核心数据结构：
 * - `pending`：client OPEN 已收到，但尚未等到 agent DATA_BIND 的隧道
 * - `active`：client tunnel WS 与 agent data WS 已完成绑定的隧道
 *
 * 严格时序约束：
 * 1. client 建立 `/ws/client/tunnel` 后第一帧发送 `CLIENT_TUNNEL_OPEN`
 * 2. server 校验 token/agent 在线后，将该 tunnelId 记为 pending，并向 agent control 下发 `TUNNEL_CREATE`
 * 3. 只有当 agent 建立 `/ws/agent/data` 并发送 `AGENT_DATA_BIND` 成功后，server 才向 client 返回 `CLIENT_TUNNEL_OK`
 *
 * 关闭/清理策略：
 * - active 隧道：任一侧 channelInactive 都会关闭另一侧并从 `active` 移除
 * - pending 隧道：等待超时或失败时向 client 发送 ERR 并关闭
 */
class TunnelRegistry(
    private val token: String,
    pendingTimeout: Duration?,
    private val agentRegistry: AgentRegistry,
) {
    private val log = logger<TunnelRegistry>()
    private val pendingTimeout: Duration = pendingTimeout ?: Duration.ofSeconds(10)

    private val pending = ConcurrentHashMap<String, PendingTunnel>()
    private val active = ConcurrentHashMap<String, ActiveTunnel>()

    /** 取消 pending 的超时任务。 */
    private fun cancelTimeout(p: PendingTunnel) {
        p.timeoutTask?.cancel(false)
    }

    /**
     * 向 client 发送 `CLIENT_TUNNEL_ERR` 并关闭连接。
     *
     * @param clientCh client channel
     * @param tunnelId 隧道 ID
     * @param code 错误码
     * @param message 错误原因
     */
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

    /**
     * 向 agent data 发送 `AGENT_DATA_BIND_ERR` 并关闭连接。
     *
     * @param agentDataCh agent data channel
     * @param tunnelId 隧道 ID
     * @param code 错误码
     * @param message 错误原因
     */
    private fun sendAgentDataBindErrAndClose(agentDataCh: Channel, tunnelId: String, code: Int, message: String) {
        agentDataCh
            .writeAndFlush(
                TextWebSocketFrame(
                    JSON.toJSONString(Messages.AgentDataBindErr(MsgTypes.AGENT_DATA_BIND_ERR, tunnelId, code, message)),
                ),
            ).addListener { agentDataCh.close() }
    }

    /**
     * 获取 active 隧道（用于数据面转发）。
     *
     * @param tunnelId 隧道 ID
     * @return active 隧道；不存在则返回 null
     */
    fun getActive(tunnelId: String): ActiveTunnel? = active[tunnelId]

    /**
     * 处理 client 的 OPEN 请求。
     *
     * 该方法只在 client tunnel handler 收到第一帧 `CLIENT_TUNNEL_OPEN` 后调用。
     *
     * @param open OPEN 消息体
     * @param clientCh client tunnel WS channel
     */
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

    /**
     * 处理 agent 回报的创建失败（control 面）。
     *
     * @param err `TUNNEL_CREATE_ERR`
     */
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

    /**
     * 处理 agent data 的 bind（data 面第一帧，控制消息）。
     *
     * @param bind bind 消息体
     * @param agentDataCh agent data WS channel
     */
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

    /**
     * 在 client tunnel WS channelInactive 时调用，负责清理 pending/active 并关闭对端。
     *
     * @param clientCh client tunnel WS channel
     */
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

    /**
     * 在 agent data WS channelInactive 时调用，负责清理 active 并关闭对端。
     *
     * @param agentDataCh agent data WS channel
     */
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

    /**
     * pending 超时回调：移除 pending 并向 client 返回 ERR + 关闭。
     *
     * @param tunnelId 隧道 ID
     */
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

    /**
     * pending 隧道：client OPEN 已收到，等待 agent DATA_BIND 的状态。
     *
     * pending 在任一时刻只能存在一个同 tunnelId 记录。
     */
    data class PendingTunnel(
        /** tunnelId（UUID 字符串）。 */
        val tunnelId: String,
        /** agentId：该隧道期望绑定的 agent。 */
        val agentId: String,
        /** agent 侧要连接的目标主机。 */
        val targetHost: String,
        /** agent 侧要连接的目标端口。 */
        val targetPort: Int,
        /** client tunnel WS channel。 */
        val clientCh: Channel,
        /** pending 超时任务（触发后向 client 返回 HANDSHAKE_TIMEOUT）。 */
        val timeoutTask: ScheduledFuture<*>?,
        /** pending 创建时间（用于观测/排障）。 */
        val createdAt: Instant,
    )

    /**
     * 一个 active tunnel 对应两条 WS：
     * - clientCh：`/ws/client/tunnel`
     * - agentDataCh：`/ws/agent/data`
     *
     * server 只做 binary frame 原样转发，不解析内容。
     */
    data class ActiveTunnel(val tunnelId: String, val clientCh: Channel, val agentDataCh: Channel)

    companion object {
        /** 绑定在 client/agentData channel 上的属性键：tunnelId。 */
        private val ATTR_TUNNEL_ID: AttributeKey<String> = AttributeKey.valueOf("tunnelId")
        /** 绑定在 client channel 上的属性键：agentId（用于 debug/清理扩展）。 */
        private val ATTR_AGENT_ID: AttributeKey<String> = AttributeKey.valueOf("agentId")
    }
}
