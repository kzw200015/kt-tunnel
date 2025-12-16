package cli

import agent.AgentApp
import picocli.CommandLine
import java.io.File
import java.util.*
import java.util.concurrent.Callable

@CommandLine.Command(name = "agent", mixinStandardHelpOptions = true)
class AgentCmd : Callable<Int> {
    @CommandLine.Option(names = ["--server-host"], required = true)
    lateinit var serverHost: String

    @CommandLine.Option(names = ["--server-port"], required = true)
    var serverPort: Int = 0

    @CommandLine.Option(names = ["--token"], required = true)
    lateinit var token: String

    @CommandLine.Option(names = ["--agent-id"], description = ["agent id (default: random uuid)"])
    var agentId: String? = null

    @CommandLine.Option(names = ["--tls"], description = ["enable TLS (wss)"])
    var tls: Boolean = false

    @CommandLine.Option(names = ["--insecure"], description = ["skip TLS verification (dev only)"])
    var insecure: Boolean = false

    @CommandLine.Option(names = ["--ca"], description = ["CA cert file"])
    var caFile: File? = null

    override fun call(): Int {
        val id = if (agentId.isNullOrBlank()) UUID.randomUUID().toString() else agentId!!
        if (agentId.isNullOrBlank()) {
            println("agentId=$id")
        }
        val useTls = tls || insecure || caFile != null
        AgentApp(AgentApp.Config(serverHost, serverPort, token, id, useTls, insecure, caFile)).runUntilShutdown()
        return 0
    }
}
