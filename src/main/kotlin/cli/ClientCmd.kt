package cli

import client.ClientApp
import client.Forward
import client.Socks5Listen
import picocli.CommandLine
import java.io.File
import java.util.concurrent.Callable

/**
 * `client` 子命令：启动本地 Client。
 *
 * Client 按 `--forward` / `--socks5` 规则在本地启动 listener。每 accept 一条本地连接就创建：
 * - 一个新的 `tunnelId(UUID)`
 * - 一条新的 client tunnel WebSocket 连接（`/ws/client/tunnel`）
 *
 * 并在本地 TCP 与 WebSocket 二进制帧之间进行透传。
 */
@CommandLine.Command(name = "client", mixinStandardHelpOptions = true)
class ClientCmd : Callable<Int> {
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

    /** 要连接的 agentId（server 侧按 agentId 选择控制连接下发建隧道指令）。 */
    @CommandLine.Option(names = ["--agent-id"], required = true)
    lateinit var agentId: String

    /** 跳过 TLS 证书校验（仅开发环境使用）。 */
    @CommandLine.Option(names = ["--insecure"], description = ["skip TLS verification (dev only)"])
    var insecure: Boolean = false

    /** 自定义 CA 证书文件（用于校验 server 证书链）。 */
    @CommandLine.Option(names = ["--ca"], description = ["CA cert file"])
    var caFile: File? = null

    /**
     * 转发规则列表。
     *
     * 每条规则会创建一个本地 listener：listenHost:listenPort -> targetHost:targetPort。
     */
    @CommandLine.Option(
        names = ["--forward"],
        converter = [ForwardConverter::class],
        description = ["forward rule: <listenPort>:<targetHost>:<targetPort> or <listenHost>:<listenPort>:<targetHost>:<targetPort>"],
    )
    var forwards: List<Forward> = emptyList()

    /**
     * SOCKS5 监听列表。
     *
     * 每条规则会创建一个本地 SOCKS5 listener：listenHost:listenPort。
     */
    @CommandLine.Option(
        names = ["--socks5"],
        converter = [Socks5ListenConverter::class],
        description = ["socks5 listen: <port> or <listenHost>:<port>"],
    )
    var socks5Listens: List<Socks5Listen> = emptyList()

    /**
     * 执行子命令：构造 [ClientApp] 并阻塞运行。
     *
     * @return 进程退出码（0 表示正常）
     */
    override fun call(): Int {
        val useTls = server.tls
        ClientApp(
            ClientApp.Config(
                server.host,
                server.port,
                token,
                agentId,
                useTls,
                insecure,
                caFile,
                forwards,
                socks5Listens,
            )
        ).runUntilShutdown()
        return 0
    }
}
