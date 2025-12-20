package cli

import agent.AgentApp
import picocli.CommandLine
import java.io.File
import java.util.*
import java.util.concurrent.Callable

/**
 * `agent` 子命令：启动内网 Agent。
 *
 * Agent 会与 server 建立一条控制面 WebSocket 长连接，接收 server 下发的 `TUNNEL_CREATE` 指令：
 * 1. 先连接内网 target（TCP）
 * 2. 成功后建立数据面 WebSocket（`/ws/agent/data`）并发送 `AGENT_DATA_BIND`
 *
 * 每条隧道独占一条 data WS 与一条 target TCP（无多路复用）。
 */
@CommandLine.Command(name = "agent", mixinStandardHelpOptions = true)
class AgentCmd : Callable<Int> {
    /** server 的地址（ws:// 或 wss://）。 */
    @CommandLine.Option(
        names = ["--server"],
        required = true,
        converter = [ServerAddressConverter::class],
        paramLabel = "ws[s]://HOST:PORT",
        description = ["server url: ws://host:port or wss://host:port"],
    )
    lateinit var server: ServerAddress

    /** 与 server 约定的共享 token。 */
    @CommandLine.Option(names = ["--token"], required = true)
    lateinit var token: String

    /** agent 的逻辑标识；为空时自动生成 UUID 并打印。 */
    @CommandLine.Option(names = ["--agent-id"], description = ["agent id (default: random uuid)"])
    var agentId: String? = null

    /** 跳过 TLS 证书校验（仅开发环境使用）。 */
    @CommandLine.Option(names = ["--insecure"], description = ["skip TLS verification (dev only)"])
    var insecure: Boolean = false

    /** 自定义 CA 证书文件（用于校验 server 证书链）。 */
    @CommandLine.Option(names = ["--ca"], description = ["CA cert file"])
    var caFile: File? = null

    /**
     * 执行子命令：构造 [AgentApp] 并阻塞运行到连接关闭。
     *
     * @return 进程退出码（0 表示正常）
     */
    override fun call(): Int {
        val id = if (agentId.isNullOrBlank()) UUID.randomUUID().toString() else agentId!!
        if (agentId.isNullOrBlank()) {
            println("agentId=$id")
        }
        val useTls = server.tls
        AgentApp(AgentApp.Config(server.host, server.port, token, id, useTls, insecure, caFile)).runUntilShutdown()
        return 0
    }
}
