package agent

import com.alibaba.fastjson2.JSON
import com.alibaba.fastjson2.JSONObject
import common.Messages
import common.MsgTypes
import common.Protocol
import io.netty.channel.ChannelHandlerContext
import io.netty.channel.SimpleChannelInboundHandler
import io.netty.handler.codec.http.websocketx.TextWebSocketFrame
import io.netty.handler.codec.http.websocketx.WebSocketClientProtocolHandler
import logger

class AgentControlClientHandler(
    private val agentId: String,
    private val token: String,
    private val tunnelManager: AgentTunnelManager,
) : SimpleChannelInboundHandler<TextWebSocketFrame>() {
    private val log = logger<AgentControlClientHandler>()

    override fun userEventTriggered(ctx: ChannelHandlerContext, evt: Any) {
        if (evt == WebSocketClientProtocolHandler.ClientHandshakeStateEvent.HANDSHAKE_COMPLETE) {
            log.info("agent control ws connected: remote={}", ctx.channel().remoteAddress())
            ctx.writeAndFlush(
                TextWebSocketFrame(
                    JSON.toJSONString(
                        Messages.AgentRegister(
                            MsgTypes.AGENT_REGISTER,
                            agentId,
                            token
                        )
                    )
                )
            )
        } else {
            ctx.fireUserEventTriggered(evt)
        }
    }

    override fun channelRead0(ctx: ChannelHandlerContext, frame: TextWebSocketFrame) {
        val obj: JSONObject =
            try {
                JSON.parseObject(frame.text())
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

    private fun onTunnelCreate(ctx: ChannelHandlerContext, obj: JSONObject) {
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

    override fun channelInactive(ctx: ChannelHandlerContext) {
        log.info("agent control ws disconnected: remote={}", ctx.channel().remoteAddress())
    }

    override fun exceptionCaught(ctx: ChannelHandlerContext, cause: Throwable) {
        log.debug("control exception", cause)
        ctx.close()
    }
}
