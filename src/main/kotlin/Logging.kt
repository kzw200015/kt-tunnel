import org.slf4j.Logger
import org.slf4j.LoggerFactory

/**
 * 获取指定类型的 slf4j [Logger]。
 *
 * 用法：
 * `private val log = logger<MyClass>()`
 */
inline fun <reified T> logger(): Logger = LoggerFactory.getLogger(T::class.java)
