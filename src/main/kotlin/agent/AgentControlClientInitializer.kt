package agent

import io.netty.channel.ChannelInitializer
import io.netty.channel.socket.SocketChannel
import io.netty.handler.codec.http.HttpClientCodec
import io.netty.handler.codec.http.HttpObjectAggregator
import io.netty.handler.codec.http.websocketx.WebSocketClientProtocolConfig
import io.netty.handler.codec.http.websocketx.WebSocketClientProtocolHandler
import io.netty.handler.ssl.SslContext
import java.net.URI

class AgentControlClientInitializer(
    private val sslContext: SslContext?,
    private val controlUri: URI,
    private val agentId: String,
    private val token: String,
    private val tunnelManager: AgentTunnelManager,
) : ChannelInitializer<SocketChannel>() {
    override fun initChannel(ch: SocketChannel) {
        if (sslContext != null) {
            ch.pipeline().addLast(sslContext.newHandler(ch.alloc(), controlUri.host, controlUri.port))
        }
        ch.pipeline().addLast(HttpClientCodec())
        ch.pipeline().addLast(HttpObjectAggregator(65536))
        val wsConfig =
            WebSocketClientProtocolConfig.newBuilder()
                .webSocketUri(controlUri)
                .maxFramePayloadLength(1024 * 1024)
                .build()
        ch.pipeline().addLast(WebSocketClientProtocolHandler(wsConfig))
        ch.pipeline().addLast(AgentControlClientHandler(agentId, token, tunnelManager))
    }
}
