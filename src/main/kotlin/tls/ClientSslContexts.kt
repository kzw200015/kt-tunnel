package tls

import io.netty.handler.ssl.SslContext
import io.netty.handler.ssl.SslContextBuilder
import io.netty.handler.ssl.util.InsecureTrustManagerFactory
import java.io.File

object ClientSslContexts {
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
