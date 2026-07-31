package com.andrerinas.headunitrevived.utils

import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.content.FileProvider
import java.io.File
import java.io.FileOutputStream
import java.io.IOException

object LogExporter {

    enum class LogLevel(val filter: String, val logLevel: Int) {
        VERBOSE("*:V", Log.VERBOSE),
        DEBUG("*:D", Log.DEBUG),
        INFO("*:I", Log.INFO),
        WARNING("*:W", Log.WARN),
        ERROR("*:E", Log.ERROR),
        /** Do not perform any background capture. */
        SILENT("", Int.MAX_VALUE)
    }

    // [FIX] These were plain vars read/written from at least three different threads: whatever
    // caller thread calls startCapture()/stopCapture()/saveLogToPublicFile(), plus the capture
    // pipe thread itself, which reassigns captureProcess/captureThread in-place when it restarts
    // itself after logcat dies (see launchLogcatPipe). Without @Volatile a writer on one thread
    // wasn't guaranteed to ever become visible to a reader on another (e.g. stopCapture() could
    // miss a just-spawned restart process entirely, leaking an unstoppable logcat process).
    // @Volatile fixes visibility; the read-modify-write sequences below are additionally guarded
    // with synchronized(this) so a restart-in-progress can't interleave with a concurrent stop.
    @Volatile private var captureProcess: Process? = null
    @Volatile private var captureThread: Thread? = null
    @Volatile private var captureFile: File? = null
    @Volatile private var captureVerbosity: LogLevel = LogLevel.DEBUG
    @Volatile private var captureRestarts = 0
    private const val MAX_RESTARTS = 5

    val isCapturing: Boolean get() = captureProcess != null

    /** Current capture verbosity while capturing, or null when no capture is active. */
    val currentLevel: LogLevel?
        get() = if (isCapturing) captureVerbosity else null

    /**
     * Starts a continuous logcat process writing to a timestamped file.
     * Unlike [saveLogToPublicFile], this captures everything from the moment it is called,
     * bypassing the small shared ring buffer.
     */
    fun startCapture(context: Context, verbosity: LogLevel) {
        if (AppLog.logSource == Settings.LogSource.APPLOG_FILE) {
            AppLog.w("LogExporter: log source is APPLOG_FILE; logcat capture is disabled")
            stopCapture()
            captureFile = null
            captureVerbosity = verbosity
            return
        }

        // If SILENT requested, ensure capture is stopped and don't start a new one.
        if (verbosity == LogLevel.SILENT) {
            stopCapture()
            captureFile = null
            captureVerbosity = verbosity
            return
        }

        stopCapture()
        val logDir = LogFilesHelper.resolveLogDirectory(context, allowInternalFallback = false) ?: return
        LogFilesHelper.rotateLogs(logDir)

        val file = LogFilesHelper.createTimestampedLogFile(logDir)
        captureFile = file
        captureVerbosity = verbosity
        captureRestarts = 0

        launchLogcatPipe(file, verbosity)
    }

    /**
     * Spawns a logcat process piping stdout into [file] (append mode).
     * When the process exits unexpectedly, restarts automatically up to [MAX_RESTARTS] times
     * so a system-killed logcat doesn't silently stop the capture.
     */
    private fun launchLogcatPipe(file: File, verbosity: LogLevel) {
        try {
            val process = Runtime.getRuntime().exec(
                arrayOf("logcat", "-v", "threadtime", verbosity.filter)
            )
            val thread = Thread {
                try {
                    FileOutputStream(file, true).use { out ->
                        process.inputStream.copyTo(out)
                    }
                } catch (_: IOException) { }
                // copyTo returned — logcat process died or was intentionally stopped.
                // Decide-and-mutate atomically so a concurrent stopCapture() can't interleave
                // with this restart (e.g. see captureProcess as still non-null and destroy the
                // wrong/new process, or miss it entirely).
                val restartAttempt = synchronized(this) {
                    if (captureProcess === process && captureRestarts < MAX_RESTARTS) {
                        ++captureRestarts
                    } else null
                }
                if (restartAttempt != null) {
                    AppLog.w("Log capture process exited, restarting (attempt $restartAttempt/$MAX_RESTARTS)")
                    try { Thread.sleep(2000) } catch (_: InterruptedException) { return@Thread }
                    launchLogcatPipe(file, verbosity)
                }
            }.also { it.isDaemon = true; it.start() }
            synchronized(this) {
                captureProcess = process
                captureThread = thread
            }
        } catch (e: IOException) {
            AppLog.e("Failed to start log capture", e)
            captureFile = null
        }
    }

    /** Stops the continuous capture process. */
    fun stopCapture() {
        val process: Process?
        val thread: Thread?
        synchronized(this) {
            process = captureProcess
            thread = captureThread
            captureProcess = null
            captureThread = null
        }
        // destroy()/join() can block — do them outside the lock so a concurrent restart
        // decision in launchLogcatPipe() (which also takes this lock) can't deadlock against it.
        process?.destroy()
        thread?.join(2000)
    }

    /**
     * Writes logs to a timestamped file and returns it.
     * - If a capture file is available (capture was started, active or already stopped):
     *   copies its content into a fresh export file so the original capture file is preserved.
     * - Otherwise: dumps the current logcat ring buffer.
     */
    fun saveLogToPublicFile(context: Context, verbosity: LogLevel): File? {
        if (verbosity == LogLevel.SILENT) {
            AppLog.w("LogExporter: export requested while SILENT; skipping export")
            return null
        }

        if (AppLog.logSource == Settings.LogSource.APPLOG_FILE) {
            return (AppLog.currentLogFile ?: AppLog.lastLogFile)
                ?.takeIf { it.exists() && it.length() > 0 }
        }

        val logDir = LogFilesHelper.resolveLogDirectory(context, allowInternalFallback = false) ?: return null
        LogFilesHelper.ensureDirectory(logDir)

        val source = captureFile
        if (source != null && source.exists() && source.length() > 0) {
            // [FIX] This used to `return source` directly — handing the caller the live
            // capture file itself, which the background pipe thread may still be actively
            // appending to (contradicting this function's own doc comment above, which
            // promises a copy "so the original capture file is preserved"). Sharing that file
            // via FileProvider could race the writer thread and hand a recipient app a
            // truncated/still-changing file. Copy it into a fresh, stable export file instead.
            return try {
                val exportFile = LogFilesHelper.createTimestampedLogFile(logDir)
                source.copyTo(exportFile, overwrite = true)
                exportFile
            } catch (e: Exception) {
                AppLog.e("Failed to copy capture log for export", e)
                null
            }
        }

        return try {
            LogFilesHelper.rotateLogs(logDir)
            val logFile = LogFilesHelper.createTimestampedLogFile(logDir)
            // Use stdout piping instead of -f flag; -f is unreliable on Android 4.4.
            val process = Runtime.getRuntime().exec(
                arrayOf("logcat", "-d", "-v", "threadtime", verbosity.filter)
            )
            FileOutputStream(logFile).use { out ->
                process.inputStream.copyTo(out)
            }
            process.waitFor()
            logFile
        } catch (e: Exception) {
            AppLog.e("Failed to save logs", e)
            null
        }
    }

    fun shareLogFile(context: Context, file: File) {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)

        val shareIntent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }

        val chooser = Intent.createChooser(shareIntent, "Share Log File")
        chooser.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(chooser)
    }
}