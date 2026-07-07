package com.codex.mobile

import android.content.Context
import android.util.Log
import java.io.File
import java.io.InputStreamReader

/**
 * Manages a proot shell session connected to the Alpine Linux rootfs.
 * Output is delivered via [onOutput]; input is sent via [write].
 */
class TerminalSession(private val context: Context) {

    companion object {
        private const val TAG = "TerminalSession"
    }

    var onOutput: ((String) -> Unit)? = null

    private var process: Process? = null

    val isRunning: Boolean
        get() = process?.let {
            try { it.exitValue(); false } catch (_: IllegalThreadStateException) { true }
        } ?: false

    /** Start proot + Alpine /bin/sh. Returns true if the process launched. */
    fun start(): Boolean {
        val paths     = BootstrapInstaller.getPaths(context)
        val prefixDir = paths.prefixDir
        val rootfsDir = DistroInstaller.getRootfsDir(context).absolutePath
        val proot     = "$prefixDir/bin/proot"

        val cmd = mutableListOf(
            proot,
            "-0",                          // fake root
            "--link2symlink",              // handle symlinks on noexec filesystems
            "-r", rootfsDir,               // Alpine rootfs
            "-b", "/proc",
            "-b", "/dev",
            "-b", "/sys",
            "-b", "/dev/urandom:/dev/random",
            // Expose the app files dir so Nastech (installed in Alpine) can reach its data
            "-b", "${context.filesDir.absolutePath}:${context.filesDir.absolutePath}",
            "-w", "/root",
            "/bin/sh", "-i",               // interactive shell — prints prompts to stderr
        )

        val env = mapOf(
            "HOME"            to "/root",
            "TERM"            to "xterm-256color",
            "PATH"            to "/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin",
            "USER"            to "root",
            "LOGNAME"         to "root",
            "SHELL"           to "/bin/sh",
            "PS1"             to "root@localhost:~# ",
            "LANG"            to "en_US.UTF-8",
            // proot needs the Termux bootstrap env vars
            "PREFIX"          to prefixDir,
            "LD_LIBRARY_PATH" to "$prefixDir/lib",
            "LD_PRELOAD"      to "$prefixDir/lib/libtermux-exec.so",
            "TMPDIR"          to "$prefixDir/tmp",
            "PROOT_TMP_DIR"   to "$prefixDir/tmp",
            "ANDROID_DATA"    to "/data",
            "ANDROID_ROOT"    to "/system",
        )

        val pb = ProcessBuilder(cmd)
        pb.environment().clear()
        pb.environment().putAll(env)
        pb.redirectErrorStream(true)  // merge stderr (prompts) into stdout

        return try {
            val proc = pb.start()
            process = proc

            Thread {
                try {
                    val reader = InputStreamReader(proc.inputStream)
                    val buf = CharArray(4096)
                    var n: Int
                    while (reader.read(buf).also { n = it } != -1) {
                        if (n > 0) onOutput?.invoke(String(buf, 0, n))
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Output stream closed: ${e.message}")
                }
                Log.i(TAG, "Shell exited with code ${proc.waitFor()}")
                process = null
                onOutput?.invoke("\r\n[session ended]\r\n")
            }.start()

            Thread.sleep(300)
            Log.i(TAG, "Terminal session started")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start session: ${e.message}")
            false
        }
    }

    /** Send a string directly to the shell (e.g. a command + newline). */
    fun write(data: String) {
        try {
            process?.outputStream?.let {
                it.write(data.toByteArray())
                it.flush()
            }
        } catch (e: Exception) {
            Log.w(TAG, "Write failed: ${e.message}")
        }
    }

    fun stop() {
        process?.destroy()
        process = null
    }
}
