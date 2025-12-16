package agent

import io.netty.channel.ChannelInitializer
import io.netty.channel.socket.SocketChannel

class TargetInitializer(private val tunnelContext: TunnelContext) : ChannelInitializer<SocketChannel>() {
    override fun initChannel(ch: SocketChannel) {
        ch.pipeline().addLast(TargetToWsRelayHandler(tunnelContext))
    }
}
