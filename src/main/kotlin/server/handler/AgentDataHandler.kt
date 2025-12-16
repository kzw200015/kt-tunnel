package server.handler

import com.alibaba.fastjson2.JSON
import com.alibaba.fastjson2.JSONObject
import common.Messages
import common.MsgTypes
import common.Protocol
import io.netty.channel.Channel
import io.netty.channel.ChannelHandlerContext
import io.netty.channel.SimpleChannelInboundHandler
import io.netty.handler.codec.http.websocketx.BinaryWebSocketFrame
import io.netty.handler.codec.http.websocketx.TextWebSocketFrame
import io.netty.handler.codec.http.websocketx.WebSocketFrame
import logger
import server.TunnelRegistry

class AgentDataHandler(private val tunnelRegistry: TunnelRegistry) : SimpleChannelInboundHandler<WebSocketFrame>() {
    private val log = logger<AgentDataHandler>()

    private var bound = false
    private var tunnelId: String? = null

    override fun channelRead0(ctx: ChannelHandlerContext, frame: WebSocketFrame) {
        if (!bound) {
            if (frame !is TextWebSocketFrame) {
                ctx.close()
                return
            }

            val obj: JSONObject =
                try {
                    JSON.parseObject(frame.text())
                } catch (_: Exception) {
                    ctx.close()
                    return
                }

            val type = Protocol.requireType(obj)
            if (type != MsgTypes.AGENT_DATA_BIND) {
                ctx.close()
                return
            }

            val tunnelId = Protocol.requireTunnelId(obj)
            val agentId = Protocol.requireString(obj, "agentId", 128)
            val token = Protocol.requireString(obj, "token", 256)

            this.tunnelId = tunnelId
            log.info(
                "agent data ws bind received: tunnelId={}, agentId={}, remote={}",
                tunnelId,
                agentId,
                ctx.channel().remoteAddress()
            )
            tunnelRegistry.handleAgentDataBind(
                Messages.AgentDataBind(
                    MsgTypes.AGENT_DATA_BIND,
                    tunnelId,
                    agentId,
                    token
                ), ctx.channel()
            )
            bound = true
            return
        }

        if (frame is BinaryWebSocketFrame) {
            val tid = tunnelId
            if (tid == null) {
                ctx.close()
                return
            }
            val a = tunnelRegistry.getActive(tid)
            if (a == null) {
                ctx.close()
                return
            }
            val clientCh: Channel = a.clientCh
            if (!clientCh.isActive) {
                ctx.close()
                return
            }
            clientCh.write(BinaryWebSocketFrame(frame.isFinalFragment, frame.rsv(), frame.content().retain()))
            return
        }

        if (frame is TextWebSocketFrame) {
            return
        }

        log.debug("ignore agent data frame: {}", frame::class.java.simpleName)
    }

    override fun channelReadComplete(ctx: ChannelHandlerContext) {
        val tid = tunnelId
        if (bound && tid != null) {
            val a = tunnelRegistry.getActive(tid)
            if (a != null) {
                a.clientCh.flush()
            }
        }
        ctx.flush()
    }

    override fun channelInactive(ctx: ChannelHandlerContext) {
        tunnelRegistry.handleAgentDataChannelInactive(ctx.channel())
        log.info("agent data ws disconnected: tunnelId={}, remote={}", tunnelId, ctx.channel().remoteAddress())
    }

    override fun exceptionCaught(ctx: ChannelHandlerContext, cause: Throwable) {
        log.debug("agent data exception", cause)
        ctx.close()
    }
}
