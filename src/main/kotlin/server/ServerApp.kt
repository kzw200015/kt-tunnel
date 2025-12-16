package server

import io.netty.bootstrap.ServerBootstrap
import io.netty.channel.ChannelOption
import io.netty.channel.EventLoopGroup
import io.netty.channel.MultiThreadIoEventLoopGroup
import io.netty.channel.nio.NioIoHandler
import io.netty.channel.socket.nio.NioServerSocketChannel
import io.netty.handler.ssl.SslContext
import io.netty.handler.ssl.SslContextBuilder
import logger
import server.handler.WsServerInitializer
import java.io.File
import java.net.InetSocketAddress
import java.time.Duration

class ServerApp(private val config: Config) {
    private val log = logger<ServerApp>()

    private fun buildServerSslContext(certFile: File?, keyFile: File?): SslContext? {
        if (certFile == null && keyFile == null) {
            return null
        }
        if (certFile == null || keyFile == null) {
            throw IllegalArgumentException("both --cert and --key must be provided to enable TLS")
        }
        return SslContextBuilder.forServer(certFile, keyFile).build()
    }

    fun runUntilShutdown() {
        val sslContext = buildServerSslContext(config.certFile, config.keyFile)

        val agentRegistry = AgentRegistry()
        val tunnelRegistry = TunnelRegistry(config.token, config.pendingTimeout, agentRegistry)

        val bossGroup: EventLoopGroup = MultiThreadIoEventLoopGroup(1, NioIoHandler.newFactory())
        val workerGroup: EventLoopGroup = MultiThreadIoEventLoopGroup(NioIoHandler.newFactory())
        try {
            val bootstrap =
                ServerBootstrap()
                    .group(bossGroup, workerGroup)
                    .channel(NioServerSocketChannel::class.java)
                    .childHandler(WsServerInitializer(sslContext, config.token, tunnelRegistry, agentRegistry))
                    .childOption(ChannelOption.TCP_NODELAY, true)
                    .childOption(ChannelOption.SO_KEEPALIVE, true)

            val ch =
                bootstrap
                    .bind(InetSocketAddress(config.bindHost, config.port))
                    .sync()
                    .channel()

            log.info(
                "server listening on {}:{} ({})",
                config.bindHost,
                config.port,
                if (sslContext == null) "ws" else "wss",
            )
            ch.closeFuture().sync()
        } finally {
            bossGroup.shutdownGracefully()
            workerGroup.shutdownGracefully()
        }
    }

    data class Config(
        val bindHost: String,
        val port: Int,
        val token: String,
        val certFile: File?,
        val keyFile: File?,
        val pendingTimeout: Duration,
    )
}
