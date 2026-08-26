package com.ciallo.hyperbackground

import kotlin.concurrent.thread

/**
 * 以 root（su -c）执行命令的轻量封装。
 *
 * 参照 HyperIsland 的 RootShell 实现：用独立守护线程实时排空 stderr，
 * 主线程读取 stdout，最后再 waitFor，避免管道缓冲写满导致子进程阻塞、
 * 进而让 waitFor 超时（这会让批量 force-stop 中排在后面的包“看起来失效”）。
 */
internal object RootShell {

    data class CommandResult(
        val stdout: String,
        val stderr: String,
        val exitCode: Int,
    ) {
        val success: Boolean get() = exitCode == 0
    }

    fun run(command: String): CommandResult {
        val process = Runtime.getRuntime().exec(arrayOf("su", "-c", command))

        val stderr = StringBuilder()
        val stderrThread = thread(start = true, isDaemon = true, name = "root-shell-stderr") {
            runCatching {
                process.errorStream.bufferedReader().use { reader -> stderr.append(reader.readText()) }
            }
        }

        val stdout = runCatching {
            process.inputStream.bufferedReader().use { it.readText() }
        }.getOrDefault("")

        val exitCode = process.waitFor()
        stderrThread.join()

        return CommandResult(
            stdout = stdout.trim(),
            stderr = stderr.toString().trim(),
            exitCode = exitCode,
        )
    }
}
