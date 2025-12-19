package client

import io.netty.channel.ChannelInitializer
import io.netty.channel.socket.SocketChannel
import logger

/**
 * 本地 TCP listener 的 child pipeline 初始化器。
 *
 * 每当 client accept 一条本地连接（local socket）时，都会：
 * 1. 生成一个 tunnelId（UUID）
 * 2. 创建一条新的 client tunnel WebSocket 连接到 server
 * 3. 在本地 TCP 与 WS 二进制帧之间进行转发
 */
class LocalServerInitializer(
    /** 隧道连接器（负责建立 WS 隧道）。 */
    private val tunnelConnector: ClientTunnelConnector,
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
        val tunnelContext = tunnelConnector.open(ch, forward)
        log.info(
            "local connection accepted: tunnelId={}, listen={}:{}, target={}:{}, remote={}",
            tunnelContext.tunnelId,
            forward.listenHost,
            forward.listenPort,
            forward.targetHost,
            forward.targetPort,
            ch.remoteAddress(),
        )
    }
}
