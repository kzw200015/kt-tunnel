package agent

import io.netty.channel.ChannelInitializer
import io.netty.channel.socket.SocketChannel
import io.netty.handler.codec.http.HttpClientCodec
import io.netty.handler.codec.http.HttpObjectAggregator
import io.netty.handler.codec.http.websocketx.WebSocketClientProtocolConfig
import io.netty.handler.codec.http.websocketx.WebSocketClientProtocolHandler
import io.netty.handler.ssl.SslContext
import java.net.URI

/**
 * Agent 控制面连接的 pipeline 初始化器。
 *
 * 负责在 socket channel 上安装：
 * - 可选 TLS handler（wss）
 * - HTTP client codec + 聚合器
 * - WebSocket 协议 handler
 * - [AgentControlClientHandler]
 */
class AgentControlClientInitializer(
    /** 可选 TLS 上下文（wss）。 */
    private val sslContext: SslContext?,
    /** 控制面 WebSocket URI（包含 path）。 */
    private val controlUri: URI,
    /** agentId：用于注册。 */
    private val agentId: String,
    /** token：用于注册。 */
    private val token: String,
    /** 隧道管理器：处理 `TUNNEL_CREATE`。 */
    private val tunnelManager: AgentTunnelManager,
) : ChannelInitializer<SocketChannel>() {
    /** 初始化 agent 控制面 socket channel 的 pipeline。 */
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
