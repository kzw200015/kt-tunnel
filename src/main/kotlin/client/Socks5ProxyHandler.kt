package client

import io.netty.channel.ChannelHandlerContext
import io.netty.channel.SimpleChannelInboundHandler
import io.netty.handler.codec.socksx.v5.*
import isIgnorableNettyIoException
import nettyIoExceptionSummary
import logger

/**
 * SOCKS5 proxy handler that maps CONNECT requests to tunnels.
 */
class Socks5ProxyHandler(
    private val tunnelConnector: ClientTunnelConnector,
    private val listen: Socks5Listen,
) : SimpleChannelInboundHandler<Socks5Message>() {
    private val log = logger<Socks5ProxyHandler>()

    override fun channelRead0(ctx: ChannelHandlerContext, msg: Socks5Message) {
        if (!msg.decoderResult().isSuccess) {
            ctx.close()
            return
        }
        when (msg) {
            is Socks5InitialRequest -> handleInitial(ctx, msg)
            is Socks5CommandRequest -> handleCommand(ctx, msg)
            else -> ctx.close()
        }
    }

    private fun handleInitial(ctx: ChannelHandlerContext, req: Socks5InitialRequest) {
        if (!req.authMethods().contains(Socks5AuthMethod.NO_AUTH)) {
            ctx.writeAndFlush(DefaultSocks5InitialResponse(Socks5AuthMethod.UNACCEPTED))
                .addListener { ctx.close() }
            return
        }
        ctx.pipeline().addBefore(ctx.name(), "socks5CmdDecoder", Socks5CommandRequestDecoder())
        ctx.pipeline().remove(Socks5InitialRequestDecoder::class.java)
        ctx.writeAndFlush(DefaultSocks5InitialResponse(Socks5AuthMethod.NO_AUTH))
    }

    private fun handleCommand(ctx: ChannelHandlerContext, req: Socks5CommandRequest) {
        val responseAddrType = req.dstAddrType()
        if (req.type() != Socks5CommandType.CONNECT) {
            ctx.writeAndFlush(
                DefaultSocks5CommandResponse(
                    Socks5CommandStatus.COMMAND_UNSUPPORTED,
                    responseAddrType,
                ),
            ).addListener { ctx.close() }
            return
        }

        val targetHost = req.dstAddr()
        val targetPort = req.dstPort()
        val forward = Forward(listen.listenHost, listen.listenPort, targetHost, targetPort)
        val localCh = ctx.channel()
        val tunnelContext =
            tunnelConnector.open(
                localCh,
                forward,
                onReady = {
                    if (localCh.isActive) {
                        localCh.writeAndFlush(
                            DefaultSocks5CommandResponse(Socks5CommandStatus.SUCCESS, responseAddrType),
                        )
                    }
                },
            )
        log.info(
            "socks5 connect request: tunnelId={}, listen={}:{}, target={}:{}, remote={}",
            tunnelContext.tunnelId,
            listen.listenHost,
            listen.listenPort,
            targetHost,
            targetPort,
            ctx.channel().remoteAddress(),
        )
    }

    override fun exceptionCaught(ctx: ChannelHandlerContext, cause: Throwable) {
        if (isIgnorableNettyIoException(cause)) {
            log.debug("socks5 io exception: {}", nettyIoExceptionSummary(cause))
            ctx.close()
            return
        }
        log.debug("socks5 unexpected exception", cause)
        ctx.close()
    }
}
