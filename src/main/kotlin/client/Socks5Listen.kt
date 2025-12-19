package client

/**
 * SOCKS5 listen config.
 *
 * @param listenHost local listen host (e.g. `0.0.0.0` / `127.0.0.1`)
 * @param listenPort local listen port
 */
data class Socks5Listen(
    val listenHost: String,
    val listenPort: Int,
)
