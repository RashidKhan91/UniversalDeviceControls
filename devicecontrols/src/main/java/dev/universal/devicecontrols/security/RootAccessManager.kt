package dev.universal.devicecontrols.security

import android.os.SystemClock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.util.concurrent.TimeUnit

internal data class RootCommandResult(val authorized: Boolean, val exitCode: Int?, val output: String, val timedOut: Boolean = false)

internal fun interface RootCommandRunner {
    suspend fun execute(command: String, timeoutMs: Long): RootCommandResult
}

/** Runs only library-built allowlisted commands. No public arbitrary shell API is exposed. */
internal class RootAccessManager internal constructor(
    private val timeoutMs: Long = 5_000L,
    private val runner: RootCommandRunner = SuRootCommandRunner()
) {
    internal suspend fun run(command: String): RootCommandResult = runner.execute(command, timeoutMs)

    internal suspend fun detect(): Boolean {
        val result = run("id -u")
        return result.exitCode == 0 && result.output.trim() == "0" && !result.timedOut
    }
}

private class SuRootCommandRunner : RootCommandRunner {
    override suspend fun execute(command: String, timeoutMs: Long): RootCommandResult = withContext(Dispatchers.IO) {
        val started = SystemClock.elapsedRealtime()
        var process: Process? = null
        val output = ByteArrayOutputStream()
        try {
            process = ProcessBuilder("su", "-c", command).redirectErrorStream(true).start()
            val p = process
            val reader = Thread { p.inputStream.use { it.copyTo(output) } }.apply { isDaemon = true; start() }
            val done = p.waitFor(timeoutMs, TimeUnit.MILLISECONDS)
            if (!done) {
                p.destroyForcibly()
                reader.join(250)
                return@withContext RootCommandResult(false, null, String(output.toByteArray(), Charsets.UTF_8), timedOut = true)
            }
            reader.join((timeoutMs - (SystemClock.elapsedRealtime() - started)).coerceAtLeast(250))
            val text = String(output.toByteArray(), Charsets.UTF_8)
            val code = p.exitValue()
            RootCommandResult(code == 0 && (text.contains("uid=0") || !command.contains("id -u")), code, text)
        } catch (e: Exception) {
            process?.destroyForcibly()
            RootCommandResult(false, null, e.message.orEmpty())
        }
    }
}
