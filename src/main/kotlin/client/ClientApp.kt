package client

import io.netty.bootstrap.ServerBootstrap
import io.netty.channel.Channel
import io.netty.channel.ChannelOption
import io.netty.channel.EventLoopGroup
import io.netty.channel.MultiThreadIoEventLoopGroup
import io.netty.channel.nio.NioIoHandler
import io.netty.channel.socket.nio.NioServerSocketChannel
import io.netty.handler.ssl.SslContext
import logger
import tls.ClientSslContexts
import java.io.File
import java.net.InetSocketAddress
import java.net.URI
import java.util.concurrent.CountDownLatch

class ClientApp(private val config: Config) {
    private val log = logger<ClientApp>()

    fun runUntilShutdown() {
        if (config.forwards.isEmpty()) {
            throw IllegalArgumentException("missing --forward")
        }

        val scheme = if (config.tls) "wss" else "ws"
        val wsUri = URI(scheme, null, config.serverHost, config.serverPort, "/ws/client/tunnel", null, null)
        val sslContext: SslContext? = if (config.tls) ClientSslContexts.build(config.insecure, config.caFile) else null

        val bossGroup: EventLoopGroup = MultiThreadIoEventLoopGroup(1, NioIoHandler.newFactory())
        val workerGroup: EventLoopGroup = MultiThreadIoEventLoopGroup(NioIoHandler.newFactory())

        val listenersClosedLatch = CountDownLatch(config.forwards.size)
        val channels = ArrayList<Channel>()
        try {
            for (forward in config.forwards) {
                val b =
                    ServerBootstrap()
                        .group(bossGroup, workerGroup)
                        .channel(NioServerSocketChannel::class.java)
                        .childOption(ChannelOption.TCP_NODELAY, true)
                        .childOption(ChannelOption.SO_KEEPALIVE, true)
                        .childHandler(LocalServerInitializer(wsUri, sslContext, config.token, config.agentId, forward))

                val channel = b.bind(InetSocketAddress(forward.listenHost, forward.listenPort)).sync().channel()
                channel.closeFuture().addListener { listenersClosedLatch.countDown() }
                log.info(
                    "forward listening on {}:{} -> {}:{}",
                    forward.listenHost,
                    forward.listenPort,
                    forward.targetHost,
                    forward.targetPort,
                )
                channels.add(channel)
            }
            Runtime.getRuntime().addShutdownHook(
                Thread {
                    for (ch in channels) {
                        ch.close()
                    }
                },
            )
            listenersClosedLatch.await()
        } finally {
            bossGroup.shutdownGracefully()
            workerGroup.shutdownGracefully()
        }
    }

    data class Config(
        val serverHost: String,
        val serverPort: Int,
        val token: String,
        val agentId: String,
        val tls: Boolean,
        val insecure: Boolean,
        val caFile: File?,
        val forwards: List<Forward>,
    )
}
