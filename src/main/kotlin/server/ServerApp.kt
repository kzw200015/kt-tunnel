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

/**
 * Server 端主程序：在公网监听并承载三端 WebSocket endpoint。
 *
 * Server 的职责：
 * - 维护在线 agent 的 control 连接（agentId -> Channel）
 * - 维护隧道状态（pending/active），并负责 client 与 agentData 的配对绑定
 * - 在两端之间转发 BinaryWebSocketFrame（透明转发，不解析 payload）
 */
class ServerApp(private val config: Config) {
    private val log = logger<ServerApp>()

    /**
     * 按 `--cert/--key` 构造 server 侧 [SslContext]。
     *
     * @param certFile PEM 证书文件（可为空）
     * @param keyFile PEM 私钥文件（可为空）
     * @return 启用 TLS 时返回 SslContext，否则返回 null
     */
    private fun buildServerSslContext(certFile: File?, keyFile: File?): SslContext? {
        if (certFile == null && keyFile == null) {
            return null
        }
        if (certFile == null || keyFile == null) {
            throw IllegalArgumentException("both --cert and --key must be provided to enable TLS")
        }
        return SslContextBuilder.forServer(certFile, keyFile).build()
    }

    /**
     * 启动并阻塞运行，直到 server channel 关闭（或进程被外部终止）。
     *
     * 该方法会创建 Netty boss/worker EventLoopGroup，并在 finally 中优雅关闭。
     */
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
        /** 监听地址。 */
        val bindHost: String,
        /** 监听端口。 */
        val port: Int,
        /** 共享 token（MVP：静态密钥）。 */
        val token: String,
        /** TLS 证书文件（PEM，可为空；为空则不启用 TLS）。 */
        val certFile: File?,
        /** TLS 私钥文件（PEM，可为空；为空则不启用 TLS）。 */
        val keyFile: File?,
        /** pending 隧道等待超时：client OPEN 后等待 agent DATA_BIND 的最大时长。 */
        val pendingTimeout: Duration,
    )
}
