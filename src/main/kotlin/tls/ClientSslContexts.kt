package tls

import io.netty.handler.ssl.SslContext
import io.netty.handler.ssl.SslContextBuilder
import io.netty.handler.ssl.util.InsecureTrustManagerFactory
import java.io.File

/**
 * client/agent 侧 TLS（wss）上下文构建工具。
 *
 * 说明：
 * - 默认使用系统信任链（JDK cacerts）
 * - `--ca` 可指定自定义 CA
 * - `--insecure` 跳过验证（仅开发环境）
 */
object ClientSslContexts {
    /**
     * 构造一个用于 WebSocket over TLS（wss）的 [SslContext]。
     *
     * @param insecure 是否跳过证书校验（不安全，仅开发）
     * @param caFile 自定义 CA 证书文件（可为空）
     * @return Netty SslContext
     */
    @Throws(Exception::class)
    fun build(insecure: Boolean, caFile: File?): SslContext {
        val builder = SslContextBuilder.forClient()
        if (insecure) {
            builder.trustManager(InsecureTrustManagerFactory.INSTANCE)
        } else if (caFile != null) {
            builder.trustManager(caFile)
        }
        return builder.build()
    }
}
