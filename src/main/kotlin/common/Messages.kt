package common

object Messages {
    data class AgentRegister(val type: String, val agentId: String, val token: String)

    data class AgentRegisterOk(val type: String)

    data class AgentRegisterErr(val type: String, val code: Int, val message: String)

    data class TunnelCreate(val type: String, val tunnelId: String, val targetHost: String, val targetPort: Int)

    data class TunnelCreateErr(val type: String, val tunnelId: String, val code: Int, val message: String)

    data class ClientTunnelOpen(
        val type: String,
        val tunnelId: String,
        val agentId: String,
        val targetHost: String,
        val targetPort: Int,
        val token: String,
    )

    data class ClientTunnelOk(val type: String, val tunnelId: String)

    data class ClientTunnelErr(val type: String, val tunnelId: String, val code: Int, val message: String)

    data class AgentDataBind(val type: String, val tunnelId: String, val agentId: String, val token: String)

    data class AgentDataBindOk(val type: String, val tunnelId: String)

    data class AgentDataBindErr(val type: String, val tunnelId: String, val code: Int, val message: String)
}
