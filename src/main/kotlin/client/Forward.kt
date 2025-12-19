package client

/**
 * 一条端口转发规则：本地 listener -> 远端 target（由 agent 侧去连接）。
 *
 * @param listenHost 本地监听地址（例如 `0.0.0.0` / `127.0.0.1`）
 * @param listenPort 本地监听端口
 * @param targetHost agent 侧要连接的目标主机（内网地址）
 * @param targetPort agent 侧要连接的目标端口
 */
data class Forward(
    val listenHost: String,
    val listenPort: Int,
    val targetHost: String,
    val targetPort: Int,
)
