package com.dualscreen.launcher

import android.util.Log
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.ZipInputStream

/**
 * Installs External Item Descriptions (EID) into Isaac's mods folder. The lower
 * panel reads EID's full descriptions through the game's Lua API; without it
 * only the game's one-line pickup text is available. The mod is fetched on the
 * device from its official repository the first time Isaac is played.
 */
internal object IsaacEid {
    private const val TAG = "IsaacEid"
    private const val FOLDER = "external item descriptions"
    private const val SOURCE =
        "https://github.com/wofsauge/External-Item-Descriptions/archive/refs/heads/master.zip"
    private const val MAX_BYTES = 256L * 1024 * 1024

    fun installed(gameRoot: File): Boolean = File(gameRoot, "mods/$FOLDER/main.lua").isFile

    /** Returns true when EID is present afterwards. Never throws. */
    fun ensure(gameRoot: File, onProgress: (String) -> Unit = {}): Boolean = runCatching {
        if (installed(gameRoot)) return@runCatching true
        val mods = File(gameRoot, "mods").apply { mkdirs() }
        val staging = File(mods, ".$FOLDER.partial").apply { deleteRecursively(); mkdirs() }
        onProgress("Downloading item descriptions (EID)")
        val connection = (URL(SOURCE).openConnection() as HttpURLConnection).apply {
            instanceFollowRedirects = true
            connectTimeout = 20_000
            readTimeout = 60_000
        }
        try {
            check(connection.responseCode in 200..299) { "HTTP ${connection.responseCode}" }
            var total = 0L
            ZipInputStream(connection.inputStream.buffered()).use { zip ->
                while (true) {
                    val entry = zip.nextEntry ?: break
                    // Drop the archive's single top-level folder.
                    val relative = entry.name.substringAfter('/', "")
                    if (relative.isEmpty() || relative.startsWith(".git") ||
                        relative.split('/').any { it == ".." }
                    ) continue
                    val target = File(staging, relative)
                    check(target.canonicalPath.startsWith(staging.canonicalPath + File.separator))
                    if (entry.isDirectory) {
                        target.mkdirs()
                    } else {
                        target.parentFile?.mkdirs()
                        target.outputStream().use { output ->
                            val buffer = ByteArray(64 * 1024)
                            while (true) {
                                val read = zip.read(buffer)
                                if (read < 0) break
                                total += read
                                check(total <= MAX_BYTES) { "EID archive is unexpectedly large" }
                                output.write(buffer, 0, read)
                            }
                        }
                    }
                }
            }
        } finally {
            connection.disconnect()
        }
        check(File(staging, "main.lua").isFile && File(staging, "metadata.xml").isFile) {
            "Downloaded archive is not the EID mod"
        }
        val destination = File(mods, FOLDER)
        destination.deleteRecursively()
        check(staging.renameTo(destination)) { "Could not publish EID" }
        true
    }.getOrElse { failure ->
        Log.w(TAG, "EID installation skipped", failure)
        false
    }
}
