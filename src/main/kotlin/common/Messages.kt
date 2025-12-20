package common

import kotlinx.serialization.Serializable

/**
 * 控制面消息（TextWebSocketFrame JSON）结构。
 *
 * 这里的 data class 仅用于 JSON 序列化/反序列化；透传数据不在此处定义。
 */
object Messages {
    /**
     * agent -> server：注册/鉴权（控制面）。
     *
     * @param type 消息类型：`AGENT_REGISTER`
     * @param agentId agent 的逻辑标识（server 侧按 agentId 找到对应 control channel）
     * @param token 共享 token（MVP：静态密钥）
     */
    @Serializable
    data class AgentRegister(val type: String, val agentId: String, val token: String)

    /**
     * server -> agent：注册成功响应（控制面）。
     *
     * @param type 消息类型：`AGENT_REGISTER_OK`
     */
    @Serializable
    data class AgentRegisterOk(val type: String)

    /**
     * server -> agent：注册失败响应（控制面）。
     *
     * @param type 消息类型：`AGENT_REGISTER_ERR`
     * @param code 错误码（例如 [Protocol.CODE_BAD_TOKEN]）
     * @param message 错误原因（例如 [Protocol.MSG_BAD_TOKEN]）
     */
    @Serializable
    data class AgentRegisterErr(val type: String, val code: Int, val message: String)

    /**
     * server -> agent：创建隧道指令（控制面）。
     *
     * @param type 消息类型：`TUNNEL_CREATE`
     * @param tunnelId client 生成的 tunnelId（UUID 字符串）
     * @param targetHost agent 侧需要连接的 targetHost
     * @param targetPort agent 侧需要连接的 targetPort
     */
    @Serializable
    data class TunnelCreate(val type: String, val tunnelId: String, val targetHost: String, val targetPort: Int)

    /**
     * agent -> server：创建隧道失败回报（控制面）。
     *
     * 成功时不需要 OK；成功以 agent data 连接上的 `AGENT_DATA_BIND` 为准。
     *
     * @param type 消息类型：`TUNNEL_CREATE_ERR`
     * @param tunnelId 隧道 ID
     * @param code 错误码（例如 [Protocol.CODE_DIAL_FAILED]）
     * @param message 错误原因（例如 [Protocol.MSG_DIAL_FAILED]）
     */
    @Serializable
    data class TunnelCreateErr(val type: String, val tunnelId: String, val code: Int, val message: String)

    /**
     * client -> server：打开隧道请求（client tunnel 的第一帧，控制面）。
     *
     * @param type 消息类型：`CLIENT_TUNNEL_OPEN`
     * @param tunnelId client 生成的隧道 ID（UUID 字符串）
     * @param agentId 指定要使用的 agent（server 通过 agentId 找 control channel 下发创建指令）
     * @param targetHost agent 侧要连接的目标主机
     * @param targetPort agent 侧要连接的目标端口
     * @param token 共享 token（MVP：静态密钥）
     */
    @Serializable
    data class ClientTunnelOpen(
        val type: String,
        val tunnelId: String,
        val agentId: String,
        val targetHost: String,
        val targetPort: Int,
        val token: String,
    )

    /**
     * server -> client：隧道 OK（client tunnel 控制面）。
     *
     * server 只有在 agent data bind 完成后才会发送 OK，client 收到 OK 才能开始发送 binary。
     *
     * @param type 消息类型：`CLIENT_TUNNEL_OK`
     * @param tunnelId 隧道 ID
     */
    @Serializable
    data class ClientTunnelOk(val type: String, val tunnelId: String)

    /**
     * server -> client：隧道错误（client tunnel 控制面）。
     *
     * @param type 消息类型：`CLIENT_TUNNEL_ERR`
     * @param tunnelId 隧道 ID
     * @param code 错误码
     * @param message 错误原因
     */
    @Serializable
    data class ClientTunnelErr(val type: String, val tunnelId: String, val code: Int, val message: String)

    /**
     * agent -> server：agent data 连接绑定隧道（agent data 的第一帧，控制面）。
     *
     * @param type 消息类型：`AGENT_DATA_BIND`
     * @param tunnelId 隧道 ID
     * @param agentId agent ID（用于校验与 pending 的 agentId 是否一致）
     * @param token 共享 token（MVP：静态密钥）
     */
    @Serializable
    data class AgentDataBind(val type: String, val tunnelId: String, val agentId: String, val token: String)

    /**
     * server -> agent：绑定成功（agent data 控制面，可选 ack）。
     *
     * @param type 消息类型：`AGENT_DATA_BIND_OK`
     * @param tunnelId 隧道 ID
     */
    @Serializable
    data class AgentDataBindOk(val type: String, val tunnelId: String)

    /**
     * server -> agent：绑定失败（agent data 控制面）。
     *
     * @param type 消息类型：`AGENT_DATA_BIND_ERR`
     * @param tunnelId 隧道 ID
     * @param code 错误码
     * @param message 错误原因
     */
    @Serializable
    data class AgentDataBindErr(val type: String, val tunnelId: String, val code: Int, val message: String)
}
