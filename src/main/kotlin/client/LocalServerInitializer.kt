package client

import io.netty.bootstrap.Bootstrap
import io.netty.channel.ChannelInitializer
import io.netty.channel.ChannelOption
import io.netty.channel.EventLoop
import io.netty.channel.socket.SocketChannel
import io.netty.channel.socket.nio.NioSocketChannel
import io.netty.handler.ssl.SslContext
import logger
import java.net.URI
import java.util.*

class LocalServerInitializer(
    private val wsUri: URI,
    private val sslContext: SslContext?,
    private val token: String,
    private val agentId: String,
    private val forward: Forward,
) : ChannelInitializer<SocketChannel>() {
    private val log = logger<LocalServerInitializer>()

    override fun initChannel(ch: SocketChannel) {
        val tunnelId = UUID.randomUUID().toString()
        log.info(
            "local connection accepted: tunnelId={}, listen={}:{}, target={}:{}, remote={}",
            tunnelId,
            forward.listenHost,
            forward.listenPort,
            forward.targetHost,
            forward.targetPort,
            ch.remoteAddress(),
        )

        val tunnelContext = ClientTunnelContext(tunnelId, agentId, token, forward) {}
        tunnelContext.bindLocal(ch)
        ch.config().isAutoRead = false
        ch.pipeline().addLast(LocalInboundRelayHandler(tunnelContext))

        val loop: EventLoop = ch.eventLoop()
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
                f.cause()?.toString() ?: "unknown"
            )
            tunnelContext.closeBoth("ws_connect_failed")
        }
    }
}
