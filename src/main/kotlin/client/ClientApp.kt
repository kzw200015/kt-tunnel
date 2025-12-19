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

/**
 * Client 端主程序：在本地监听并为每个入站 TCP 连接创建隧道。
 *
 * 关键点：
 * - 每条 [Forward] 规则对应一个本地 TCP listener
 * - 每 accept 一条本地连接，就创建一条新的 client tunnel WebSocket 连接（无多路复用）
 * - 控制面严格时序：收到 server 的 `CLIENT_TUNNEL_OK` 后才开始读取/转发本地数据
 */
class ClientApp(private val config: Config) {
    private val log = logger<ClientApp>()

    /**
     * 启动并阻塞运行。
     *
     * 该方法会为每条 [Forward] / [Socks5Listen] 规则启动一个本地 listener，并保持进程不退出。
     */
    fun runUntilShutdown() {
        if (config.forwards.isEmpty() && config.socks5Listens.isEmpty()) {
            throw IllegalArgumentException("missing --forward or --socks5")
        }

        val scheme = if (config.tls) "wss" else "ws"
        val wsUri = URI(scheme, null, config.serverHost, config.serverPort, "/ws/client/tunnel", null, null)
        val sslContext: SslContext? = if (config.tls) ClientSslContexts.build(config.insecure, config.caFile) else null
        val tunnelConnector = ClientTunnelConnector(wsUri, sslContext, config.token, config.agentId)

        val bossGroup: EventLoopGroup = MultiThreadIoEventLoopGroup(1, NioIoHandler.newFactory())
        val workerGroup: EventLoopGroup = MultiThreadIoEventLoopGroup(NioIoHandler.newFactory())

        val listenersClosedLatch = CountDownLatch(config.forwards.size + config.socks5Listens.size)
        val channels = ArrayList<Channel>()
        try {
            for (forward in config.forwards) {
                val b =
                    ServerBootstrap()
                        .group(bossGroup, workerGroup)
                        .channel(NioServerSocketChannel::class.java)
                        .childOption(ChannelOption.TCP_NODELAY, true)
                        .childOption(ChannelOption.SO_KEEPALIVE, true)
                        .childHandler(LocalServerInitializer(tunnelConnector, forward))

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
            for (socks5 in config.socks5Listens) {
                val b =
                    ServerBootstrap()
                        .group(bossGroup, workerGroup)
                        .channel(NioServerSocketChannel::class.java)
                        .childOption(ChannelOption.TCP_NODELAY, true)
                        .childOption(ChannelOption.SO_KEEPALIVE, true)
                        .childHandler(
                            Socks5ServerInitializer(
                                tunnelConnector,
                                socks5,
                            ),
                        )

                val channel = b.bind(InetSocketAddress(socks5.listenHost, socks5.listenPort)).sync().channel()
                channel.closeFuture().addListener { listenersClosedLatch.countDown() }
                log.info("socks5 listening on {}:{}", socks5.listenHost, socks5.listenPort)
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
        /** server 主机名/IP。 */
        val serverHost: String,
        /** server 端口。 */
        val serverPort: Int,
        /** 共享 token。 */
        val token: String,
        /** 目标 agentId（server 用于路由控制连接）。 */
        val agentId: String,
        /** 是否启用 TLS（wss）。 */
        val tls: Boolean,
        /** 是否跳过 TLS 校验（不安全，仅开发）。 */
        val insecure: Boolean,
        /** 自定义 CA 证书（可为空）。 */
        val caFile: File?,
        /** 转发规则列表（可为空）。 */
        val forwards: List<Forward>,
        /** SOCKS5 监听列表（可为空）。 */
        val socks5Listens: List<Socks5Listen>,
    )
}
