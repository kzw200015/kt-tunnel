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

/**
 * Agent 侧隧道管理器：负责创建与维护当前进程内的所有 tunnel。
 *
 * 重要约束：
 * - 不做多路复用：一个 tunnelId 独占一条 target TCP 与一条 data WS
 * - 隧道的生命周期由任一侧断连驱动清理（target 或 data WS 任一断开则关闭另一侧）
 */
class AgentTunnelManager(
    /** 事件循环组：用于连接 target TCP 和 data WS。 */
    private val group: EventLoopGroup,
    /** 可选的 TLS 上下文：启用 wss 时用于 data WS。 */
    private val sslContext: SslContext?,
    /** agent data 的 WebSocket URI（`/ws/agent/data`）。 */
    private val dataUri: URI,
    /** 当前 agent 的逻辑标识。 */
    private val agentId: String,
    /** 共享 token。 */
    private val token: String,
) {
    private val log = logger<AgentTunnelManager>()

    /** 本地隧道表：tunnelId -> TunnelContext。 */
    private val tunnels = ConcurrentHashMap<String, TunnelContext>()

    /**
     * 创建一个 tunnel：
     * 1. 先连接内网 targetHost:targetPort（TCP）
     * 2. 成功后再连接 `/ws/agent/data`，并发送 `AGENT_DATA_BIND`
     * 3. 任一侧失败则通过 control 回报 `TUNNEL_CREATE_ERR`
     */
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
