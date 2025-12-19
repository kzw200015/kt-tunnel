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

/**
 * Agent 端主程序：连接 server 并等待隧道创建指令。
 *
 * Agent 的连接模型：
 * - 与 server 维持一条 control WebSocket（`/ws/agent/control`）长连接
 * - 每条隧道额外建立一条 data WebSocket（`/ws/agent/data`）
 * - 每条隧道同时连接一个内网 target TCP
 *
 * 该实现不做多路复用：一个 tunnelId 独占一条 data WS 和一条 target TCP。
 */
class AgentApp(private val config: Config) {
    private val log = logger<AgentApp>()

    /**
     * 启动并阻塞运行，直到控制连接断开（或进程被外部终止）。
     *
     * 该方法会创建 Netty EventLoopGroup，并在 finally 中优雅关闭。
     */
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
        /** server 主机名/IP。 */
        val serverHost: String,
        /** server 端口。 */
        val serverPort: Int,
        /** 共享 token（MVP：静态密钥）。 */
        val token: String,
        /** agentId：用于 server 侧路由控制连接。 */
        val agentId: String,
        /** 是否启用 TLS（wss）。 */
        val tls: Boolean,
        /** 是否跳过 TLS 校验（不安全，仅开发）。 */
        val insecure: Boolean,
        /** 自定义 CA 证书（可为空）。 */
        val caFile: File?,
    )
}
