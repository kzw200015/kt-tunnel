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

class ClientTunnelHandler(private val tunnelRegistry: TunnelRegistry) : SimpleChannelInboundHandler<WebSocketFrame>() {
    private val log = logger<ClientTunnelHandler>()

    private var opened = false
    private var tunnelId: String? = null

    override fun channelRead0(ctx: ChannelHandlerContext, frame: WebSocketFrame) {
        if (!opened) {
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
            if (type != MsgTypes.CLIENT_TUNNEL_OPEN) {
                ctx.close()
                return
            }

            val tunnelId = Protocol.requireTunnelId(obj)
            val agentId = Protocol.requireString(obj, "agentId", 128)
            val targetHost = Protocol.requireString(obj, "targetHost", 255)
            val targetPort = Protocol.requirePort(obj, "targetPort")
            val token = Protocol.requireString(obj, "token", 256)

            this.tunnelId = tunnelId
            opened = true
            log.info(
                "client tunnel ws open received: tunnelId={}, agentId={}, target={}:{}, remote={}",
                tunnelId,
                agentId,
                targetHost,
                targetPort,
                ctx.channel().remoteAddress(),
            )
            tunnelRegistry.handleClientOpen(
                Messages.ClientTunnelOpen(
                    MsgTypes.CLIENT_TUNNEL_OPEN,
                    tunnelId,
                    agentId,
                    targetHost,
                    targetPort,
                    token
                ),
                ctx.channel(),
            )
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
            val agentDataCh: Channel = a.agentDataCh
            if (!agentDataCh.isActive) {
                ctx.close()
                return
            }
            agentDataCh.write(BinaryWebSocketFrame(frame.isFinalFragment, frame.rsv(), frame.content().retain()))
            return
        }

        if (frame is TextWebSocketFrame) {
            return
        }

        log.debug("ignore client tunnel frame: {}", frame::class.java.simpleName)
    }

    override fun channelReadComplete(ctx: ChannelHandlerContext) {
        val tid = tunnelId
        if (opened && tid != null) {
            val a = tunnelRegistry.getActive(tid)
            if (a != null) {
                a.agentDataCh.flush()
            }
        }
        ctx.flush()
    }

    override fun channelInactive(ctx: ChannelHandlerContext) {
        tunnelRegistry.handleClientChannelInactive(ctx.channel())
        log.info("client tunnel ws disconnected: tunnelId={}, remote={}", tunnelId, ctx.channel().remoteAddress())
    }

    override fun exceptionCaught(ctx: ChannelHandlerContext, cause: Throwable) {
        log.debug("client tunnel exception", cause)
        ctx.close()
    }
}
