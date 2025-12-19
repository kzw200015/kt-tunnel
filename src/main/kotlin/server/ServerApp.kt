package server

import io.netty.bootstrap.ServerBootstrap
import io.netty.channel.ChannelOption
import io.netty.channel.EventLoopGroup
import io.netty.channel.MultiThreadIoEventLoopGroup
import io.netty.channel.nio.NioIoHandler
import io.netty.channel.socket.nio.NioServerSocketChannel
import io.netty.handler.ssl.SslContext
import io.netty.handler.ssl.SslContextBuilder
import io.netty.handler.ssl.SslProvider
import io.netty.pkitesting.CertificateBuilder
import logger
import server.handler.WsServerInitializer
import java.io.File
import java.net.InetSocketAddress
import java.time.Duration
import java.time.Instant
import java.nio.file.Files
import java.nio.file.Path
import java.util.Base64

/**
 * Server 端主程序：在公网监听并承载三端 WebSocket endpoint。
 *
 * Server 的职责：
 * - 维护在线 agent 的 control 连接（agentId -> Channel）
 * - 维护隧道状态（pending/active），并负责 client 与 agentData 的配对绑定
 * - 在两端之间转发 BinaryWebSocketFrame（透明转发，不解析 payload）
 */
class ServerApp(private val config: Config) {
    private val log = logger<ServerApp>()

    /**
     * 按 `--cert/--key` 构造 server 侧 [SslContext]。
     *
     * @param certFile PEM 证书文件（可为空）
     * @param keyFile PEM 私钥文件（可为空）
     * @param selfSignedTlsHost 自签证书使用的 host（可为空）
     * @return 启用 TLS 时返回 SslContext，否则返回 null
     */
    private fun buildServerSslContext(
        certFile: File?,
        keyFile: File?,
        selfSignedTlsHost: String?,
    ): Pair<SslContext?, SelfSignedTlsFiles?> {
        if (selfSignedTlsHost != null) {
            if (certFile != null || keyFile != null) {
                throw IllegalArgumentException("--self-signed-tls conflicts with --cert/--key")
            }
            val now = Instant.now()
            val builder = CertificateBuilder()
            builder.setIsCertificateAuthority(true)
            builder.subject(if (selfSignedTlsHost.contains("=")) selfSignedTlsHost else "CN=$selfSignedTlsHost")
            builder.notBefore(now.minusSeconds(60))
            builder.notAfter(now.plus(Duration.ofDays(3650)))
            builder.algorithm(CertificateBuilder.Algorithm.ecp256)
            val bundle = builder.buildSelfSigned()

            val dir = Files.createTempDirectory("kt-tunnel-self-signed-")
            val files =
                SelfSignedTlsFiles(
                    certFile = dir.resolve("server.crt").toFile(),
                    keyFile = dir.resolve("server.key").toFile(),
                    dir = dir,
                )
            Files.write(files.certFile.toPath(), pem("CERTIFICATE", bundle.certificate.encoded))
            Files.write(files.keyFile.toPath(), pem("PRIVATE KEY", bundle.keyPair.private.encoded))
            return SslContextBuilder.forServer(files.certFile, files.keyFile).build() to files
        }
        if (certFile == null && keyFile == null) {
            return null to null
        }
        if (certFile == null || keyFile == null) {
            throw IllegalArgumentException("both --cert and --key must be provided to enable TLS")
        }
        return SslContextBuilder.forServer(certFile, keyFile).build() to null
    }

    /**
     * 启动并阻塞运行，直到 server channel 关闭（或进程被外部终止）。
     *
     * 该方法会创建 Netty boss/worker EventLoopGroup，并在 finally 中优雅关闭。
     */
    fun runUntilShutdown() {
        val (sslContext, selfSignedTlsFiles) =
            buildServerSslContext(config.certFile, config.keyFile, config.selfSignedTlsHost)

        val agentRegistry = AgentRegistry()
        val tunnelRegistry = TunnelRegistry(config.token, config.pendingTimeout, agentRegistry)

        val bossGroup: EventLoopGroup = MultiThreadIoEventLoopGroup(1, NioIoHandler.newFactory())
        val workerGroup: EventLoopGroup = MultiThreadIoEventLoopGroup(NioIoHandler.newFactory())
        try {
            if (selfSignedTlsFiles != null) {
                log.info(
                    "self-signed tls enabled: host={}, cert={}, key={}",
                    config.selfSignedTlsHost,
                    selfSignedTlsFiles.certFile,
                    selfSignedTlsFiles.keyFile,
                )
            }
            val bootstrap =
                ServerBootstrap()
                    .group(bossGroup, workerGroup)
                    .channel(NioServerSocketChannel::class.java)
                    .childHandler(WsServerInitializer(sslContext, config.token, tunnelRegistry, agentRegistry))
                    .childOption(ChannelOption.TCP_NODELAY, true)
                    .childOption(ChannelOption.SO_KEEPALIVE, true)

            val ch =
                bootstrap
                    .bind(InetSocketAddress(config.bindHost, config.port))
                    .sync()
                    .channel()

            log.info(
                "server listening on {}:{} ({})",
                config.bindHost,
                config.port,
                if (sslContext == null) "ws" else "wss",
            )
            ch.closeFuture().sync()
        } finally {
            selfSignedTlsFiles?.delete()
            bossGroup.shutdownGracefully()
            workerGroup.shutdownGracefully()
        }
    }

    private data class SelfSignedTlsFiles(
        val certFile: File,
        val keyFile: File,
        val dir: Path,
    ) {
        fun delete() {
            Files.deleteIfExists(certFile.toPath())
            Files.deleteIfExists(keyFile.toPath())
            Files.deleteIfExists(dir)
        }
    }

    private fun pem(type: String, der: ByteArray): ByteArray {
        val base64 = Base64.getMimeEncoder(64, "\n".toByteArray()).encodeToString(der)
        val pem = "-----BEGIN $type-----\n$base64\n-----END $type-----\n"
        return pem.toByteArray(Charsets.US_ASCII)
    }

    data class Config(
        /** 监听地址。 */
        val bindHost: String,
        /** 监听端口。 */
        val port: Int,
        /** 共享 token（MVP：静态密钥）。 */
        val token: String,
        /** TLS 证书文件（PEM，可为空；为空则不启用 TLS）。 */
        val certFile: File?,
        /** TLS 私钥文件（PEM，可为空；为空则不启用 TLS）。 */
        val keyFile: File?,
        /** 自签 TLS host（可为空；不为空则生成自签证书并启用 TLS）。 */
        val selfSignedTlsHost: String?,
        /** pending 隧道等待超时：client OPEN 后等待 agent DATA_BIND 的最大时长。 */
        val pendingTimeout: Duration,
    )
}
