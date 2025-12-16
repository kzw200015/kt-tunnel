package agent

import io.netty.buffer.ByteBuf
import io.netty.channel.Channel
import io.netty.channel.ChannelHandlerContext
import io.netty.channel.ChannelInboundHandlerAdapter
import io.netty.handler.codec.http.websocketx.BinaryWebSocketFrame
import logger

class TargetToWsRelayHandler(private val tunnelContext: TunnelContext) : ChannelInboundHandlerAdapter() {
    private val log = logger<TargetToWsRelayHandler>()

    override fun channelRead(ctx: ChannelHandlerContext, msg: Any) {
        if (msg !is ByteBuf) {
            ctx.fireChannelRead(msg)
            return
        }
        try {
            val dataCh: Channel? = tunnelContext.dataWsCh
            if (dataCh == null || !dataCh.isActive) {
                return
            }
            dataCh.write(BinaryWebSocketFrame(msg.retain()))
        } finally {
            msg.release()
        }
    }

    override fun channelReadComplete(ctx: ChannelHandlerContext) {
        val dataCh = tunnelContext.dataWsCh
        dataCh?.flush()
        ctx.flush()
    }

    override fun channelInactive(ctx: ChannelHandlerContext) {
        tunnelContext.closeBoth("target_inactive")
    }

    override fun exceptionCaught(ctx: ChannelHandlerContext, cause: Throwable) {
        log.debug("target exception", cause)
        ctx.close()
    }
}
