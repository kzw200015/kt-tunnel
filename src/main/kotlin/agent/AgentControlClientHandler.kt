package agent

import common.*
import io.netty.channel.ChannelHandlerContext
import io.netty.channel.SimpleChannelInboundHandler
import io.netty.handler.codec.http.websocketx.TextWebSocketFrame
import io.netty.handler.codec.http.websocketx.WebSocketClientProtocolHandler
import isIgnorableNettyIoException
import io.netty.util.concurrent.ScheduledFuture
import kotlinx.serialization.json.JsonObject
import logger
import nettyIoExceptionSummary
import java.util.concurrent.TimeUnit

/**
 * Agent 端控制面 handler（client 侧）。
 *
 * 负责：
 * - WebSocket 握手完成后发送 `AGENT_REGISTER`
 * - 处理 server 下发的 `TUNNEL_CREATE` 指令，交给 [AgentTunnelManager] 创建隧道
 *
 * 该 handler 仅处理 TextWebSocketFrame（JSON）。
 */
class AgentControlClientHandler(
    /** 当前 agentId（用于注册消息）。 */
    private val agentId: String,
    /** 共享 token（用于注册消息）。 */
    private val token: String,
    /** 隧道管理器（收到创建指令时调用）。 */
    private val tunnelManager: AgentTunnelManager,
) : SimpleChannelInboundHandler<TextWebSocketFrame>() {
    private val log = logger<AgentControlClientHandler>()
    private var heartbeatTask: ScheduledFuture<*>? = null

    /**
     * 处理 WebSocket client 协议事件。
     *
     * 这里主要关注握手完成事件（HANDSHAKE_COMPLETE），用于触发注册消息发送。
     */
    override fun userEventTriggered(ctx: ChannelHandlerContext, evt: Any) {
        if (evt == WebSocketClientProtocolHandler.ClientHandshakeStateEvent.HANDSHAKE_COMPLETE) {
            log.info("agent control ws connected: remote={}", ctx.channel().remoteAddress())
            ctx.writeAndFlush(
                TextWebSocketFrame(
                    Messages.AgentRegister(
                        MsgTypes.AGENT_REGISTER,
                        agentId,
                        token
                    ).toJsonString()
                )
            )
            heartbeatTask?.cancel(false)
            heartbeatTask =
                ctx.executor().scheduleAtFixedRate(
                    {
                        ctx.writeAndFlush(
                            TextWebSocketFrame(
                                Messages.AgentHeartbeat(
                                    MsgTypes.AGENT_HEARTBEAT,
                                    System.currentTimeMillis(),
                                ).toJsonString()
                            )
                        )
                    },
                    30,
                    30,
                    TimeUnit.SECONDS
                )
        } else {
            ctx.fireUserEventTriggered(evt)
        }
    }

    /**
     * 处理来自 server 的控制面消息（Text JSON）。
     *
     * 支持消息类型：
     * - `AGENT_REGISTER_OK`/`AGENT_REGISTER_ERR`
     * - `TUNNEL_CREATE`
     */
    override fun channelRead0(ctx: ChannelHandlerContext, frame: TextWebSocketFrame) {
        val obj: JsonObject =
            try {
                frame.text().parseJsonObject()
            } catch (_: Exception) {
                ctx.close()
                return
            }

        when (Protocol.requireType(obj)) {
            MsgTypes.AGENT_REGISTER_OK -> log.info("agent register ok")
            MsgTypes.AGENT_REGISTER_ERR -> {
                log.error("agent register err: {}", frame.text())
                ctx.close()
            }

            MsgTypes.TUNNEL_CREATE -> onTunnelCreate(ctx, obj)
            else -> log.debug("ignore control msg type={}", Protocol.requireType(obj))
        }
    }

    /** 处理 `TUNNEL_CREATE`：提取 tunnelId/target，并交由 [AgentTunnelManager] 创建隧道。 */
    private fun onTunnelCreate(ctx: ChannelHandlerContext, obj: JsonObject) {
        val tunnelId: String
        val targetHost: String
        val targetPort: Int
        try {
            tunnelId = Protocol.requireTunnelId(obj)
            targetHost = Protocol.requireString(obj, "targetHost", 255)
            targetPort = Protocol.requirePort(obj, "targetPort")
        } catch (_: Exception) {
            return
        }

        log.info("tunnel create received: tunnelId={}, target={}:{}", tunnelId, targetHost, targetPort)
        tunnelManager.startTunnel(ctx.channel(), tunnelId, targetHost, targetPort)
    }

    /** 控制连接断开：记录一条 INFO 日志（重连策略不在本类中实现）。 */
    override fun channelInactive(ctx: ChannelHandlerContext) {
        heartbeatTask?.cancel(false)
        heartbeatTask = null
        log.info("agent control ws disconnected: remote={}", ctx.channel().remoteAddress())
    }

    /** 异常处理：记录 debug 日志并关闭连接。 */
    override fun exceptionCaught(ctx: ChannelHandlerContext, cause: Throwable) {
        if (isIgnorableNettyIoException(cause)) {
            log.debug("control io exception: {}", nettyIoExceptionSummary(cause))
            ctx.close()
            return
        }
        log.debug("control unexpected exception", cause)
        ctx.close()
    }
}
