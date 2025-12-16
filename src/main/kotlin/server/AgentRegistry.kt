package server

import io.netty.channel.Channel
import io.netty.util.AttributeKey
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

class AgentRegistry {
    private val online = ConcurrentHashMap<String, AgentControl>()

    fun get(agentId: String): AgentControl? = online[agentId]

    fun register(agentId: String, ch: Channel) {
        ch.attr(ATTR_AGENT_ID).set(agentId)
        val prev = online.put(agentId, AgentControl(ch, Instant.now()))
        if (prev != null && prev.channel != ch) {
            prev.channel.close()
        }
    }

    fun touch(ch: Channel) {
        val agentId = ch.attr(ATTR_AGENT_ID).get() ?: return
        online.computeIfPresent(agentId) { _, v -> AgentControl(v.channel, Instant.now()) }
    }

    fun unregister(ch: Channel) {
        val agentId = ch.attr(ATTR_AGENT_ID).getAndSet(null) ?: return
        online.remove(agentId)
    }

    data class AgentControl(val channel: Channel, val lastSeen: Instant)

    companion object {
        private val ATTR_AGENT_ID: AttributeKey<String> = AttributeKey.valueOf("agentId")
    }
}
