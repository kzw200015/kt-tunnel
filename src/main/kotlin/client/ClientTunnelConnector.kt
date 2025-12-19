package client

import io.netty.bootstrap.Bootstrap
import io.netty.channel.Channel
import io.netty.channel.ChannelOption
import io.netty.channel.EventLoop
import io.netty.channel.socket.nio.NioSocketChannel
import io.netty.handler.ssl.SslContext
import logger
import java.net.URI
import java.util.UUID

class ClientTunnelConnector(
    private val wsUri: URI,
    private val sslContext: SslContext?,
    private val token: String,
    private val agentId: String,
) {
    private val log = logger<ClientTunnelConnector>()

    fun open(localCh: Channel, forward: Forward, onReady: () -> Unit = {}): ClientTunnelContext {
        val tunnelId = UUID.randomUUID().toString()
        val tunnelContext = ClientTunnelContext(tunnelId, agentId, token, forward, onReady = onReady)
        tunnelContext.localCh = localCh
        localCh.config().isAutoRead = false
        localCh.pipeline().addLast(LocalInboundRelayHandler(tunnelContext))

        val loop: EventLoop = localCh.eventLoop()
        val wsBootstrap =
            Bootstrap()
                .group(loop)
                .channel(NioSocketChannel::class.java)
                .option(ChannelOption.TCP_NODELAY, true)
                .handler(ClientTunnelWsInitializer(sslContext, wsUri, tunnelContext))

        wsBootstrap.connect(wsUri.host, wsUri.port).addListener { f ->
            if (f.isSuccess) {
                return@addListener
            }
            log.error(
                "connect ws failed: tunnelId={}, server={}, cause={}",
                tunnelId,
                wsUri,
                f.cause()?.toString() ?: "unknown",
            )
            tunnelContext.closeBoth("ws_connect_failed")
        }
        return tunnelContext
    }
}
