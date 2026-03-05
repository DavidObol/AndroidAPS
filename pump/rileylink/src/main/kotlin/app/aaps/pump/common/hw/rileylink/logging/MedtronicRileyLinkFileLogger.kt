package app.aaps.pump.common.hw.rileylink.logging

import android.content.Context
import android.os.Environment
import app.aaps.core.keys.interfaces.Preferences
import app.aaps.pump.common.hw.rileylink.keys.RileylinkBooleanPreferenceKey
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStreamWriter
import java.nio.charset.StandardCharsets
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Logs all RileyLink and Medtronic pump interaction to a single file in the AAPS directory
 * for debugging. Does not replace or modify existing AAPSLogger calls.
 * Enable via preference MedtronicRileyLinkFileLogEnabled.
 */
@Singleton
class MedtronicRileyLinkFileLogger @Inject constructor(
    private val context: Context,
    private val preferences: Preferences
) {
    private val lock = Any()
    private const val LOG_FILE_NAME = "medtronic_rileylink.log"
    private const val MAX_FILE_SIZE_BYTES = 2 * 1024 * 1024 // 2 MB

    private fun aapsDirectory(): File =
        File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS), "AAPS")

    private fun logFile(): File = File(aapsDirectory(), LOG_FILE_NAME)

    private fun isEnabled(): Boolean = preferences.get(RileylinkBooleanPreferenceKey.MedtronicRileyLinkFileLogEnabled)

    /**
     * Append a line to the log file. Only writes when enabled.
     * @param tag e.g. "UI", "RL", "INFO", "ERROR"
     * @param message log line (no newlines added automatically for multi-line)
     */
    fun log(tag: String, message: String) {
        if (!isEnabled()) return
        val timestamp = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS", Locale.US).format(Date())
        val line = "$timestamp [$tag] $message\n"
        synchronized(lock) {
            try {
                val dir = aapsDirectory()
                if (!dir.exists()) dir.mkdirs()
                val file = logFile()
                if (file.length() > MAX_FILE_SIZE_BYTES) {
                    file.writeText("")
                }
                FileOutputStream(file, true).use { fos ->
                    OutputStreamWriter(fos, StandardCharsets.UTF_8).use { writer ->
                        writer.write(line)
                        writer.flush()
                    }
                }
            } catch (_: Exception) {
                // Do not affect app; logging is best-effort
            }
        }
    }

    fun logUi(message: String) = log("UI", message)
    fun logComm(message: String) = log("RL", message)
    fun logError(message: String) = log("ERROR", message)
}
