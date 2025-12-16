package agent

import io.netty.channel.Channel
import logger
import java.util.concurrent.atomic.AtomicBoolean

class TunnelContext(
    val tunnelId: String,
    val agentId: String,
    val token: String,
    private val onClose: () -> Unit,
) {
    private val log = logger<TunnelContext>()
    private val closed = AtomicBoolean(false)

    @Volatile
    var targetCh: Channel? = null

    @Volatile
    var dataWsCh: Channel? = null

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
