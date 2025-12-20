package agent

import io.netty.buffer.ByteBuf
import io.netty.channel.Channel
import io.netty.channel.ChannelHandlerContext
import io.netty.channel.ChannelInboundHandlerAdapter
import io.netty.handler.codec.http.websocketx.BinaryWebSocketFrame
import isIgnorableNettyIoException
import logger
import nettyIoExceptionSummary

/**
 * target TCP -> agent data WS 的数据转发 handler。
 *
 * 该 handler 运行在 target TCP 的 pipeline 中：
 * - 读取到 [ByteBuf] 后，封装为 [BinaryWebSocketFrame] 写入 data WS
 * - 若 data WS 尚未建立或已断开，则丢弃读取到的数据（连接会在后续 closeBoth 中统一关闭）
 */
class TargetToWsRelayHandler(private val tunnelContext: TunnelContext) : ChannelInboundHandlerAdapter() {
    private val log = logger<TargetToWsRelayHandler>()

    /**
     * 收到 target TCP 数据：封装为 BinaryWebSocketFrame 写到 data WS。
     *
     * 注意：此处显式 retain + finally release，确保引用计数正确。
     */
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

    /** 读完成：flush data WS，减少尾延迟。 */
    override fun channelReadComplete(ctx: ChannelHandlerContext) {
        val dataCh = tunnelContext.dataWsCh
        dataCh?.flush()
        ctx.flush()
    }

    /** target TCP 断开：关闭隧道两端并清理。 */
    override fun channelInactive(ctx: ChannelHandlerContext) {
        tunnelContext.closeBoth("target_inactive")
    }

    /** 异常处理：记录 debug 日志并关闭连接。 */
    override fun exceptionCaught(ctx: ChannelHandlerContext, cause: Throwable) {
        if (isIgnorableNettyIoException(cause)) {
            log.debug("target io exception: {}", nettyIoExceptionSummary(cause))
            ctx.close()
            return
        }
        log.debug("target unexpected exception", cause)
        ctx.close()
    }
}
