package com.codex.mobile

import android.content.Context
import android.util.Log
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL

/**
 * Manages the lifecycle of the Nastech agent running inside the Termux
 * bootstrap environment.
 */
class CodexServerManager(private val context: Context) {

    companion object {
        private const val TAG = "NastechServerManager"
        const val NASTECH_PORT = 9119
    }

    private var nastechProcess: Process? = null

    // ── Shell helpers ──────────────────────────────────────────────────────

    fun runInPrefix(
        command: String,
        onOutput: ((String) -> Unit)? = null,
    ): Int {
        val paths = BootstrapInstaller.getPaths(context)
        val env = buildEnvironment(paths)

        val shell = "${paths.prefixDir}/bin/sh"
        val pb = ProcessBuilder(shell, "-c", command)
        pb.environment().clear()
        pb.environment().putAll(env)
        pb.directory(File(paths.homeDir))
        pb.redirectErrorStream(true)

        val proc = pb.start()
        val reader = BufferedReader(InputStreamReader(proc.inputStream))
        var line = reader.readLine()
        while (line != null) {
            Log.d(TAG, line)
            onOutput?.invoke(line)
            line = reader.readLine()
        }
        return proc.waitFor()
    }

    // ── Install checks ─────────────────────────────────────────────────────

    fun isProotInstalled(): Boolean {
        val paths = BootstrapInstaller.getPaths(context)
        return File(paths.prefixDir, "bin/proot").exists()
    }

    fun installProot(onProgress: (String) -> Unit): Boolean {
        val paths = BootstrapInstaller.getPaths(context)
        val prefix = paths.prefixDir
        val termuxPrefix = "/data/data/com.termux/files/usr"

        onProgress("Downloading proot…")

        val downloadCmd = """
            cd $prefix/tmp &&
            apt-get update --allow-insecure-repositories 2>&1;
            apt-get download --allow-unauthenticated proot libtalloc 2>&1
        """.trimIndent()

        val dlCode = runInPrefix(downloadCmd, onOutput = { onProgress(it) })
        if (dlCode != 0) {
            Log.e(TAG, "apt-get download proot failed with code $dlCode")
            return false
        }

        onProgress("Extracting proot…")
        val extractCmd = """
            cd $prefix/tmp &&
            mkdir -p _proot_stage &&
            for deb in proot*.deb libtalloc*.deb; do
                [ -f "${'$'}deb" ] && dpkg-deb -x "${'$'}deb" _proot_stage/ 2>&1
            done &&
            if [ -d "_proot_stage$termuxPrefix" ]; then
                cp -a _proot_stage$termuxPrefix/* "$prefix/" 2>&1
            elif [ -d "_proot_stage/usr" ]; then
                cp -a _proot_stage/usr/* "$prefix/" 2>&1
            fi &&
            chmod 700 "$prefix/bin/proot" 2>/dev/null
            rm -rf _proot_stage proot*.deb libtalloc*.deb 2>/dev/null
            echo "proot installed"
        """.trimIndent()

        val extractCode = runInPrefix(extractCmd, onOutput = { onProgress(it) })
        if (extractCode != 0) {
            Log.e(TAG, "proot extract failed with code $extractCode")
            return false
        }

        return isProotInstalled()
    }

    // ── Nastech ───────────────────────────────────────────────────────────────

    /**
     * Returns true when the Nastech venv Python exists.
     */
    fun isNastechInstalled(): Boolean {
        val paths = BootstrapInstaller.getPaths(context)
        val venvPython = File("${paths.homeDir}/.nastech/nastech-agent/venv/bin/python")
        return venvPython.exists()
    }

    /**
     * Install Nastech using the official install script:
     * curl -fsSL https://raw.githubusercontent.com/nastechai/nastech-agent/main/scripts/install.sh | bash
     */
    fun installNastech(onProgress: (String) -> Unit): Boolean {
        val paths = BootstrapInstaller.getPaths(context)

        onProgress("Running Nastech install script…")
        val installCmd = """
            set -o pipefail
            export TERMUX_VERSION=1
            export PREFIX="${paths.prefixDir}"
            export HOME="${paths.homeDir}"
            export NASTECH_HOME="${paths.homeDir}/.nastech"
            curl -fsSL https://raw.githubusercontent.com/nastechai/nastech-agent/main/scripts/install.sh | bash 2>&1
        """.trimIndent()

        val code = runInPrefix(installCmd) { onProgress(it) }
        if (code != 0) {
            Log.w(TAG, "Nastech install script exited $code — checking if installed anyway")
        }

        return isNastechInstalled()
    }

