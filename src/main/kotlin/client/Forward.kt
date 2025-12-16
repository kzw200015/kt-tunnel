package client

data class Forward(
    val listenHost: String,
    val listenPort: Int,
    val targetHost: String,
    val targetPort: Int,
)
