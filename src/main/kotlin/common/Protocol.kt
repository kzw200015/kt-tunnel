package common

import com.alibaba.fastjson2.JSONObject
import java.util.*

object Protocol {
    const val CODE_BAD_TOKEN = 401
    const val CODE_AGENT_MISMATCH = 403
    const val CODE_NOT_FOUND = 404
    const val CODE_DUPLICATE = 409
    const val CODE_DIAL_FAILED = 502
    const val CODE_HANDSHAKE_TIMEOUT = 504

    const val MSG_BAD_TOKEN = "BAD_TOKEN"
    const val MSG_AGENT_OFFLINE = "AGENT_OFFLINE"
    const val MSG_NO_SUCH_TUNNEL = "NO_SUCH_TUNNEL"
    const val MSG_DUPLICATE_TUNNEL_ID = "DUPLICATE_TUNNEL_ID"
    const val MSG_DIAL_FAILED = "DIAL_FAILED"
    const val MSG_HANDSHAKE_TIMEOUT = "HANDSHAKE_TIMEOUT"
    const val MSG_AGENT_MISMATCH = "AGENT_MISMATCH"

    fun requireType(obj: JSONObject): String {
        val type = obj.getString("type")
        if (type == null || type.isBlank()) {
            throw IllegalArgumentException("MISSING_TYPE")
        }
        return type
    }

    fun requireString(obj: JSONObject, field: String, maxLen: Int): String {
        val value = obj.getString(field)
        if (value == null || value.isBlank()) {
            throw IllegalArgumentException("MISSING_$field")
        }
        if (value.length > maxLen) {
            throw IllegalArgumentException("FIELD_TOO_LONG_$field")
        }
        return value
    }

    fun requireTunnelId(obj: JSONObject): String {
        val tunnelId = requireString(obj, "tunnelId", 64)
        try {
            UUID.fromString(tunnelId)
        } catch (_: Exception) {
            throw IllegalArgumentException("INVALID_TUNNEL_ID")
        }
        return tunnelId
    }

    fun requirePort(obj: JSONObject, field: String): Int {
        val port = obj.getInteger(field) ?: throw IllegalArgumentException("MISSING_$field")
        if (port < 1 || port > 65535) {
            throw IllegalArgumentException("INVALID_$field")
        }
        return port
    }

    fun requireTokenMatch(expected: String, provided: String) {
        if (expected != provided) {
            throw IllegalArgumentException(MSG_BAD_TOKEN)
        }
    }
}
