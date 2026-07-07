package com.codex.mobile

import android.content.Context
import android.util.Log
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.net.URL

/**
 * Downloads and extracts the Alpine Linux ARM64 minimal rootfs into the app's
 * private files directory, then sets it up for use with proot.
 *
 * Rootfs path: <filesDir>/alpine-rootfs/
 */
object DistroInstaller {

    private const val TAG = "DistroInstaller"

    // Alpine Linux 3.20 minimal rootfs for aarch64 (~5 MB compressed)
    private const val ROOTFS_URL =
        "https://dl-cdn.alpinelinux.org/alpine/v3.20/releases/aarch64/alpine-minirootfs-3.20.3-aarch64.tar.gz"

    fun getRootfsDir(context: Context): File =
        File(context.filesDir, "alpine-rootfs")

    fun isInstalled(context: Context): Boolean =
        File(getRootfsDir(context), "bin/sh").exists()

    /**
     * Full install:
     *   1. Download the rootfs tarball.
     *   2. Extract it using the Termux-bootstrap tar binary.
     *   3. Write /etc/resolv.conf and create /root.
     *   4. Update Alpine package index and install essentials.
     *   5. Run the Nastech install script inside Alpine.
     */
    fun install(
        context: Context,
        prefixDir: String,
        onProgress: (String) -> Unit,
    ): Boolean {
        val rootfsDir = getRootfsDir(context)
        val tarball   = File(context.filesDir, "alpine-rootfs.tar.gz")

        // ── 1. Download ────────────────────────────────────────────────────
        onProgress("Downloading Alpine Linux ARM64…")
        try {
            URL(ROOTFS_URL).openConnection().also { it.connect() }.getInputStream().use { inp ->
                tarball.outputStream().use { out ->
                    val buf = ByteArray(65536)
                    var n: Int
                    while (inp.read(buf).also { n = it } != -1) out.write(buf, 0, n)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Download failed: ${e.message}")
            return false
        }

        // ── 2. Extract ─────────────────────────────────────────────────────
        onProgress("Extracting Alpine Linux…")
        rootfsDir.mkdirs()

        val exitCode = shell(
            prefixDir,
            "tar -xzf ${tarball.absolutePath} -C ${rootfsDir.absolutePath} 2>&1",
            onProgress,
        )
        tarball.delete()

        if (exitCode != 0) {
            Log.e(TAG, "tar extraction failed (code $exitCode)")
            return false
        }

        // ── 3. Basic setup ─────────────────────────────────────────────────
        onProgress("Configuring Alpine Linux…")
        File(rootfsDir, "etc/resolv.conf").writeText(
            "nameserver 8.8.8.8\nnameserver 1.1.1.1\n"
        )
        File(rootfsDir, "etc/hosts").writeText(
            "127.0.0.1  localhost\n::1        localhost\n"
        )
        File(rootfsDir, "root").mkdirs()
        File(rootfsDir, "tmp").mkdirs()

        // ── 4. Install essential packages ──────────────────────────────────
        onProgress("Installing packages (curl bash python3 git)…")
        prootShell(
            prefixDir,
            rootfsDir.absolutePath,
            "apk update 2>&1 && apk add --no-cache bash curl python3 py3-pip git 2>&1",
            onProgress,
        )

        // ── 5. Install Nastech inside Alpine ───────────────────────────────
        onProgress("Installing Nastech inside Alpine…")
        prootShell(
            prefixDir,
            rootfsDir.absolutePath,
            "curl -fsSL https://raw.githubusercontent.com/nastechai/nastech-agent/main/scripts/install.sh | bash 2>&1",
            onProgress,
        )

        return isInstalled(context)
    }

    // ── proot helpers ───────────────────────────────────────────────────────

    /**
     * Run a command through proot inside the Alpine rootfs.
     * Returns the exit code.
     */
    fun prootShell(
        prefixDir: String,
        rootfsPath: String,
        command: String,
        onOutput: ((String) -> Unit)? = null,
    ): Int {
        val proot = "$prefixDir/bin/proot"
        val fullCmd = listOf(
            proot,
            "-0",
            "--link2symlink",
            "-r", rootfsPath,
            "-b", "/proc",
            "-b", "/dev",
            "-b", "/sys",
            "-b", "/dev/urandom:/dev/random",
            "-w", "/root",
            "/bin/sh", "-c", command,
        )

        val env = buildEnv(prefixDir)
        val pb  = ProcessBuilder(fullCmd)
        pb.environment().clear()
        pb.environment().putAll(env)
        pb.redirectErrorStream(true)

        val proc   = pb.start()
        val reader = BufferedReader(InputStreamReader(proc.inputStream))
        var line   = reader.readLine()
        while (line != null) {
            Log.d(TAG, "[proot] $line")
            onOutput?.invoke(line)
            line = reader.readLine()
        }
        return proc.waitFor()
    }

    /** Run a command in the Termux prefix shell (not inside Alpine). */
    private fun shell(
        prefixDir: String,
        command: String,
        onOutput: ((String) -> Unit)? = null,
    ): Int {
        val env = buildEnv(prefixDir)
        val pb  = ProcessBuilder("$prefixDir/bin/sh", "-c", command)
        pb.environment().clear()
        pb.environment().putAll(env)
        pb.redirectErrorStream(true)

        val proc   = pb.start()
        val reader = BufferedReader(InputStreamReader(proc.inputStream))
        var line   = reader.readLine()
        while (line != null) {
            Log.d(TAG, "[shell] $line")
            onOutput?.invoke(line)
            line = reader.readLine()
        }
        return proc.waitFor()
    }

    private fun buildEnv(prefixDir: String) = mapOf(
        "PREFIX"          to prefixDir,
        "HOME"            to "/root",
        "PATH"            to "$prefixDir/bin:$prefixDir/bin/applets:/system/bin",
        "LD_LIBRARY_PATH" to "$prefixDir/lib",
        "LD_PRELOAD"      to "$prefixDir/lib/libtermux-exec.so",
        "TMPDIR"          to "$prefixDir/tmp",
        "PROOT_TMP_DIR"   to "$prefixDir/tmp",
        "ANDROID_DATA"    to "/data",
        "ANDROID_ROOT"    to "/system",
        "TERM"            to "xterm-256color",
        "LANG"            to "en_US.UTF-8",
    )
}
