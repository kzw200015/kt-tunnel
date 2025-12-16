package server.handler

import io.netty.channel.ChannelHandlerContext
import io.netty.channel.ChannelPipeline
import io.netty.channel.SimpleChannelInboundHandler
import io.netty.handler.codec.http.DefaultFullHttpResponse
import io.netty.handler.codec.http.FullHttpRequest
import io.netty.handler.codec.http.HttpHeaderNames.CONNECTION
import io.netty.handler.codec.http.HttpHeaderValues.CLOSE
import io.netty.handler.codec.http.HttpResponseStatus
import io.netty.handler.codec.http.HttpVersion
import io.netty.handler.codec.http.websocketx.WebSocketServerProtocolConfig
import io.netty.handler.codec.http.websocketx.WebSocketServerProtocolHandler
import logger
import server.AgentRegistry
import server.TunnelRegistry

class WsPathRouterHandler(
    private val token: String,
    private val tunnelRegistry: TunnelRegistry,
    private val agentRegistry: AgentRegistry,
) : SimpleChannelInboundHandler<FullHttpRequest>() {
    private val log = logger<WsPathRouterHandler>()

    private fun wsConfig(path: String): WebSocketServerProtocolConfig =
        WebSocketServerProtocolConfig.newBuilder()
            .websocketPath(path)
            .checkStartsWith(false)
            .handleCloseFrames(true)
            .dropPongFrames(true)
            .maxFramePayloadLength(1024 * 1024)
            .build()

    override fun channelRead0(ctx: ChannelHandlerContext, req: FullHttpRequest) {
        val uri = req.uri()
        val path =
            run {
                val q = uri.indexOf('?')
                if (q >= 0) uri.substring(0, q) else uri
            }

        val p: ChannelPipeline = ctx.pipeline()

        when (path) {
            WS_AGENT_CONTROL -> {
                p.addLast(WebSocketServerProtocolHandler(wsConfig(WS_AGENT_CONTROL)))
                p.addLast(AgentControlHandler(token, agentRegistry, tunnelRegistry))
            }

            WS_AGENT_DATA -> {
                p.addLast(WebSocketServerProtocolHandler(wsConfig(WS_AGENT_DATA)))
                p.addLast(AgentDataHandler(tunnelRegistry))
            }

            WS_CLIENT_TUNNEL -> {
                p.addLast(WebSocketServerProtocolHandler(wsConfig(WS_CLIENT_TUNNEL)))
                p.addLast(ClientTunnelHandler(tunnelRegistry))
            }

            else -> {
                val resp = DefaultFullHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.NOT_FOUND)
                resp.headers().set(CONNECTION, CLOSE)
                ctx.writeAndFlush(resp).addListener { ctx.close() }
                return
            }
        }

        p.remove(this)
        ctx.fireChannelRead(req.retain())
    }

    override fun exceptionCaught(ctx: ChannelHandlerContext, cause: Throwable) {
        log.debug("ws router exception", cause)
        ctx.close()
    }

    companion object {
        const val WS_AGENT_CONTROL = "/ws/agent/control"
        const val WS_AGENT_DATA = "/ws/agent/data"
        const val WS_CLIENT_TUNNEL = "/ws/client/tunnel"
    }
}
