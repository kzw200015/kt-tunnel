package server.handler

import common.*
import io.netty.channel.ChannelHandlerContext
import io.netty.channel.SimpleChannelInboundHandler
import io.netty.handler.codec.http.websocketx.TextWebSocketFrame
import isIgnorableNettyIoException
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import logger
import nettyIoExceptionSummary
import server.AgentRegistry
import server.TunnelRegistry

/**
 * server 侧 agent 控制面 handler：处理 `/ws/agent/control`。
 *
 * 负责：
 * - 处理 `AGENT_REGISTER`：鉴权并注册到 [AgentRegistry]
 * - 处理 `TUNNEL_CREATE_ERR`：转交 [TunnelRegistry] 通知 client
 *
 * 该 handler 仅处理 TextWebSocketFrame（JSON）。
 */
class AgentControlHandler(
    /** 共享 token（用于鉴权）。 */
    private val token: String,
    /** agent 注册表（保存在线 control 连接）。 */
    private val agentRegistry: AgentRegistry,
    /** 隧道注册表（处理创建失败回报）。 */
    private val tunnelRegistry: TunnelRegistry,
) : SimpleChannelInboundHandler<TextWebSocketFrame>() {
    private val log = logger<AgentControlHandler>()

    /** 当前 control 连接已注册的 agentId（未注册则为 null）。 */
    private var registeredAgentId: String? = null

    /** 处理 agent control 的控制面消息（Text JSON）。 */
    override fun channelRead0(ctx: ChannelHandlerContext, frame: TextWebSocketFrame) {
        val obj: JsonObject =
            try {
                frame.text().parseJsonObject()
            } catch (_: Exception) {
                ctx.close()
                return
            }

        when (Protocol.requireType(obj)) {
            MsgTypes.AGENT_REGISTER -> onRegister(ctx, obj)
            MsgTypes.AGENT_HEARTBEAT -> agentRegistry.touch(ctx.channel())
            MsgTypes.TUNNEL_CREATE_ERR -> onCreateErr(obj)
            else -> log.debug("ignore agent control msg type={}", Protocol.requireType(obj))
        }
    }

    /**
     * 处理 agent 注册消息：
     * - 校验 token
     * - 注册 agentId -> channel
     * - 返回 REGISTER_OK 或 REGISTER_ERR
     */
    private fun onRegister(ctx: ChannelHandlerContext, obj: JsonObject) {
        val agentId: String
        val providedToken: String
        try {
            agentId = Protocol.requireString(obj, "agentId", 128)
            providedToken = Protocol.requireString(obj, "token", 256)
        } catch (_: Exception) {
            ctx.close()
            return
        }

        if (token != providedToken) {
            log.warn("agent register rejected (bad token): remote={}", ctx.channel().remoteAddress())
            ctx
                .writeAndFlush(
                    TextWebSocketFrame(
                        Messages.AgentRegisterErr(
                            MsgTypes.AGENT_REGISTER_ERR,
                            Protocol.CODE_BAD_TOKEN,
                            Protocol.MSG_BAD_TOKEN
                        ).toJsonString(),
                    ),
                ).addListener { ctx.close() }
            return
        }

        agentRegistry.register(agentId, ctx.channel())
        registeredAgentId = agentId
        log.info("agent control registered: agentId={}, remote={}", agentId, ctx.channel().remoteAddress())
        ctx.writeAndFlush(TextWebSocketFrame(Messages.AgentRegisterOk(MsgTypes.AGENT_REGISTER_OK).toJsonString()))
    }

    /**
     * 处理 agent 回报的创建失败消息 `TUNNEL_CREATE_ERR`。
     *
     * server 侧会将该失败转交 [TunnelRegistry]，由 registry 向对应 client 返回 ERR 并关闭。
     */
    private fun onCreateErr(obj: JsonObject) {
        val tunnelId: String
        val code: Int
        val message: String
        try {
            tunnelId = Protocol.requireTunnelId(obj)
            code = (obj["code"] as? JsonPrimitive)?.content?.toIntOrNull() ?: Protocol.CODE_DIAL_FAILED
            message =
                (obj["message"] as? JsonPrimitive)?.content?.takeIf { it.isNotBlank() }
                    ?: Protocol.MSG_DIAL_FAILED
        } catch (_: Exception) {
            return
        }

        log.warn("agent tunnel create err: tunnelId={}, code={}, message={}", tunnelId, code, message)
        tunnelRegistry.handleAgentCreateErr(
            Messages.TunnelCreateErr(
                MsgTypes.TUNNEL_CREATE_ERR,
                tunnelId,
                code,
                message
            )
        )
    }

    /** 控制连接断开：从 [AgentRegistry] 注销该 agent。 */
    override fun channelInactive(ctx: ChannelHandlerContext) {
        agentRegistry.unregister(ctx.channel())
        log.info("agent control disconnected: agentId={}, remote={}", registeredAgentId, ctx.channel().remoteAddress())
    }

    /** 异常处理：记录 debug 日志并关闭连接。 */
    override fun exceptionCaught(ctx: ChannelHandlerContext, cause: Throwable) {
        if (isIgnorableNettyIoException(cause)) {
            log.debug("agent control io exception: {}", nettyIoExceptionSummary(cause))
            ctx.close()
            return
        }
        log.debug("agent control unexpected exception", cause)
        ctx.close()
    }
}
