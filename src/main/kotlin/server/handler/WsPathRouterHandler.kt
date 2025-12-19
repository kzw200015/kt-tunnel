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

/**
 * WebSocket 路由器：在 HTTP 升级前根据请求 path 选择不同的 WS 处理链。
 *
 * 设计动机：在同一个监听端口上提供多个 WebSocket endpoint，避免多端口部署复杂度。
 *
 * 本 handler 只处理升级前的 [FullHttpRequest]；一旦根据 path 安装好相应 handler，
 * 就会将自己从 pipeline 移除并向后转发当前请求。
 */
class WsPathRouterHandler(
    /** 共享 token（传递给控制面 handler）。 */
    private val token: String,
    /** 隧道注册表（传递给各数据面 handler）。 */
    private val tunnelRegistry: TunnelRegistry,
    /** agent 注册表（传递给控制面 handler）。 */
    private val agentRegistry: AgentRegistry,
) : SimpleChannelInboundHandler<FullHttpRequest>() {
    private val log = logger<WsPathRouterHandler>()

    /** 构造 WebSocket server 协议配置。 */
    private fun wsConfig(path: String): WebSocketServerProtocolConfig =
        WebSocketServerProtocolConfig.newBuilder()
            .websocketPath(path)
            .checkStartsWith(false)
            .handleCloseFrames(true)
            .dropPongFrames(true)
            .maxFramePayloadLength(1024 * 1024)
            .build()

    /**
     * 处理升级前的 HTTP 请求并按 path 路由。
     *
     * 路由完成后会移除自身并将请求向后传播，交由 [WebSocketServerProtocolHandler] 执行升级握手。
     */
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

    /** 异常处理：记录 debug 日志并关闭连接。 */
    override fun exceptionCaught(ctx: ChannelHandlerContext, cause: Throwable) {
        log.debug("ws router exception", cause)
        ctx.close()
    }

    companion object {
        /** agent 控制面 endpoint。 */
        const val WS_AGENT_CONTROL = "/ws/agent/control"
        /** agent 数据面 endpoint。 */
        const val WS_AGENT_DATA = "/ws/agent/data"
        /** client 隧道 endpoint。 */
        const val WS_CLIENT_TUNNEL = "/ws/client/tunnel"
    }
}
