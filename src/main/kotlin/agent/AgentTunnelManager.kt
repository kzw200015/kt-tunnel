package agent

import com.alibaba.fastjson2.JSON
import common.Messages
import common.MsgTypes
import common.Protocol
import io.netty.bootstrap.Bootstrap
import io.netty.channel.Channel
import io.netty.channel.ChannelFutureListener
import io.netty.channel.ChannelOption
import io.netty.channel.EventLoopGroup
import io.netty.channel.socket.nio.NioSocketChannel
import io.netty.handler.codec.http.websocketx.TextWebSocketFrame
import io.netty.handler.ssl.SslContext
import logger
import java.net.URI
import java.util.concurrent.ConcurrentHashMap

class AgentTunnelManager(
    private val group: EventLoopGroup,
    private val sslContext: SslContext?,
    private val dataUri: URI,
    private val agentId: String,
    private val token: String,
) {
    private val log = logger<AgentTunnelManager>()

    private val tunnels = ConcurrentHashMap<String, TunnelContext>()

    fun startTunnel(controlCh: Channel, tunnelId: String, targetHost: String, targetPort: Int) {
        log.info("tunnel start: tunnelId={}, target={}:{}", tunnelId, targetHost, targetPort)

        val ctx = TunnelContext(tunnelId, agentId, token) { tunnels.remove(tunnelId) }
        val existing = tunnels.putIfAbsent(tunnelId, ctx)
        if (existing != null) {
            log.warn("duplicate tunnelId ignored: {}", tunnelId)
            return
        }

        val targetBootstrap =
            Bootstrap()
                .group(group)
                .channel(NioSocketChannel::class.java)
                .option(ChannelOption.TCP_NODELAY, true)
                .option(ChannelOption.SO_KEEPALIVE, true)
                .handler(TargetInitializer(ctx))

        targetBootstrap.connect(targetHost, targetPort).addListener(ChannelFutureListener { f ->
            if (!f.isSuccess) {
                tunnels.remove(tunnelId)
                log.warn(
                    "target connect failed: tunnelId={}, target={}:{}, cause={}",
                    tunnelId,
                    targetHost,
                    targetPort,
                    f.cause()
                )
                controlCh.writeAndFlush(
                    TextWebSocketFrame(
                        JSON.toJSONString(
                            Messages.TunnelCreateErr(
                                MsgTypes.TUNNEL_CREATE_ERR,
                                tunnelId,
                                Protocol.CODE_DIAL_FAILED,
                                Protocol.MSG_DIAL_FAILED
                            ),
                        ),
                    ),
                )
                return@ChannelFutureListener
            }

            val targetCh = f.channel()
            log.info(
                "target connected: tunnelId={}, local={}, remote={}",
                tunnelId,
                targetCh.localAddress(),
                targetCh.remoteAddress()
            )
            targetCh.config().isAutoRead = false
            ctx.targetCh = targetCh

            val dataBootstrap =
                Bootstrap()
                    .group(group)
                    .channel(NioSocketChannel::class.java)
                    .option(ChannelOption.TCP_NODELAY, true)
                    .handler(AgentDataClientInitializer(sslContext, dataUri, ctx))

            log.info("data ws connecting: tunnelId={}, uri={}", tunnelId, dataUri)
            dataBootstrap.connect(dataUri.host, dataUri.port).addListener(ChannelFutureListener { wf ->
                if (wf.isSuccess) {
                    return@ChannelFutureListener
                }
                log.warn("data ws connect failed: tunnelId={}, uri={}, cause={}", tunnelId, dataUri, wf.cause())
                ctx.closeBoth("data_ws_connect_failed")
                controlCh.writeAndFlush(
                    TextWebSocketFrame(
                        JSON.toJSONString(
                            Messages.TunnelCreateErr(
                                MsgTypes.TUNNEL_CREATE_ERR,
                                tunnelId,
                                Protocol.CODE_DIAL_FAILED,
                                "DATA_WS_FAILED"
                            ),
                        ),
                    ),
                )
            })
        })
    }
}
