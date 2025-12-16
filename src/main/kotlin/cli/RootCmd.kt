package cli

import picocli.CommandLine

@CommandLine.Command(
    name = "tunnel",
    mixinStandardHelpOptions = true,
    subcommands = [ServerCmd::class, AgentCmd::class, ClientCmd::class],
    description = ["WS/WSS tunnel (server/agent/client)"],
    version = ["tunnel 1.0"],
)
class RootCmd : Runnable {
    override fun run() {
        throw CommandLine.ParameterException(CommandLine(this), "missing subcommand (server|agent|client)")
    }
}
