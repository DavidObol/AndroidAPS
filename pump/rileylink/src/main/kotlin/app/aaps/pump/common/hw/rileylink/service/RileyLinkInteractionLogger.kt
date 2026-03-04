package app.aaps.pump.common.hw.rileylink.service

import android.content.Context
import java.io.File
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
 * File: app filesDir / RileyLink_interaction.log
 */
@Singleton
class RileyLinkInteractionLogger @Inject constructor(
    private val context: Context
) {
    private val executor: ExecutorService = Executors.newSingleThreadExecutor()
    private val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)
    private val logFile: File
        get() = File(context.applicationContext.filesDir, LOG_FILENAME)

    fun log(message: String) {
        val threadName = Thread.currentThread().name
        val timestamp = dateFormat.format(Date())
        val line = "$timestamp | $threadName | $message\n"
        executor.execute {
            try {
                logFile.appendText(line)
            } catch (e: Exception) {
                // avoid crashing; log is best-effort
            }
        }
    }

    fun log(event: String, detail: String) = log("$event: $detail")

    companion object {
        private const val LOG_FILENAME = "RileyLink_interaction.log"
    }
}
