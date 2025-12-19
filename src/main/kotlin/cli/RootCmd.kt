package cli

import picocli.CommandLine

/**
 * picocli 根命令：仅负责组织子命令。
 *
 * 本应用通过一个可执行 JAR 提供三个子命令：
 * - `server`：公网服务端（接入 client 与 agent）
 * - `agent`：内网代理端（连接 server，按需回连内网 target）
 * - `client`：本地客户端（监听本地端口，对每条连接创建一条隧道）
 */
@CommandLine.Command(
    name = "tunnel",
    mixinStandardHelpOptions = true,
    subcommands = [ServerCmd::class, AgentCmd::class, ClientCmd::class],
    description = ["WS/WSS tunnel (server/agent/client)"],
    version = ["tunnel 1.0"],
)
class RootCmd : Runnable {
    /**
     * 根命令本身不提供默认行为；必须指定子命令。
     *
     * picocli 在未匹配到子命令时会执行该方法，这里显式抛出异常以提示用户正确用法。
     */
    override fun run() {
        throw CommandLine.ParameterException(CommandLine(this), "missing subcommand (server|agent|client)")
    }
}
