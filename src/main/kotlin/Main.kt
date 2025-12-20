import cli.RootCmd
import picocli.CommandLine
import kotlin.system.exitProcess

/**
 * 应用主入口。
 *
 * 该函数只负责把命令行参数交给 picocli 的根命令 [RootCmd] 解析与执行，并将返回码作为进程退出码。
 *
 * 运行示例：
 * `java -jar tunnel.jar server --bind 8000 --token xxx`
 */
fun main(args: Array<String>) {
    val code = CommandLine(RootCmd()).execute(*args)
    exitProcess(code)
}
