import cli.RootCmd
import picocli.CommandLine
import kotlin.system.exitProcess

fun main(args: Array<String>) {
    val code = CommandLine(RootCmd()).execute(*args)
    exitProcess(code)
}
