package client

import io.netty.buffer.ByteBuf
import io.netty.channel.Channel
import io.netty.channel.ChannelHandlerContext
import io.netty.channel.SimpleChannelInboundHandler
import io.netty.handler.codec.http.websocketx.BinaryWebSocketFrame

class LocalInboundRelayHandler(private val tunnelContext: ClientTunnelContext) :
    SimpleChannelInboundHandler<ByteBuf>() {
    override fun channelRead0(ctx: ChannelHandlerContext, msg: ByteBuf) {
        if (!tunnelContext.ready) {
            ctx.close()
            return
        }
        val wsCh: Channel? = tunnelContext.wsCh
        if (wsCh == null || !wsCh.isActive) {
            ctx.close()
            return
        }
        wsCh.write(BinaryWebSocketFrame(msg.retain()))
    }

    override fun channelReadComplete(ctx: ChannelHandlerContext) {
        val wsCh = tunnelContext.wsCh
        wsCh?.flush()
        ctx.flush()
    }

    override fun channelInactive(ctx: ChannelHandlerContext) {
        tunnelContext.closeBoth("local_inactive")
    }
}
