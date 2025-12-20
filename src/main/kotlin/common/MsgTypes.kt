package common

/**
 * WebSocket Text(JSON) 控制消息的 type 常量。
 *
 * 注意：二进制透传使用 BinaryWebSocketFrame，不带 type。
 */
object MsgTypes {
    /** agent -> server：注册。 */
    const val AGENT_REGISTER = "AGENT_REGISTER"

    /** server -> agent：注册成功。 */
    const val AGENT_REGISTER_OK = "AGENT_REGISTER_OK"

    /** server -> agent：注册失败。 */
    const val AGENT_REGISTER_ERR = "AGENT_REGISTER_ERR"

    /** server -> agent：创建隧道指令。 */
    const val TUNNEL_CREATE = "TUNNEL_CREATE"

    /** agent -> server：创建隧道失败回报（成功不回 OK）。 */
    const val TUNNEL_CREATE_ERR = "TUNNEL_CREATE_ERR"

    /** client -> server：打开隧道请求（client tunnel 第一帧）。 */
    const val CLIENT_TUNNEL_OPEN = "CLIENT_TUNNEL_OPEN"

    /** server -> client：隧道已就绪（agent data bind 完成）。 */
    const val CLIENT_TUNNEL_OK = "CLIENT_TUNNEL_OK"

    /** server -> client：隧道失败/拒绝。 */
    const val CLIENT_TUNNEL_ERR = "CLIENT_TUNNEL_ERR"

    /** agent -> server：agent data 连接绑定隧道（agent data 第一帧）。 */
    const val AGENT_DATA_BIND = "AGENT_DATA_BIND"

    /** server -> agent：绑定成功（可选 ack）。 */
    const val AGENT_DATA_BIND_OK = "AGENT_DATA_BIND_OK"

    /** server -> agent：绑定失败。 */
    const val AGENT_DATA_BIND_ERR = "AGENT_DATA_BIND_ERR"
}
