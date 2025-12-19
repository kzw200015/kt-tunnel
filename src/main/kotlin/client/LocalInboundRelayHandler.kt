package client

import io.netty.buffer.ByteBuf
import io.netty.channel.Channel
import io.netty.channel.ChannelHandlerContext
import io.netty.channel.SimpleChannelInboundHandler
import io.netty.handler.codec.http.websocketx.BinaryWebSocketFrame
import isIgnorableNettyIoException
import nettyIoExceptionSummary
import logger

/**
 * 本地 TCP -> client tunnel WS 的数据转发 handler。
 *
 * 透传规则：
 * - 只转发 [ByteBuf]（本地 TCP 收到的数据）
 * - 通过 [BinaryWebSocketFrame] 作为二进制帧写入 ws
 * - 未 ready（未收到 server 的 OK）时，认为协议未就绪，直接关闭本地连接
 */
class LocalInboundRelayHandler(private val tunnelContext: ClientTunnelContext) :
    SimpleChannelInboundHandler<ByteBuf>() {
    private val log = logger<LocalInboundRelayHandler>()

    /** 收到本地 TCP 入站数据：封装为二进制帧转发给 server。 */
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

    /** 读完成：flush WS，减少尾延迟。 */
    override fun channelReadComplete(ctx: ChannelHandlerContext) {
        val wsCh = tunnelContext.wsCh
        wsCh?.flush()
        ctx.flush()
    }

    /** 本地连接断开：关闭 WS 并清理。 */
    override fun channelInactive(ctx: ChannelHandlerContext) {
        tunnelContext.closeBoth("local_inactive")
    }

    /** 异常处理：忽略常见 IO 异常，其他情况记 debug 并关闭连接。 */
    override fun exceptionCaught(ctx: ChannelHandlerContext, cause: Throwable) {
        if (isIgnorableNettyIoException(cause)) {
            log.debug("local relay io exception: {}", nettyIoExceptionSummary(cause))
            ctx.close()
            return
        }
        log.debug("local relay unexpected exception", cause)
        ctx.close()
    }
}
