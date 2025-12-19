package client

import io.netty.channel.ChannelInitializer
import io.netty.channel.socket.SocketChannel
import io.netty.handler.codec.http.HttpClientCodec
import io.netty.handler.codec.http.HttpObjectAggregator
import io.netty.handler.codec.http.websocketx.WebSocketClientProtocolConfig
import io.netty.handler.codec.http.websocketx.WebSocketClientProtocolHandler
import io.netty.handler.ssl.SslContext
import java.net.URI

/**
 * client tunnel WebSocket 连接的 pipeline 初始化器。
 *
 * 每条本地 TCP 连接都会创建一条 WS 连接到 server，本初始化器负责安装：
 * - 可选 TLS handler（wss）
 * - HTTP client codec + 聚合器
 * - WebSocket 协议 handler
 * - [ClientTunnelWsHandler]（发送 OPEN、等待 OK、透传数据）
 */
class ClientTunnelWsInitializer(
    /** 可选 TLS 上下文（wss）。 */
    private val sslContext: SslContext?,
    /** client tunnel WebSocket URI。 */
    private val wsUri: URI,
    /** 隧道上下文（保存 tunnelId / channel 引用 / ready 状态）。 */
    private val tunnelContext: ClientTunnelContext,
) : ChannelInitializer<SocketChannel>() {
    /** 初始化 client tunnel WS socket channel 的 pipeline。 */
    override fun initChannel(ch: SocketChannel) {
        if (sslContext != null) {
            ch.pipeline().addLast(sslContext.newHandler(ch.alloc(), wsUri.host, wsUri.port))
        }
        ch.pipeline().addLast(HttpClientCodec())
        ch.pipeline().addLast(HttpObjectAggregator(65536))
        val wsConfig =
            WebSocketClientProtocolConfig.newBuilder()
                .webSocketUri(wsUri)
                .maxFramePayloadLength(1024 * 1024)
                .build()
        ch.pipeline().addLast(WebSocketClientProtocolHandler(wsConfig))
        ch.pipeline().addLast(ClientTunnelWsHandler(tunnelContext))
    }
}