    /**
     * Start the Nastech gateway on NASTECH_PORT (9119).
     */
    fun startNastech(): Boolean {
        nastechProcess?.let {
            return try { it.exitValue(); false } catch (_: IllegalThreadStateException) { true }
        }

        if (!isNastechInstalled()) {
            Log.w(TAG, "Nastech not installed — skipping start")
            return false
        }

        val paths   = BootstrapInstaller.getPaths(context)
        val python  = "${paths.homeDir}/.nastech/nastech-agent/venv/bin/python"
        val workDir = "${paths.homeDir}/.nastech/nastech-agent"

        val env = buildEnvironment(paths).toMutableMap()
        env["NASTECH_HOME"]         = "${paths.homeDir}/.nastech"
        env["NASTECH_GATEWAY_PORT"] = NASTECH_PORT.toString()

        val pb = ProcessBuilder(python, "-m", "nastech_cli.main", "gateway")
        pb.environment().clear()
        pb.environment().putAll(env)
        pb.directory(File(workDir))
        pb.redirectErrorStream(true)

        val proc = pb.start()
        nastechProcess = proc

        Thread {
            val reader = BufferedReader(InputStreamReader(proc.inputStream))
            var line = reader.readLine()
            while (line != null) {
                Log.d(TAG, "[nastech] $line")
                line = reader.readLine()
            }
            Log.i(TAG, "Nastech exited with code: ${proc.waitFor()}")
            nastechProcess = null
        }.start()

        Thread.sleep(3000)
        val alive = nastechProcess?.let {
            try { it.exitValue(); false } catch (_: IllegalThreadStateException) { true }
        } ?: false
        Log.i(TAG, "Nastech gateway started=$alive on :$NASTECH_PORT")
        return alive
    }

    /**
     * Poll http://127.0.0.1:9119/ until it responds or timeout.
     */
    fun waitForNastech(timeoutMs: Long = 90_000): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            try {
                val conn = URL("http://127.0.0.1:$NASTECH_PORT/").openConnection() as HttpURLConnection
                conn.connectTimeout = 1_000
                conn.readTimeout   = 1_000
                val code = conn.responseCode
                conn.disconnect()
                if (code < 500) return true
            } catch (_: Exception) {}
            Thread.sleep(500)
        }
        return false
    }

    fun stopNastech() {
        nastechProcess?.destroy()
        nastechProcess = null
    }

    fun stopServer() {
        stopNastech()
        Log.i(TAG, "Nastech stopped")
    }

    // ── Environment ───────────────────────────────────────────────────────────

    private fun buildEnvironment(paths: BootstrapInstaller.Paths): Map<String, String> {
        return mapOf(
            "PREFIX"              to paths.prefixDir,
            "HOME"                to paths.homeDir,
            "PATH"                to "${paths.prefixDir}/bin:${paths.prefixDir}/bin/applets:/system/bin",
            "LD_LIBRARY_PATH"     to "${paths.prefixDir}/lib",
            "LD_PRELOAD"          to "${paths.prefixDir}/lib/libtermux-exec.so",
            "TERMUX_PREFIX"       to paths.prefixDir,
            "TERMUX__PREFIX"      to paths.prefixDir,
            "LANG"                to "en_US.UTF-8",
            "TMPDIR"              to paths.tmpDir,
            "TMP"                 to paths.tmpDir,
            "TEMP"                to paths.tmpDir,
            "PROOT_TMP_DIR"       to paths.tmpDir,
            "TERM"                to "xterm-256color",
            "ANDROID_DATA"        to "/data",
            "ANDROID_ROOT"        to "/system",
            "APT_CONFIG"          to "${paths.prefixDir}/etc/apt/apt.conf",
            "DPKG_ADMINDIR"       to "${paths.prefixDir}/var/lib/dpkg",
            "SSL_CERT_FILE"       to "${paths.prefixDir}/etc/tls/cert.pem",
            "SSL_CERT_DIR"        to "/system/etc/security/cacerts",
            "CURL_CA_BUNDLE"      to "${paths.prefixDir}/etc/tls/cert.pem",
            "GIT_SSL_CAINFO"      to "${paths.prefixDir}/etc/tls/cert.pem",
            "GIT_CONFIG_NOSYSTEM" to "1",
            "GIT_EXEC_PATH"       to "${paths.prefixDir}/libexec/git-core",
            "GIT_TEMPLATE_DIR"    to "${paths.prefixDir}/share/git-core/templates",
            "OPENSSL_CONF"        to "${paths.prefixDir}/etc/tls/openssl.cnf",
            "CONTAINER"           to "1",
        )
    }
}
