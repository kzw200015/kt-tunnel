package client

import io.netty.channel.ChannelInitializer
import io.netty.channel.socket.SocketChannel
import io.netty.handler.codec.socksx.v5.Socks5InitialRequestDecoder
import io.netty.handler.codec.socksx.v5.Socks5ServerEncoder
import logger

/**
 * Child pipeline initializer for local SOCKS5 listeners.
 *
 * Once a SOCKS5 connection finishes handshake and sends CONNECT,
 * a new client tunnel WebSocket connection is created.
 */
class Socks5ServerInitializer(
    private val tunnelConnector: ClientTunnelConnector,
    private val listen: Socks5Listen,
) : ChannelInitializer<SocketChannel>() {
    private val log = logger<Socks5ServerInitializer>()

    override fun initChannel(ch: SocketChannel) {
        log.info(
            "socks5 connection accepted: listen={}:{}, remote={}",
            listen.listenHost,
            listen.listenPort,
            ch.remoteAddress(),
        )
        ch.pipeline().addLast(Socks5ServerEncoder.DEFAULT)
        ch.pipeline().addLast(Socks5InitialRequestDecoder())
        ch.pipeline().addLast(Socks5ProxyHandler(tunnelConnector, listen))
    }
}
