package com.codex.mobile

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.text.SpannableStringBuilder
import android.util.Log
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity

class MainActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "MainActivity"
        // Maximum characters kept in the terminal buffer before trimming
        private const val MAX_BUFFER = 150_000
    }

    private lateinit var loadingOverlay: View
    private lateinit var statusText: TextView
    private lateinit var statusDetail: TextView
    private lateinit var terminalLayout: LinearLayout
    private lateinit var scrollView: ScrollView
    private lateinit var terminalOutput: TextView
    private lateinit var inputField: EditText
    private lateinit var enterButton: ImageButton

    private lateinit var serverManager: CodexServerManager
    private val session = lazy { TerminalSession(this) }

    // ANSI parser state (persists across output chunks)
    private val termBuffer   = SpannableStringBuilder()
    private val ansiColor    = intArrayOf(-1)   // -1 = default
    private val ansiBold     = booleanArrayOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        loadingOverlay  = findViewById(R.id.loadingOverlay)
        statusText      = findViewById(R.id.statusText)
        statusDetail    = findViewById(R.id.statusDetail)
        terminalLayout  = findViewById(R.id.terminalLayout)
        scrollView      = findViewById(R.id.scrollView)
        terminalOutput  = findViewById(R.id.terminalOutput)
        inputField      = findViewById(R.id.inputField)
        enterButton     = findViewById(R.id.enterButton)

        serverManager = CodexServerManager(this)

        requestBatteryOptimizationExemption()
        startForegroundService()
        setupInput()
        startSetupFlow()
    }

    override fun onDestroy() {
        super.onDestroy()
        if (session.isInitialized()) session.value.stop()
        serverManager.stopServer()
        stopService(Intent(this, CodexForegroundService::class.java))
    }

    // ── Battery optimisation ────────────────────────────────────────────────

    private fun requestBatteryOptimizationExemption() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return
        val pm = getSystemService(PowerManager::class.java) ?: return
        if (pm.isIgnoringBatteryOptimizations(packageName)) return
        try {
            @Suppress("BatteryLife")
            startActivity(
                Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                    data = Uri.parse("package:$packageName")
                }
            )
        } catch (e: Exception) {
            Log.w(TAG, "Battery exemption: ${e.message}")
        }
    }

    private fun startForegroundService() {
        val intent = Intent(this, CodexForegroundService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            startForegroundService(intent)
        else
            startService(intent)
    }

    // ── Input handling ──────────────────────────────────────────────────────

    private fun setupInput() {
        val send: () -> Unit = {
            val text = inputField.text.toString()
            inputField.text.clear()
            if (session.isInitialized()) {
                session.value.write(text + "\n")
            }
        }

        enterButton.setOnClickListener { send() }

        inputField.setOnEditorActionListener { _, actionId, event ->
            if (actionId == EditorInfo.IME_ACTION_SEND ||
                (event?.keyCode == KeyEvent.KEYCODE_ENTER && event.action == KeyEvent.ACTION_DOWN)
            ) {
                send()
                true
            } else false
        }
    }

    // ── Setup flow ──────────────────────────────────────────────────────────

    private fun startSetupFlow() {
        showLoading(true)
        setStatus("Initializing…")

        Thread {
            try {
                runSetup()
            } catch (e: Exception) {
                Log.e(TAG, "Setup failed", e)
                runOnUiThread { showError(e.message ?: "Unknown error") }
            }
        }.start()
    }

    private fun runSetup() {
        val paths = BootstrapInstaller.getPaths(this)

        // Step 1: Termux bootstrap
        if (!BootstrapInstaller.isBootstrapInstalled(this)) {
            updateStatus("Extracting environment…")
            BootstrapInstaller.install(this) { updateStatus(it) }
        }

        // Step 2: proot (needed for Alpine chroot)
        if (!serverManager.isProotInstalled()) {
            updateStatus("Installing proot…")
            if (!serverManager.installProot { updateDetail(it) })
                throw RuntimeException("Failed to install proot")
        }

        // Step 3: Nastech install (in Termux bootstrap env)
        if (!serverManager.isNastechInstalled()) {
            updateStatus("Installing Nastech…", "This may take a few minutes")
            serverManager.installNastech { updateDetail(it) }
        }

        // Step 4: Alpine Linux rootfs
        if (!DistroInstaller.isInstalled(this)) {
            updateStatus("Downloading Alpine Linux…", "Full OS ~5 MB")
            if (!DistroInstaller.install(this, paths.prefixDir) { updateDetail(it) })
                throw RuntimeException("Failed to install Alpine Linux")
        }

        // Step 5: Start terminal session
        updateStatus("Launching terminal…")
        val sess = session.value
        sess.onOutput = { raw -> runOnUiThread { appendOutput(raw) } }

        if (!sess.start()) throw RuntimeException("Failed to start shell session")

        // Show terminal
        runOnUiThread {
            showLoading(false)
            terminalLayout.visibility = View.VISIBLE
            inputField.requestFocus()
        }
    }

    // ── Terminal output ─────────────────────────────────────────────────────

    private fun appendOutput(raw: String) {
        AnsiParser.append(raw, termBuffer, ansiColor, ansiBold)

        // Trim oldest content when buffer grows too large
        if (termBuffer.length > MAX_BUFFER) {
            val keep = (MAX_BUFFER * 0.75).toInt()
            termBuffer.delete(0, termBuffer.length - keep)
        }

        terminalOutput.text = termBuffer
        scrollView.post { scrollView.fullScroll(ScrollView.FOCUS_DOWN) }
    }

    // ── UI helpers ──────────────────────────────────────────────────────────

    private fun showError(message: String) {
        AlertDialog.Builder(this)
            .setTitle(R.string.error_title)
            .setMessage(message)
            .setPositiveButton(R.string.retry) { _, _ -> startSetupFlow() }
            .setNegativeButton(R.string.cancel) { _, _ -> finish() }
            .setCancelable(false)
            .show()
    }

    private fun showLoading(show: Boolean) {
        loadingOverlay.visibility  = if (show) View.VISIBLE else View.GONE
    }

    private fun setStatus(text: String, detail: String? = null) {
        statusText.text = text
        if (detail != null) {
            statusDetail.text = detail
            statusDetail.visibility = View.VISIBLE
        } else {
            statusDetail.visibility = View.GONE
        }
    }

    private fun updateStatus(text: String, detail: String? = null) =
        runOnUiThread { setStatus(text, detail) }

    private fun updateDetail(text: String) = runOnUiThread {
        statusDetail.text = text
        statusDetail.visibility = View.VISIBLE
    }
}
