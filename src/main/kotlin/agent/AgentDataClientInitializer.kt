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
 * Agent 数据面连接的 pipeline 初始化器。
 *
 * 每条隧道都会创建一条 data WS 连接；该初始化器负责安装 WebSocket 客户端处理器与
 * [AgentDataClientHandler]（绑定 tunnelId，并透传二进制数据）。
 */
class AgentDataClientInitializer(
    /** 可选 TLS 上下文（wss）。 */
    private val sslContext: SslContext?,
    /** 数据面 WebSocket URI。 */
    private val dataUri: URI,
    /** 隧道上下文（保存 tunnelId、channel 引用等）。 */
    private val tunnelContext: TunnelContext,
) : ChannelInitializer<SocketChannel>() {
    /** 初始化 agent 数据面 socket channel 的 pipeline。 */
    override fun initChannel(ch: SocketChannel) {
        if (sslContext != null) {
            ch.pipeline().addLast(sslContext.newHandler(ch.alloc(), dataUri.host, dataUri.port))
        }
        ch.pipeline().addLast(HttpClientCodec())
        ch.pipeline().addLast(HttpObjectAggregator(65536))
        val wsConfig =
            WebSocketClientProtocolConfig.newBuilder()
                .webSocketUri(dataUri)
                .maxFramePayloadLength(1024 * 1024)
                .build()
        ch.pipeline().addLast(WebSocketClientProtocolHandler(wsConfig))
        ch.pipeline().addLast(AgentDataClientHandler(tunnelContext))
    }
}
