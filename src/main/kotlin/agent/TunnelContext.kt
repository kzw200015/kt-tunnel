package agent

import io.netty.channel.Channel
import logger
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Agent 侧单条隧道的上下文对象。
 *
 * 该对象用于在多个 handler/连接之间共享必要状态，并统一管理隧道生命周期：
 * - target TCP channel（连接内网服务）
 * - data WS channel（连接公网 server 的 `/ws/agent/data`）
 * - 幂等关闭逻辑（任一侧断开触发 closeBoth）
 */
class TunnelContext(
    /** tunnelId：由 client 生成并在三端之间透传。 */
    val tunnelId: String,
    /** agentId：用于 data bind 消息。 */
    val agentId: String,
    /** token：用于 data bind 消息。 */
    val token: String,
    /** 隧道关闭后的回调（通常用于从管理器 map 中移除）。 */
    private val onClose: () -> Unit,
) {
    private val log = logger<TunnelContext>()
    private val closed = AtomicBoolean(false)

    @Volatile
    var targetCh: Channel? = null

    @Volatile
    var dataWsCh: Channel? = null

    /**
     * 同时关闭 target 与 data WS，并触发清理回调（带原因）。
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
            "tunnel closed (forwarding end): tunnelId={}, agentId={}, reason={}, targetActive={}, dataWsActive={}, targetRemote={}, dataWsRemote={}",
            tunnelId,
            agentId,
            reason,
            targetCh?.isActive ?: false,
            dataWsCh?.isActive ?: false,
            targetCh?.remoteAddress(),
            dataWsCh?.remoteAddress(),
        )
        targetCh?.close()
        dataWsCh?.close()
        onClose()
    }
}
