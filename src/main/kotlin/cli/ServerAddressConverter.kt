package cli

import picocli.CommandLine
import java.net.URI

data class ServerAddress(val host: String, val port: Int, val tls: Boolean)

class ServerAddressConverter : CommandLine.ITypeConverter<ServerAddress> {
    override fun convert(value: String): ServerAddress {
        if (value.isBlank()) {
            throw CommandLine.TypeConversionException("empty --server")
        }

        val uri =
            try {
                URI(value)
            } catch (_: Exception) {
                throw CommandLine.TypeConversionException("bad --server: $value")
            }
        val scheme = uri.scheme?.lowercase()
        if (scheme != "ws" && scheme != "wss") {
            throw CommandLine.TypeConversionException("bad --server: $value")
        }
        val host = uri.host ?: throw CommandLine.TypeConversionException("bad --server: $value")
        val port = uri.port
        if (port <= 0) {
            throw CommandLine.TypeConversionException("bad --server: $value")
        }
        return ServerAddress(host, port, scheme == "wss")
    }
}
