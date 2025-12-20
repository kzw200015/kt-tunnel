package common

import common.Protocol.CODE_BAD_TOKEN
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.util.*

/**
 * 控制面协议的字段校验与错误码/原因常量。
 *
 * 本项目将控制面消息都定义为 JSON（TextWebSocketFrame），因此在 handler 中需要对必填字段做校验，
 * 以确保三端控制面时序与数据面绑定的健壮性。
 */
object Protocol {
    /** token 不匹配。 */
    const val CODE_BAD_TOKEN = 401

    /** agentId 与 pending 的 agentId 不一致（潜在的串线/复用问题）。 */
    const val CODE_AGENT_MISMATCH = 403

    /** 资源不存在（例如 agent 不在线、tunnel 不存在）。 */
    const val CODE_NOT_FOUND = 404

    /** tunnelId 冲突（重复）。 */
    const val CODE_DUPLICATE = 409

    /** 连接 target 失败（或 data ws 连接失败）。 */
    const val CODE_DIAL_FAILED = 502

    /** 等待握手/绑定超时。 */
    const val CODE_HANDSHAKE_TIMEOUT = 504

    /** 与 [CODE_BAD_TOKEN] 对应的错误原因文本。 */
    const val MSG_BAD_TOKEN = "BAD_TOKEN"

    /** agent 不在线或不可用。 */
    const val MSG_AGENT_OFFLINE = "AGENT_OFFLINE"

    /** server 侧找不到对应 tunnelId 的 pending。 */
    const val MSG_NO_SUCH_TUNNEL = "NO_SUCH_TUNNEL"

    /** tunnelId 重复。 */
    const val MSG_DUPLICATE_TUNNEL_ID = "DUPLICATE_TUNNEL_ID"

    /** 连接目标失败。 */
    const val MSG_DIAL_FAILED = "DIAL_FAILED"

    /** 等待 agent data bind 超时。 */
    const val MSG_HANDSHAKE_TIMEOUT = "HANDSHAKE_TIMEOUT"

    /** agentId 与 server 侧 pending 的 agentId 不一致。 */
    const val MSG_AGENT_MISMATCH = "AGENT_MISMATCH"

    /** 从 JSON 中读取并校验 `type` 字段。 */
    fun requireType(obj: JsonObject): String {
        val type = (obj["type"] as? JsonPrimitive)?.content
        if (type == null || type.isBlank()) {
            throw IllegalArgumentException("MISSING_TYPE")
        }
        return type
    }

    /** 从 JSON 中读取并校验字符串字段（必填、非空、长度上限）。 */
    fun requireString(obj: JsonObject, field: String, maxLen: Int): String {
        val value = (obj[field] as? JsonPrimitive)?.content
        if (value == null || value.isBlank()) {
            throw IllegalArgumentException("MISSING_$field")
        }
        if (value.length > maxLen) {
            throw IllegalArgumentException("FIELD_TOO_LONG_$field")
        }
        return value
    }

    /** 读取 tunnelId 并校验 UUID 格式。 */
    fun requireTunnelId(obj: JsonObject): String {
        val tunnelId = requireString(obj, "tunnelId", 64)
        try {
            UUID.fromString(tunnelId)
        } catch (_: Exception) {
            throw IllegalArgumentException("INVALID_TUNNEL_ID")
        }
        return tunnelId
    }

    /** 读取端口并校验 1..65535。 */
    fun requirePort(obj: JsonObject, field: String): Int {
        val value = (obj[field] as? JsonPrimitive)?.content ?: throw IllegalArgumentException("MISSING_$field")
        val port = value.toIntOrNull() ?: throw IllegalArgumentException("INVALID_$field")
        if (port < 1 || port > 65535) {
            throw IllegalArgumentException("INVALID_$field")
        }
        return port
    }

    /** 校验 token（MVP：共享密钥）。 */
    fun requireTokenMatch(expected: String, provided: String) {
        if (expected != provided) {
            throw IllegalArgumentException(MSG_BAD_TOKEN)
        }
    }
}
