package agent

import io.netty.channel.ChannelInitializer
import io.netty.channel.socket.SocketChannel

/**
 * Agent 侧 target TCP 连接的 pipeline 初始化器。
 *
 * target 侧使用纯 TCP 连接（不做任何协议解析），只负责将读取到的 ByteBuf
 * 转发到 data WS（BinaryWebSocketFrame）。
 */
class TargetInitializer(private val tunnelContext: TunnelContext) : ChannelInitializer<SocketChannel>() {
    /** 初始化 target TCP socket channel 的 pipeline。 */
    override fun initChannel(ch: SocketChannel) {
        ch.pipeline().addLast(TargetToWsRelayHandler(tunnelContext))
    }
}
