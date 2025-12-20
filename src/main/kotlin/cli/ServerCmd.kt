package cli

import picocli.CommandLine
import server.ServerApp
import java.io.File
import java.time.Duration
import java.util.concurrent.Callable

/**
 * `server` 子命令：启动公网 Server。
 *
 * Server 在单端口上提供三个 WebSocket endpoint：
 * - `/ws/agent/control`：agent 控制面长连接（注册、下发建隧道指令）
 * - `/ws/agent/data`：agent 数据面（每条隧道 1 条连接）
 * - `/ws/client/tunnel`：client 隧道（每条本地连接 1 条隧道/连接）
 */
@CommandLine.Command(name = "server", mixinStandardHelpOptions = true)
class ServerCmd : Callable<Int> {
    /** Server 监听地址（格式：`[HOST:]PORT`）。 */
    @CommandLine.Option(
        names = ["--bind"],
        required = true,
        converter = [BindAddressConverter::class],
        paramLabel = "[HOST:]PORT",
        description = ["bind address: [HOST:]PORT"],
    )
    lateinit var bind: BindAddress

    /** 共享 token（MVP：所有 client/agent 与 server 使用同一密钥）。 */
    @CommandLine.Option(names = ["--token"], required = true, description = ["shared token"])
    lateinit var token: String

    /** PEM 格式证书文件；与 [key] 同时提供时启用 TLS（wss）。 */
    @CommandLine.Option(names = ["--cert"], description = ["PEM cert file (enable TLS with --key)"])
    var cert: File? = null

    /** PEM 格式私钥文件；与 [cert] 同时提供时启用 TLS（wss）。 */
    @CommandLine.Option(names = ["--key"], description = ["PEM key file (enable TLS with --cert)"])
    var key: File? = null

    /** 自签证书并启用 TLS（wss）；可选指定证书使用的 host（默认 localhost）。 */
    @CommandLine.Option(
        names = ["--self-signed-tls"],
        arity = "0..1",
        fallbackValue = "localhost",
        paramLabel = "HOST",
        description = ["generate a self-signed cert for HOST and enable TLS (wss)"],
    )
    var selfSignedTlsHost: String? = null

    /** pending 隧道等待超时（秒）：client OPEN 后等待 agent DATA_BIND 的最大时长。 */
    @CommandLine.Option(
        names = ["--pending-timeout-seconds"],
        defaultValue = "10",
        description = ["pending tunnel timeout seconds"],
    )
    var pendingTimeoutSeconds: Long = 10

    /**
     * 执行子命令：构造 [ServerApp] 并阻塞运行到进程退出。
     *
     * @return 进程退出码（0 表示正常）
     */
    override fun call(): Int {
        if (selfSignedTlsHost != null && (cert != null || key != null)) {
            throw CommandLine.ParameterException(
                CommandLine(this),
                "--self-signed-tls conflicts with --cert/--key",
            )
        }
        ServerApp(
            ServerApp.Config(
                bindHost = bind.host,
                port = bind.port,
                token = token,
                certFile = cert,
                keyFile = key,
                selfSignedTlsHost = selfSignedTlsHost,
                pendingTimeout = Duration.ofSeconds(pendingTimeoutSeconds),
            ),
        ).runUntilShutdown()
        return 0
    }
}
