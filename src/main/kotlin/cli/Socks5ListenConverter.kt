package cli

import client.Socks5Listen
import picocli.CommandLine

/**
 * Picocli converter for [Socks5Listen].
 *
 * Supported formats:
 * - `<port>`
 * - `<listenHost>:<port>`
 */
class Socks5ListenConverter : CommandLine.ITypeConverter<Socks5Listen> {
    override fun convert(value: String): Socks5Listen {
        if (value.isBlank()) {
            throw CommandLine.TypeConversionException("empty --socks5")
        }

        val parts = value.split(':')
        try {
            if (parts.size == 1) {
                val listenPort = parts[0].toInt()
                return Socks5Listen("0.0.0.0", listenPort)
            }
            if (parts.size == 2) {
                val listenHost = parts[0]
                val listenPort = parts[1].toInt()
                return Socks5Listen(listenHost, listenPort)
            }
        } catch (_: NumberFormatException) {
            throw CommandLine.TypeConversionException("bad --socks5: $value")
        }
        throw CommandLine.TypeConversionException("bad --socks5: $value")
    }
}
