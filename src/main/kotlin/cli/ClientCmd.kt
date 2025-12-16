package cli

import client.ClientApp
import client.Forward
import picocli.CommandLine
import java.io.File
import java.util.concurrent.Callable

@CommandLine.Command(name = "client", mixinStandardHelpOptions = true)
class ClientCmd : Callable<Int> {
    @CommandLine.Option(names = ["--server-host"], required = true)
    lateinit var serverHost: String

    @CommandLine.Option(names = ["--server-port"], required = true)
    var serverPort: Int = 0

    @CommandLine.Option(names = ["--token"], required = true)
    lateinit var token: String

    @CommandLine.Option(names = ["--agent-id"], required = true)
    lateinit var agentId: String

    @CommandLine.Option(names = ["--tls"], description = ["enable TLS (wss)"])
    var tls: Boolean = false

    @CommandLine.Option(names = ["--insecure"], description = ["skip TLS verification (dev only)"])
    var insecure: Boolean = false

    @CommandLine.Option(names = ["--ca"], description = ["CA cert file"])
    var caFile: File? = null

    @CommandLine.Option(
        names = ["--forward"],
        required = true,
        converter = [ForwardConverter::class],
        description = ["forward rule: <listenPort>:<targetHost>:<targetPort> or <listenHost>:<listenPort>:<targetHost>:<targetPort>"],
    )
    lateinit var forwards: List<Forward>

    override fun call(): Int {
        val useTls = tls || insecure || caFile != null
        ClientApp(
            ClientApp.Config(
                serverHost,
                serverPort,
                token,
                agentId,
                useTls,
                insecure,
                caFile,
                forwards
            )
        ).runUntilShutdown()
        return 0
    }
}
