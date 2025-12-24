package server

import io.netty.channel.Channel
import io.netty.util.AttributeKey
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

/**
 * server 侧 agent 控制连接注册表。
 *
 * 用于保存“在线 agentId -> control channel”的映射关系，并在断连时进行清理。
 *
 * 说明：
 * - 同一个 agentId 重复注册时，会关闭旧的 control channel
 * - 使用 [AttributeKey] 将 agentId 写入 channel，便于 channelInactive 时反查
 */
class AgentRegistry {
    /** 在线表：agentId -> AgentControl。 */
    private val online = ConcurrentHashMap<String, AgentControl>()

    /**
     * 获取指定 agentId 的 control 连接信息。
     *
     * @param agentId agentId
     * @return 在线则返回 AgentControl，否则返回 null
     */
    fun get(agentId: String): AgentControl? = online[agentId]

    /**
     * 注册/更新一个 agent control 连接。
     *
     * 若同一个 agentId 已存在旧连接，则会关闭旧连接，确保 server 只保留最新连接。
     *
     * @param agentId agentId
     * @param ch agent control channel
     */
    fun register(agentId: String, ch: Channel) {
        ch.attr(ATTR_AGENT_ID).set(agentId)
        val prev = online.put(agentId, AgentControl(ch, Instant.now()))
        if (prev != null && prev.channel != ch) {
            prev.channel.close()
        }
    }

    /**
     * 更新指定 channel 对应 agentId 的 lastSeen。
     *
     * 当前实现中 lastSeen 仅作为扩展点；并未实现心跳/超时踢出逻辑。
     *
     * @param ch agent control channel
     */
    fun touch(ch: Channel) {
        val agentId = ch.attr(ATTR_AGENT_ID).get() ?: return
        online.computeIfPresent(agentId) { _, v ->
            if (v.channel == ch) AgentControl(v.channel, Instant.now()) else v
        }
    }

    /**
     * 注销一个 agent control 连接（通常在 channelInactive 时调用）。
     *
     * @param ch agent control channel
     */
    fun unregister(ch: Channel) {
        val agentId = ch.attr(ATTR_AGENT_ID).getAndSet(null) ?: return
        online.computeIfPresent(agentId) { _, v -> if (v.channel == ch) null else v }
    }

    /**
     * agent control 连接元数据（当前仅用于保存 channel + lastSeen）。
     *
     * @param channel agent 的控制面 channel（`/ws/agent/control`）
     * @param lastSeen 最近一次更新时间（扩展点：可用于心跳/超时踢出）
     */
    data class AgentControl(val channel: Channel, val lastSeen: Instant)

    companion object {
        /** 绑定在 agent control channel 上的属性键：agentId。 */
        private val ATTR_AGENT_ID: AttributeKey<String> = AttributeKey.valueOf("agentId")
    }
}
