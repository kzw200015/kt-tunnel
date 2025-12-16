package agent

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

class AgentDataClientHandler(private val tunnelContext: TunnelContext) : SimpleChannelInboundHandler<Any>() {
    private val log = logger<AgentDataClientHandler>()

    override fun userEventTriggered(ctx: ChannelHandlerContext, evt: Any) {
        if (evt == WebSocketClientProtocolHandler.ClientHandshakeStateEvent.HANDSHAKE_COMPLETE) {
            log.info(
                "agent data ws connected: tunnelId={}, remote={}",
                tunnelContext.tunnelId,
                ctx.channel().remoteAddress()
            )
            tunnelContext.dataWsCh = ctx.channel()
            ctx.writeAndFlush(
                TextWebSocketFrame(
                    JSON.toJSONString(
                        Messages.AgentDataBind(
                            MsgTypes.AGENT_DATA_BIND,
                            tunnelContext.tunnelId,
                            tunnelContext.agentId,
                            tunnelContext.token
                        )
                    ),
                ),
            )
            val targetCh = tunnelContext.targetCh
            if (targetCh != null && targetCh.isActive) {
                targetCh.config().isAutoRead = true
            }
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
                    return
                }
            when (Protocol.requireType(obj)) {
                MsgTypes.AGENT_DATA_BIND_ERR -> {
                    log.error("data bind err: {}", msg.text())
                    tunnelContext.closeBoth("data_bind_err")
                    ctx.close()
                }

                MsgTypes.AGENT_DATA_BIND_OK -> log.info(
                    "tunnel ready (forwarding start): tunnelId={}, agentId={}",
                    tunnelContext.tunnelId,
                    tunnelContext.agentId,
                )
            }
            return
        }

        if (msg is BinaryWebSocketFrame) {
            val data: ByteBuf = msg.content()
            val targetCh: Channel? = tunnelContext.targetCh
            if (targetCh == null || !targetCh.isActive) {
                ctx.close()
                return
            }
            targetCh.write(data.retain())
        }
    }

    override fun channelReadComplete(ctx: ChannelHandlerContext) {
        val targetCh = tunnelContext.targetCh
        if (targetCh != null) {
            targetCh.flush()
        }
        ctx.flush()
    }

    override fun channelInactive(ctx: ChannelHandlerContext) {
        tunnelContext.closeBoth("data_ws_inactive")
    }

    override fun exceptionCaught(ctx: ChannelHandlerContext, cause: Throwable) {
        log.debug("data exception", cause)
        ctx.close()
    }
}
