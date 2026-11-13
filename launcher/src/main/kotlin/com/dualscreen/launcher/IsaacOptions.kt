package com.dualscreen.launcher

import java.io.File

/**
 * Isaac remembers windowed mode in options.ini. In a window, Wine leaves a thin
 * frame/background around the client on the top screen; fullscreen fills it.
 * Only the one key is rewritten, every other preference is preserved.
 */
internal object IsaacOptions {
    fun ensureFullscreen(saveDirectory: File): Boolean {
        val file = File(saveDirectory, "options.ini")
        if (!file.isFile) return false
        val original = file.readText()
        val updated = forceFullscreen(original)
        if (updated == original) return false
        file.writeText(updated)
        return true
    }

    internal fun forceFullscreen(text: String): String {
        val pattern = Regex("(?im)^(\\s*Fullscreen\\s*=\\s*)\\d+")
        if (pattern.containsMatchIn(text)) return pattern.replace(text) { "${it.groupValues[1]}1" }
        val newline = if (text.contains("\r\n")) "\r\n" else "\n"
        val header = Regex("(?im)^\\[Options\\]\\s*$").find(text)
        return if (header != null) {
            text.substring(0, header.range.last + 1) + newline + "Fullscreen=1" +
                text.substring(header.range.last + 1)
        } else {
            text
        }
    }
}
