package com.pit.phonecleaner.core

import java.util.concurrent.TimeUnit

/** Простая обёртка над `su` для режима root */
object RootShell {
    data class Result(val code: Int, val out: String) {
        val ok get() = code == 0
    }

    @Volatile
    private var cached: Boolean? = null

    fun isAvailable(force: Boolean = false): Boolean {
        if (!force) cached?.let { return it }
        val r = try {
            run("id", timeoutSec = 15).out.contains("uid=0")
        } catch (e: Exception) {
            false
        }
        cached = r
        return r
    }

    fun run(vararg cmds: String, timeoutSec: Long = 120): Result {
        val p = try {
            ProcessBuilder("su").redirectErrorStream(true).start()
        } catch (e: Exception) {
            return Result(-1, e.message ?: "su not found")
        }
        val sb = StringBuilder()
        val reader = Thread {
            try {
                p.inputStream.bufferedReader().forEachLine { sb.appendLine(it) }
            } catch (_: Exception) {
            }
        }
        reader.start()
        try {
            p.outputStream.bufferedWriter().use { w ->
                cmds.forEach { w.write(it); w.newLine() }
                w.write("exit\n")
                w.flush()
            }
        } catch (_: Exception) {
        }
        val finished = p.waitFor(timeoutSec, TimeUnit.SECONDS)
        if (!finished) p.destroy()
        reader.join(2000)
        return Result(if (finished) p.exitValue() else -2, sb.toString())
    }

    fun q(s: String) = "'" + s.replace("'", "'\\''") + "'"

    /** Очистить содержимое каталога (сам каталог остаётся) */
    fun clearDirCmd(dir: String) =
        "if [ -d ${q(dir)} ]; then find ${q(dir)} -mindepth 1 -delete 2>/dev/null || rm -rf ${q(dir)}/* ${q(dir)}/.[!.]* 2>/dev/null; fi"
}
