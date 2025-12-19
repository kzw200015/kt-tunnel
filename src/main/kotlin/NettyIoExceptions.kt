import io.netty.channel.ConnectTimeoutException
import io.netty.handler.timeout.ReadTimeoutException
import io.netty.handler.timeout.WriteTimeoutException
import java.io.IOException
import java.net.SocketTimeoutException

fun isIgnorableNettyIoException(cause: Throwable): Boolean {
    val root = rootCause(cause)
    if (root is ReadTimeoutException || root is WriteTimeoutException || root is ConnectTimeoutException) {
        return true
    }
    if (root is SocketTimeoutException) {
        return true
    }
    if (root is IOException) {
        val msg = root.message ?: return false
        val m = msg.lowercase()
        if (m.contains("connection reset")) return true
        if (m.contains("broken pipe")) return true
        if (m.contains("connection timed out")) return true
        if (m.contains("timed out")) return true
        if (m.contains("timeout")) return true
    }
    return false
}

fun nettyIoExceptionSummary(cause: Throwable): String = rootCause(cause).toString()

private fun rootCause(t: Throwable): Throwable {
    var cur = t
    while (cur.cause != null) {
        cur = cur.cause!!
    }
    return cur
}
