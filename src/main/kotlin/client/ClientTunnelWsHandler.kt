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

/**
 * Client 端 client tunnel WS handler（client 侧）。
 *
 * 职责：
 * - WS 握手完成后发送 `CLIENT_TUNNEL_OPEN`（第一帧必须是 Text JSON）
 * - 等待 server 返回 `CLIENT_TUNNEL_OK` 后才允许本地连接开始读取/转发
 * - 收到 server 转发来的二进制帧：写回本地 TCP
 */
class ClientTunnelWsHandler(private val tunnelContext: ClientTunnelContext) : SimpleChannelInboundHandler<Any>() {
    private val log = logger<ClientTunnelWsHandler>()

    /**
     * 处理 WebSocket client 协议事件。
     *
     * 在握手完成时发送 OPEN，并将 WS channel 绑定到上下文。
     */
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

    /**
     * 处理来自 server 的消息：
     * - Text：控制面 OK/ERR
     * - Binary：透传数据，写回本地 TCP
     */
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

    /** 读完成：flush 本地写入，减少尾延迟。 */
    override fun channelReadComplete(ctx: ChannelHandlerContext) {
        val localCh = tunnelContext.localCh
        localCh?.flush()
        ctx.flush()
    }

    /** WS 断开：关闭本地连接并清理。 */
    override fun channelInactive(ctx: ChannelHandlerContext) {
        tunnelContext.closeBoth("ws_inactive")
    }

    /** 异常处理：记录 debug 日志并关闭连接。 */
    override fun exceptionCaught(ctx: ChannelHandlerContext, cause: Throwable) {
        log.debug("client ws exception", cause)
        ctx.close()
    }
}
