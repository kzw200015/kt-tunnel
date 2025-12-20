package cli

import picocli.CommandLine

data class BindAddress(val host: String, val port: Int)

class BindAddressConverter : CommandLine.ITypeConverter<BindAddress> {
    override fun convert(value: String): BindAddress {
        if (value.isBlank()) {
            throw CommandLine.TypeConversionException("empty --bind")
        }

        val parts = value.split(':')
        try {
            if (parts.size == 1) {
                return BindAddress("0.0.0.0", parts[0].toInt())
            }
            if (parts.size == 2) {
                return BindAddress(parts[0], parts[1].toInt())
            }
        } catch (_: NumberFormatException) {
            throw CommandLine.TypeConversionException("bad --bind: $value")
        }
        throw CommandLine.TypeConversionException("bad --bind: $value")
    }
}
