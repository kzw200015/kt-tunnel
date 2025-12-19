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

/**
 * 本地 TCP listener 的 child pipeline 初始化器。
 *
 * 每当 client accept 一条本地连接（local socket）时，都会：
 * 1. 生成一个 tunnelId（UUID）
 * 2. 创建一条新的 client tunnel WebSocket 连接到 server
 * 3. 在本地 TCP 与 WS 二进制帧之间进行转发
 */
class LocalServerInitializer(
    /** client tunnel WebSocket URI（server 侧 endpoint）。 */
    private val wsUri: URI,
    /** 可选 TLS 上下文（wss）。 */
    private val sslContext: SslContext?,
    /** 共享 token（用于 OPEN）。 */
    private val token: String,
    /** 目标 agentId（用于 OPEN）。 */
    private val agentId: String,
    /** 当前 listener 对应的转发规则。 */
    private val forward: Forward,
) : ChannelInitializer<SocketChannel>() {
    private val log = logger<LocalServerInitializer>()

    /**
     * 初始化每条被 accept 的本地连接（local socket）的 pipeline。
     *
     * 会创建一个新的 tunnelId，并发起到 server 的 WS 连接。
     */
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
        // 等 server 回 CLIENT_TUNNEL_OK 才开始读取本地数据（严格时序，避免协议违规/丢数据）。
        ch.config().isAutoRead = false
        // 方向：local TCP -> WS。
        ch.pipeline().addLast(LocalInboundRelayHandler(tunnelContext))

        // WebSocket 连接复用本地连接的 eventLoop，减少线程切换。
        val loop: EventLoop = ch.eventLoop()
        val wsBootstrap =
            Bootstrap()
                .group(loop)
                .channel(NioSocketChannel::class.java)
                .option(ChannelOption.TCP_NODELAY, true)
                .handler(ClientTunnelWsInitializer(sslContext, wsUri, tunnelContext))

        // 建立到 server 的 client tunnel WS 连接。
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
