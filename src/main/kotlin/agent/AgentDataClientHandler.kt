package agent

import common.Messages
import common.MsgTypes
import common.Protocol
import common.parseJsonObject
import common.toJsonString
import io.netty.buffer.ByteBuf
import io.netty.channel.Channel
import io.netty.channel.ChannelHandlerContext
import io.netty.channel.SimpleChannelInboundHandler
import io.netty.handler.codec.http.websocketx.BinaryWebSocketFrame
import io.netty.handler.codec.http.websocketx.TextWebSocketFrame
import io.netty.handler.codec.http.websocketx.WebSocketClientProtocolHandler
import isIgnorableNettyIoException
import nettyIoExceptionSummary
import logger
import kotlinx.serialization.json.JsonObject

/**
 * Agent 端数据面 handler（client 侧）。
 *
 * 职责：
 * - 握手完成后发送第一帧 `AGENT_DATA_BIND` 将该 data WS 绑定到 tunnelId
 * - 收到 server 转发来的二进制帧：写入 target TCP
 * - 任一侧断开：关闭并触发上下文清理
 */
class AgentDataClientHandler(private val tunnelContext: TunnelContext) : SimpleChannelInboundHandler<Any>() {
    private val log = logger<AgentDataClientHandler>()

    /**
     * 处理 WebSocket client 协议事件。
     *
     * 在握手完成后会发送第一帧 `AGENT_DATA_BIND` 并打开 target 的 autoRead。
     */
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
                    Messages.AgentDataBind(
                        MsgTypes.AGENT_DATA_BIND,
                        tunnelContext.tunnelId,
                        tunnelContext.agentId,
                        tunnelContext.token
                    ).toJsonString(),
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

    /**
     * 处理 server -> agent 的消息：
     * - Text：控制面响应（当前仅处理 bind ok/err）
     * - Binary：透传数据，写回 target TCP
     */
    override fun channelRead0(ctx: ChannelHandlerContext, msg: Any) {
        if (msg is TextWebSocketFrame) {
            val obj: JsonObject =
                try {
                    msg.text().parseJsonObject()
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

    /** 读完成：联动 flush target/WS，尽量降低延迟。 */
    override fun channelReadComplete(ctx: ChannelHandlerContext) {
        val targetCh = tunnelContext.targetCh
        if (targetCh != null) {
            targetCh.flush()
        }
        ctx.flush()
    }

    /** WS 断开：关闭隧道两端并清理。 */
    override fun channelInactive(ctx: ChannelHandlerContext) {
        tunnelContext.closeBoth("data_ws_inactive")
    }

    /** 异常处理：记录 debug 日志并关闭连接。 */
    override fun exceptionCaught(ctx: ChannelHandlerContext, cause: Throwable) {
        if (isIgnorableNettyIoException(cause)) {
            log.debug("data io exception: {}", nettyIoExceptionSummary(cause))
            ctx.close()
            return
        }
        log.debug("data unexpected exception", cause)
        ctx.close()
    }
}
