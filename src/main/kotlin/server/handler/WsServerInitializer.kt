package server.handler

import io.netty.channel.ChannelInitializer
import io.netty.channel.socket.SocketChannel
import io.netty.handler.codec.http.HttpObjectAggregator
import io.netty.handler.codec.http.HttpServerCodec
import io.netty.handler.ssl.SslContext
import server.AgentRegistry
import server.TunnelRegistry

class WsServerInitializer(
    private val sslContext: SslContext?,
    private val token: String,
    private val tunnelRegistry: TunnelRegistry,
    private val agentRegistry: AgentRegistry,
) : ChannelInitializer<SocketChannel>() {
    override fun initChannel(ch: SocketChannel) {
        if (sslContext != null) {
            ch.pipeline().addLast(sslContext.newHandler(ch.alloc()))
        }
        ch.pipeline().addLast(HttpServerCodec())
        ch.pipeline().addLast(HttpObjectAggregator(65536))
        ch.pipeline().addLast(WsPathRouterHandler(token, tunnelRegistry, agentRegistry))
    }
}
