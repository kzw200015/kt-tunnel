package server.handler

import io.netty.channel.ChannelInitializer
import io.netty.channel.socket.SocketChannel
import io.netty.handler.codec.http.HttpObjectAggregator
import io.netty.handler.codec.http.HttpServerCodec
import io.netty.handler.ssl.SslContext
import server.AgentRegistry
import server.TunnelRegistry

/**
 * server 端入口 pipeline 初始化器。
 *
 * 该 initializer 安装 HTTP + WebSocket 升级所需 handler，并将升级前的请求交给
 * [WsPathRouterHandler] 按 path 分发到不同的 WS 处理链。
 */
class WsServerInitializer(
    /** 可选 TLS 上下文（wss）。 */
    private val sslContext: SslContext?,
    /** 共享 token（传给控制面 handler 用于鉴权）。 */
    private val token: String,
    /** 隧道注册表（管理 pending/active）。 */
    private val tunnelRegistry: TunnelRegistry,
    /** agent 注册表（管理 agent 控制连接）。 */
    private val agentRegistry: AgentRegistry,
) : ChannelInitializer<SocketChannel>() {
    /**
     * 初始化 server 侧入站 TCP 连接（socket）的 pipeline。
     *
     * 此处处理的连接还处于 HTTP 阶段，后续会根据 path 升级为不同 WebSocket endpoint。
     */
    override fun initChannel(ch: SocketChannel) {
        if (sslContext != null) {
            ch.pipeline().addLast(sslContext.newHandler(ch.alloc()))
        }
        ch.pipeline().addLast(HttpServerCodec())
        ch.pipeline().addLast(HttpObjectAggregator(65536))
        ch.pipeline().addLast(WsPathRouterHandler(token, tunnelRegistry, agentRegistry))
    }
}
