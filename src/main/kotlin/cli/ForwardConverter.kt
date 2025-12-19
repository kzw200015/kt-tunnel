package cli

import client.Forward
import picocli.CommandLine

/**
 * [Forward] 的 picocli 参数转换器。
 *
 * 将 `--forward` 的字符串表示解析为转发规则对象。
 *
 * 支持格式：
 * - `<listenPort>:<targetHost>:<targetPort>`
 * - `<listenHost>:<listenPort>:<targetHost>:<targetPort>`
 */
class ForwardConverter : CommandLine.ITypeConverter<Forward> {
    /**
     * 解析 `--forward` 参数。
     *
     * @param value 转发规则字符串
     * @return 转发规则对象
     * @throws CommandLine.TypeConversionException 格式非法或端口不是数字时抛出
     */
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
