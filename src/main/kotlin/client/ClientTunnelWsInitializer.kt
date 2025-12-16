package client

import io.netty.channel.ChannelInitializer
import io.netty.channel.socket.SocketChannel
import io.netty.handler.codec.http.HttpClientCodec
import io.netty.handler.codec.http.HttpObjectAggregator
import io.netty.handler.codec.http.websocketx.WebSocketClientProtocolConfig
import io.netty.handler.codec.http.websocketx.WebSocketClientProtocolHandler
import io.netty.handler.ssl.SslContext
import java.net.URI

class ClientTunnelWsInitializer(
    private val sslContext: SslContext?,
    private val wsUri: URI,
    private val tunnelContext: ClientTunnelContext,
) : ChannelInitializer<SocketChannel>() {
    override fun initChannel(ch: SocketChannel) {
        if (sslContext != null) {
            ch.pipeline().addLast(sslContext.newHandler(ch.alloc(), wsUri.host, wsUri.port))
        }
        ch.pipeline().addLast(HttpClientCodec())
        ch.pipeline().addLast(HttpObjectAggregator(65536))
        val wsConfig =
            WebSocketClientProtocolConfig.newBuilder()
                .webSocketUri(wsUri)
                .maxFramePayloadLength(1024 * 1024)
                .build()
        ch.pipeline().addLast(WebSocketClientProtocolHandler(wsConfig))
        ch.pipeline().addLast(ClientTunnelWsHandler(tunnelContext))
    }
}
