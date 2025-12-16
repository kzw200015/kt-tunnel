package agent

import io.netty.bootstrap.Bootstrap
import io.netty.channel.Channel
import io.netty.channel.ChannelOption
import io.netty.channel.EventLoopGroup
import io.netty.channel.MultiThreadIoEventLoopGroup
import io.netty.channel.nio.NioIoHandler
import io.netty.channel.socket.nio.NioSocketChannel
import io.netty.handler.ssl.SslContext
import logger
import tls.ClientSslContexts
import java.io.File
import java.net.URI

class AgentApp(private val config: Config) {
    private val log = logger<AgentApp>()

    fun runUntilShutdown() {
        val scheme = if (config.tls) "wss" else "ws"
        val controlUri = URI(scheme, null, config.serverHost, config.serverPort, "/ws/agent/control", null, null)
        val dataUri = URI(scheme, null, config.serverHost, config.serverPort, "/ws/agent/data", null, null)

        val sslContext: SslContext? = if (config.tls) ClientSslContexts.build(config.insecure, config.caFile) else null

        val group: EventLoopGroup = MultiThreadIoEventLoopGroup(NioIoHandler.newFactory())
        try {
            val bootstrap =
                Bootstrap()
                    .group(group)
                    .channel(NioSocketChannel::class.java)
                    .option(ChannelOption.TCP_NODELAY, true)

            val tunnelManager = AgentTunnelManager(group, sslContext, dataUri, config.agentId, config.token)

            val controlCh: Channel =
                bootstrap
                    .handler(
                        AgentControlClientInitializer(
                            sslContext,
                            controlUri,
                            config.agentId,
                            config.token,
                            tunnelManager
                        )
                    )
                    .connect(controlUri.host, controlUri.port)
                    .sync()
                    .channel()

            log.info("agent control connected: {} as agentId={}", controlUri, config.agentId)
            controlCh.closeFuture().sync()
            log.info("agent control disconnected: {} as agentId={}", controlUri, config.agentId)
        } finally {
            group.shutdownGracefully()
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
    )
}
