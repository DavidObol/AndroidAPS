package app.aaps.plugins.configuration.maintenance

import android.content.Context
import androidx.documentfile.provider.DocumentFile
import app.aaps.core.interfaces.logging.AapsDirectoryLogger
import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.logging.LTag
import app.aaps.core.interfaces.maintenance.FileListProvider
import app.aaps.core.interfaces.storage.Storage
import app.aaps.core.keys.StringKey
import app.aaps.core.keys.interfaces.Preferences
import dagger.Reusable
import org.joda.time.LocalDateTime
import org.joda.time.format.DateTimeFormat

private const val LOG_FILE_NAME = "aaps_events.log"

@Reusable
class AapsDirectoryLoggerImpl constructor(
    private val context: Context,
    private val fileListProvider: FileListProvider,
    private val preferences: Preferences,
    private val storage: Storage,
    private val aapsLogger: AAPSLogger
) : AapsDirectoryLogger {

    override fun log(tag: String, message: String) {
        if (preferences.get(StringKey.AapsDirectoryUri).isEmpty()) return
        try {
            val logsDir = fileListProvider.ensureLogsDirExists() ?: return
            val logFile = logsDir.listFiles().firstOrNull { it.name == LOG_FILE_NAME }
                ?: logsDir.createFile("text/plain", LOG_FILE_NAME)
                ?: return
            val timestamp = LocalDateTime.now().toString(DateTimeFormat.forPattern("yyyy-MM-dd HH:mm:ss"))
            val line = "$timestamp [$tag] $message\n"
            val existing = try {
                storage.getFileContents(context.contentResolver, logFile)
            } catch (_: Exception) {
                ""
            }
            storage.putFileContents(context.contentResolver, logFile, existing + line)
        } catch (e: Exception) {
            aapsLogger.debug(LTag.PUMP, "AapsDirectoryLogger: failed to write to AAPS logs: ${e.message}")
        }
    }
}
