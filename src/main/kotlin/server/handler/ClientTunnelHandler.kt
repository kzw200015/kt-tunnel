package server.handler

import common.Messages
import common.MsgTypes
import common.Protocol
import common.parseJsonObject
import io.netty.channel.Channel
import io.netty.channel.ChannelHandlerContext
import io.netty.channel.SimpleChannelInboundHandler
import io.netty.handler.codec.http.websocketx.BinaryWebSocketFrame
import io.netty.handler.codec.http.websocketx.TextWebSocketFrame
import io.netty.handler.codec.http.websocketx.WebSocketFrame
import isIgnorableNettyIoException
import kotlinx.serialization.json.JsonObject
import logger
import nettyIoExceptionSummary
import server.TunnelRegistry

/**
 * server 侧 client tunnel handler：处理 `/ws/client/tunnel`。
 *
 * client tunnel 的语义：
 * - 每条本地 TCP 连接对应一条独立的 WS 连接
 * - 第一帧必须是 Text(JSON)：`CLIENT_TUNNEL_OPEN`
 * - 此后只转发 BinaryWebSocketFrame，server 不解析 payload
 */
class ClientTunnelHandler(private val tunnelRegistry: TunnelRegistry) : SimpleChannelInboundHandler<WebSocketFrame>() {
    private val log = logger<ClientTunnelHandler>()

    /** 是否已成功接收并处理 OPEN（第一帧）。 */
    private var opened = false

    /** 当前 WS 连接所属的 tunnelId（OPEN 后确定）。 */
    private var tunnelId: String? = null

    /**
     * 处理来自 client tunnel WS 的帧：
     * - 第一帧 Text：OPEN
     * - 后续 Binary：透传到 agent data
     */
    override fun channelRead0(ctx: ChannelHandlerContext, frame: WebSocketFrame) {
        if (!opened) {
            if (frame !is TextWebSocketFrame) {
                ctx.close()
                return
            }
            val obj: JsonObject =
                try {
                    frame.text().parseJsonObject()
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

    /** 读完成：联动 flush 对端，减少尾延迟。 */
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

    /** client tunnel 断开：通知 registry 清理并关闭对端（若 active）。 */
    override fun channelInactive(ctx: ChannelHandlerContext) {
        tunnelRegistry.handleClientChannelInactive(ctx.channel())
        log.info("client tunnel ws disconnected: tunnelId={}, remote={}", tunnelId, ctx.channel().remoteAddress())
    }

    /** 异常处理：记录 debug 日志并关闭连接。 */
    override fun exceptionCaught(ctx: ChannelHandlerContext, cause: Throwable) {
        if (isIgnorableNettyIoException(cause)) {
            log.debug("client tunnel io exception: {}", nettyIoExceptionSummary(cause))
            ctx.close()
            return
        }
        log.debug("client tunnel unexpected exception", cause)
        ctx.close()
    }
}
