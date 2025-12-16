package client

import com.alibaba.fastjson2.JSON
import com.alibaba.fastjson2.JSONObject
import common.Messages
import common.MsgTypes
import common.Protocol
import io.netty.buffer.ByteBuf
import io.netty.channel.Channel
import io.netty.channel.ChannelHandlerContext
import io.netty.channel.SimpleChannelInboundHandler
import io.netty.handler.codec.http.websocketx.BinaryWebSocketFrame
import io.netty.handler.codec.http.websocketx.TextWebSocketFrame
import io.netty.handler.codec.http.websocketx.WebSocketClientProtocolHandler
import logger

class ClientTunnelWsHandler(private val tunnelContext: ClientTunnelContext) : SimpleChannelInboundHandler<Any>() {
    private val log = logger<ClientTunnelWsHandler>()

    override fun userEventTriggered(ctx: ChannelHandlerContext, evt: Any) {
        if (evt == WebSocketClientProtocolHandler.ClientHandshakeStateEvent.HANDSHAKE_COMPLETE) {
            tunnelContext.bindWs(ctx.channel())
            log.info(
                "client tunnel ws connected: tunnelId={}, serverRemote={}",
                tunnelContext.tunnelId,
                ctx.channel().remoteAddress()
            )

            val f = tunnelContext.forward
            ctx.writeAndFlush(
                TextWebSocketFrame(
                    JSON.toJSONString(
                        Messages.ClientTunnelOpen(
                            MsgTypes.CLIENT_TUNNEL_OPEN,
                            tunnelContext.tunnelId,
                            tunnelContext.agentId,
                            f.targetHost,
                            f.targetPort,
                            tunnelContext.token,
                        ),
                    ),
                ),
            )
        } else {
            ctx.fireUserEventTriggered(evt)
        }
    }

    override fun channelRead0(ctx: ChannelHandlerContext, msg: Any) {
        if (msg is TextWebSocketFrame) {
            val obj: JSONObject =
                try {
                    JSON.parseObject(msg.text())
                } catch (_: Exception) {
                    ctx.close()
                    return
                }

            when (Protocol.requireType(obj)) {
                MsgTypes.CLIENT_TUNNEL_OK -> {
                    tunnelContext.markReady()
                    val forward = tunnelContext.forward
                    log.info(
                        "tunnel ready (forwarding start): tunnelId={}, agentId={}, listen={}:{}, target={}:{}",
                        tunnelContext.tunnelId,
                        tunnelContext.agentId,
                        forward.listenHost,
                        forward.listenPort,
                        forward.targetHost,
                        forward.targetPort,
                    )
                    val localCh = tunnelContext.localCh
                    if (localCh != null && localCh.isActive) {
                        localCh.config().isAutoRead = true
                    }
                }

                MsgTypes.CLIENT_TUNNEL_ERR -> {
                    log.error("client tunnel err: {}", msg.text())
                    tunnelContext.closeBoth("server_err")
                }
            }
            return
        }

        if (msg is BinaryWebSocketFrame) {
            if (!tunnelContext.ready) {
                ctx.close()
                return
            }
            val data: ByteBuf = msg.content()
            val localCh: Channel? = tunnelContext.localCh
            if (localCh == null || !localCh.isActive) {
                ctx.close()
                return
            }
            localCh.write(data.retain())
        }
    }

    override fun channelReadComplete(ctx: ChannelHandlerContext) {
        val localCh = tunnelContext.localCh
        localCh?.flush()
        ctx.flush()
    }

    override fun channelInactive(ctx: ChannelHandlerContext) {
        tunnelContext.closeBoth("ws_inactive")
    }

    override fun exceptionCaught(ctx: ChannelHandlerContext, cause: Throwable) {
        log.debug("client ws exception", cause)
        ctx.close()
    }
}
