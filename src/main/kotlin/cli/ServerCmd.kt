package cli

import picocli.CommandLine
import server.ServerApp
import java.io.File
import java.time.Duration
import java.util.concurrent.Callable

@CommandLine.Command(name = "server", mixinStandardHelpOptions = true)
class ServerCmd : Callable<Int> {
    @CommandLine.Option(names = ["--bind"], defaultValue = "0.0.0.0", description = ["bind host"])
    var bindHost: String = "0.0.0.0"

    @CommandLine.Option(names = ["--port"], required = true, description = ["bind port"])
    var port: Int = 0

    @CommandLine.Option(names = ["--token"], required = true, description = ["shared token"])
    lateinit var token: String

    @CommandLine.Option(names = ["--cert"], description = ["PEM cert file (enable TLS with --key)"])
    var cert: File? = null

    @CommandLine.Option(names = ["--key"], description = ["PEM key file (enable TLS with --cert)"])
    var key: File? = null

    @CommandLine.Option(
        names = ["--pending-timeout-seconds"],
        defaultValue = "10",
        description = ["pending tunnel timeout seconds"],
    )
    var pendingTimeoutSeconds: Long = 10

    override fun call(): Int {
        ServerApp(
            ServerApp.Config(
                bindHost = bindHost,
                port = port,
                token = token,
                certFile = cert,
                keyFile = key,
                pendingTimeout = Duration.ofSeconds(pendingTimeoutSeconds),
            ),
        ).runUntilShutdown()
        return 0
    }
}
