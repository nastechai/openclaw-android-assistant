package com.codex.mobile

import android.graphics.Typeface
import android.text.Spannable
import android.text.SpannableStringBuilder
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan

/**
 * Minimal ANSI escape-code parser.
 * Converts raw terminal output (with CSI SGR sequences) into a
 * SpannableStringBuilder with Android color/style spans.
 *
 * Supported:
 *   ESC[0m          reset
 *   ESC[1m          bold
 *   ESC[22m         bold off
 *   ESC[30–37m      standard fg colors
 *   ESC[39m         default fg
 *   ESC[90–97m      bright fg colors
 *   ESC[2J / ESC[H  clear screen → emitted as form-feed (ignored by caller)
 *   All other sequences are silently consumed.
 *
 * Non-printable characters other than \n, \r, \t are stripped.
 */
object AnsiParser {

    // ESC [ … <final-byte>
    private val CSI_RE = Regex("\u001B\\[([0-9;=?]*)([A-Za-z])")
    // Any other ESC sequence (ESC + one char)
    private val ESC_RE = Regex("\u001B.")

    // Dark-theme ANSI palette
    private val PALETTE = intArrayOf(
        0xFF1e293b.toInt(), // 0  black
        0xFFf87171.toInt(), // 1  red
        0xFF4ade80.toInt(), // 2  green
        0xFFfbbf24.toInt(), // 3  yellow
        0xFF818cf8.toInt(), // 4  blue
        0xFFc084fc.toInt(), // 5  magenta
        0xFF22d3ee.toInt(), // 6  cyan
        0xFFe2e8f0.toInt(), // 7  white
        0xFF475569.toInt(), // 8  bright black
        0xFFfca5a5.toInt(), // 9  bright red
        0xFF86efac.toInt(), // 10 bright green
        0xFFfcd34d.toInt(), // 11 bright yellow
        0xFFa5b4fc.toInt(), // 12 bright blue
        0xFFd8b4fe.toInt(), // 13 bright magenta
        0xFF67e8f9.toInt(), // 14 bright cyan
        0xFFf8fafc.toInt(), // 15 bright white
    )

    private const val DEFAULT_FG = 0xFFe2e8f0.toInt()

    /**
     * Append [raw] to [sb], applying color/style spans as we go.
     * [fgColor] and [bold] carry state across calls (pass a 1-element array).
     */
    fun append(
        raw: String,
        sb: SpannableStringBuilder,
        fgColor: IntArray,   // fgColor[0]; -1 = default
        bold: BooleanArray,  // bold[0]
    ) {
        // Normalise line endings: \r\n → \n, lone \r → \n
        val normalised = raw
            .replace("\r\n", "\n")
            .replace('\r', '\n')

        var cursor = 0
        val allMatches = CSI_RE.findAll(normalised).toList()

        for (m in allMatches) {
            // ── Plain text before this escape ─────────────────────────────
            if (m.range.first > cursor) {
                appendPlain(normalised, cursor, m.range.first, sb, fgColor[0], bold[0])
            }
            cursor = m.range.last + 1

            val cmd    = m.groupValues[2]
            val params = m.groupValues[1]
                .split(";")
                .mapNotNull { it.toIntOrNull() }

            if (cmd == "m") {
                // SGR — Select Graphic Rendition
                val codes = if (params.isEmpty()) listOf(0) else params
                for (code in codes) {
                    when (code) {
                        0       -> { fgColor[0] = -1; bold[0] = false }
                        1       -> bold[0] = true
                        22      -> bold[0] = false
                        in 30..37  -> fgColor[0] = PALETTE[code - 30]
                        in 90..97  -> fgColor[0] = PALETTE[code - 90 + 8]
                        39      -> fgColor[0] = -1  // default fg
                        // background (40–47, 100–107) — ignored
                    }
                }
            }
            // All other CSI sequences (cursor movement, erase, etc.) ignored
        }

        // ── Remaining text ────────────────────────────────────────────────
        if (cursor < normalised.length) {
            appendPlain(normalised, cursor, normalised.length, sb, fgColor[0], bold[0])
        }

        // Strip any leftover bare ESC sequences
        // (already consumed by the regex above, but just in case)
    }

    private fun appendPlain(
        src: String,
        from: Int,
        to: Int,
        sb: SpannableStringBuilder,
        fg: Int,
        bold: Boolean,
    ) {
        val text = src.substring(from, to).filter { c ->
            c == '\n' || c == '\t' || c >= ' '
        }
        if (text.isEmpty()) return

        val start = sb.length
        sb.append(text)
        val end   = sb.length

        val color = if (fg == -1) DEFAULT_FG else fg
        sb.setSpan(ForegroundColorSpan(color), start, end, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
        if (bold) {
            sb.setSpan(StyleSpan(Typeface.BOLD), start, end, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
    }
}
