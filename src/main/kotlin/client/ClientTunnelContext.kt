package client

import io.netty.channel.Channel
import logger
import java.util.concurrent.atomic.AtomicBoolean

class ClientTunnelContext(
    val tunnelId: String,
    val agentId: String,
    val token: String,
    val forward: Forward,
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

    fun bindLocal(ch: Channel) {
        localCh = ch
    }

    fun bindWs(ch: Channel) {
        wsCh = ch
    }

    fun markReady() {
        ready = true
    }

    fun closeBoth() {
        closeBoth("unknown")
    }

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
