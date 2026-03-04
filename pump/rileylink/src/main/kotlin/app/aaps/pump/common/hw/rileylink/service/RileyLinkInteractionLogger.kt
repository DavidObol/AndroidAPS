package app.aaps.pump.common.hw.rileylink.service

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import app.aaps.core.interfaces.maintenance.FileListProvider
import java.io.File
import java.io.OutputStream
import java.nio.charset.Charset
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Writes detailed RileyLink selection/connection events to a file for debugging.
 * All writes are done on a single background thread to avoid blocking and ensure order.
 * Log file location: AAPS folder (connected to the app) / logs / RileyLink_interaction.log
 * If AAPS folder is not set, falls back to app filesDir / RileyLink_interaction.log
 */
@Singleton
class RileyLinkInteractionLogger @Inject constructor(
    private val context: Context,
    private val fileListProvider: FileListProvider
) {
    private val executor: ExecutorService = Executors.newSingleThreadExecutor()
    private val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)
    private val charset = Charset.forName("UTF-8")

    /** When non-null, log to this URI (AAPS/logs/). When null, use fallback File. */
    @Volatile
    private var logFileUri: Uri? = null

    /** Initialized when using fallback (no AAPS folder). */
    private val fallbackLogFile: File
        get() = File(context.applicationContext.filesDir, LOG_FILENAME)

    fun log(message: String) {
        val threadName = Thread.currentThread().name
        val timestamp = dateFormat.format(Date())
        val line = "$timestamp | $threadName | $message\n"
        executor.execute {
            try {
                resolveLogTarget()
                val uri = logFileUri
                if (uri != null) {
                    context.contentResolver.openOutputStream(uri, "wa")?.use { os: OutputStream ->
                        os.write(line.toByteArray(charset))
                    }
                } else {
                    fallbackLogFile.appendText(line, charset)
                }
            } catch (e: Exception) {
                // avoid crashing; log is best-effort
            }
        }
    }

    fun log(event: String, detail: String) = log("$event: $detail")

    private fun resolveLogTarget() {
        if (logFileUri != null) return
        val logsDir: DocumentFile? = fileListProvider.ensureLogsDirExists()
        if (logsDir != null) {
            val existing = logsDir.listFiles().firstOrNull { it.name == LOG_FILENAME }
            val logFile = existing ?: logsDir.createFile("text/plain", LOG_FILENAME)
            logFile?.uri?.let { logFileUri = it }
        }
    }

    companion object {
        private const val LOG_FILENAME = "RileyLink_interaction.log"
    }
}
