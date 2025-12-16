package cli

import client.Forward
import picocli.CommandLine

class ForwardConverter : CommandLine.ITypeConverter<Forward> {
    override fun convert(value: String): Forward {
        if (value.isBlank()) {
            throw CommandLine.TypeConversionException("empty --forward")
        }

        val parts = value.split(':')
        try {
            if (parts.size == 3) {
                val listenPort = parts[0].toInt()
                val targetHost = parts[1]
                val targetPort = parts[2].toInt()
                return Forward("0.0.0.0", listenPort, targetHost, targetPort)
            }
            if (parts.size == 4) {
                val listenHost = parts[0]
                val listenPort = parts[1].toInt()
                val targetHost = parts[2]
                val targetPort = parts[3].toInt()
                return Forward(listenHost, listenPort, targetHost, targetPort)
            }
        } catch (_: NumberFormatException) {
            throw CommandLine.TypeConversionException("bad --forward: $value")
        }
        throw CommandLine.TypeConversionException("bad --forward: $value")
    }
}
