package app.aaps.core.interfaces.logging

/**
 * Writes log lines to the AAPS directory under the "logs" subfolder (when the user has selected
 * the AAPS directory and it contains exports, logs, extra, etc.). No-op when the directory is not set.
 */
interface AapsDirectoryLogger {

    /**
     * Append a line to the AAPS logs directory. Format and file name are implementation-defined.
     * Does nothing if the AAPS directory is not configured or not accessible.
     */
    fun log(tag: String, message: String)
}
