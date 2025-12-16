package server.handler

import com.alibaba.fastjson2.JSON
import com.alibaba.fastjson2.JSONObject
import common.Messages
import common.MsgTypes
import common.Protocol
import io.netty.channel.ChannelHandlerContext
import io.netty.channel.SimpleChannelInboundHandler
import io.netty.handler.codec.http.websocketx.TextWebSocketFrame
import logger
import server.AgentRegistry
import server.TunnelRegistry

class AgentControlHandler(
    private val token: String,
    private val agentRegistry: AgentRegistry,
    private val tunnelRegistry: TunnelRegistry,
) : SimpleChannelInboundHandler<TextWebSocketFrame>() {
    private val log = logger<AgentControlHandler>()

    private var registeredAgentId: String? = null

    override fun channelRead0(ctx: ChannelHandlerContext, frame: TextWebSocketFrame) {
        val obj: JSONObject =
            try {
                JSON.parseObject(frame.text())
            } catch (_: Exception) {
                ctx.close()
                return
            }

        when (Protocol.requireType(obj)) {
            MsgTypes.AGENT_REGISTER -> onRegister(ctx, obj)
            MsgTypes.TUNNEL_CREATE_ERR -> onCreateErr(obj)
            else -> log.debug("ignore agent control msg type={}", Protocol.requireType(obj))
        }
    }

    private fun onRegister(ctx: ChannelHandlerContext, obj: JSONObject) {
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
                        JSON.toJSONString(
                            Messages.AgentRegisterErr(
                                MsgTypes.AGENT_REGISTER_ERR,
                                Protocol.CODE_BAD_TOKEN,
                                Protocol.MSG_BAD_TOKEN
                            ),
                        ),
                    ),
                ).addListener { ctx.close() }
            return
        }

        agentRegistry.register(agentId, ctx.channel())
        registeredAgentId = agentId
        log.info("agent control registered: agentId={}, remote={}", agentId, ctx.channel().remoteAddress())
        ctx.writeAndFlush(TextWebSocketFrame(JSON.toJSONString(Messages.AgentRegisterOk(MsgTypes.AGENT_REGISTER_OK))))
    }

    private fun onCreateErr(obj: JSONObject) {
        val tunnelId: String
        val code: Int
        val message: String
        try {
            tunnelId = Protocol.requireTunnelId(obj)
            code = obj.getInteger("code") ?: Protocol.CODE_DIAL_FAILED
            message = obj.getString("message")?.takeIf { it.isNotBlank() } ?: Protocol.MSG_DIAL_FAILED
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

    override fun channelInactive(ctx: ChannelHandlerContext) {
        agentRegistry.unregister(ctx.channel())
        log.info("agent control disconnected: agentId={}, remote={}", registeredAgentId, ctx.channel().remoteAddress())
    }

    override fun exceptionCaught(ctx: ChannelHandlerContext, cause: Throwable) {
        log.debug("agent control exception", cause)
        ctx.close()
    }
}
