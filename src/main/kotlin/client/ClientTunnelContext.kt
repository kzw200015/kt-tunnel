package client

import io.netty.channel.Channel
import logger
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Client 侧单条隧道的上下文对象。
 *
 * 用于在本地 TCP handler 与 WS handler 之间共享必要状态，并统一管理两端的关闭：
 * - localCh：被转发的本地连接
 * - wsCh：到 server 的 client tunnel WS 连接
 * - ready：是否已收到 server 的 `CLIENT_TUNNEL_OK`
 */
class ClientTunnelContext(
    /** tunnelId：由 client 生成（UUID 字符串）。 */
    val tunnelId: String,
    /** agentId：用于 OPEN 消息，server 侧据此选择 agent。 */
    val agentId: String,
    /** token：用于 OPEN 消息。 */
    val token: String,
    /** 转发规则：决定 targetHost/targetPort。 */
    val forward: Forward,
    /** 隧道关闭后的回调（预留扩展）。 */
    private val onClose: () -> Unit,
) {
    private val log = logger<ClientTunnelContext>()
    private val closed = AtomicBoolean(false)

    @Volatile
    var localCh: Channel? = null
        private set

    @Volatile
    var wsCh: Channel? = null
        private set

    @Volatile
    var ready: Boolean = false
        private set

    /** 绑定本地 TCP channel。 */
    fun bindLocal(ch: Channel) {
        localCh = ch
    }

    /** 绑定 WS channel。 */
    fun bindWs(ch: Channel) {
        wsCh = ch
    }

    /** 标记隧道已 ready（已收到 server 的 OK）。 */
    fun markReady() {
        ready = true
    }

    /** 同时关闭本地连接与 WS 连接，并触发清理回调。 */
    fun closeBoth() {
        closeBoth("unknown")
    }

    /**
     * 同时关闭本地连接与 WS 连接，并触发清理回调（带原因）。
     *
     * 该方法保证幂等（只会执行一次关闭逻辑），且会在首次关闭时记录一条 INFO 日志用于排障。
     *
     * @param reason 关闭原因（用于日志展示）
     */
    fun closeBoth(reason: String) {
        if (!closed.compareAndSet(false, true)) {
            return
        }
        log.info(
            "tunnel closed (forwarding end): tunnelId={}, agentId={}, listen={}:{}, target={}:{}, reason={}, ready={}, localRemote={}, wsRemote={}",
            tunnelId,
            agentId,
            forward.listenHost,
            forward.listenPort,
            forward.targetHost,
            forward.targetPort,
            reason,
            ready,
            localCh?.remoteAddress(),
            wsCh?.remoteAddress(),
        )
        localCh?.close()
        wsCh?.close()
        onClose()
    }
}
