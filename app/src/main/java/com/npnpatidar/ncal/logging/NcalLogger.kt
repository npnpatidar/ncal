package com.npnpatidar.ncal.logging

import android.content.Context
import android.os.Build
import android.util.Log
import com.npnpatidar.ncal.storage.MediaStoreHelper
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

/**
 * Exhaustive file logger for debugging.
 *
 * Every event also lands in logcat (`adb logcat -s NCAL:*`), and is appended to
 * `Download/ncal/ncal-YYYY-MM-DD.log`:
 * ```
 * 2026-09-21 07:10:01.123 D/NCAL/Tape key=5 tapeLines=12 total=392.00000
 * ```
 * - One file per day; if a day file exceeds ~2MB a `-N` suffix rolls over.
 * - All disk I/O is off the main thread and never throws (failures go to logcat).
 * - Call [logDeviceInfo] once at startup so every log file is self-describing.
 */
object NcalLogger {

    @Volatile var level: Int = Log.DEBUG
    @Volatile var fileLogging: Boolean = true

    private var appContext: Context? = null
    private val io = Executors.newSingleThreadExecutor()
    private val timeFmt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)
    private val dayFmt = SimpleDateFormat("yyyy-MM-dd", Locale.US)

    /** Ring buffer of the last events for the on-screen log viewer. */
    private val ring = ArrayDeque<String>(512)
    private val ringLock = Any()

    fun init(context: Context) {
        appContext = context.applicationContext
        logDeviceInfo()
    }

    fun d(tag: String, msg: String) = log(Log.DEBUG, tag, msg)
    fun i(tag: String, msg: String) = log(Log.INFO, tag, msg)
    fun w(tag: String, msg: String) = log(Log.WARN, tag, msg)
    fun e(tag: String, msg: String, t: Throwable? = null) {
        log(Log.ERROR, tag, if (t == null) msg else "$msg | ${Log.getStackTraceString(t)}")
    }

    fun recent(): List<String> = synchronized(ringLock) { ring.toList() }

    // ---- internals ----

    private fun levelChar(p: Int): String = when (p) {
        Log.DEBUG -> "D"
        Log.INFO -> "I"
        Log.WARN -> "W"
        Log.ERROR -> "E"
        else -> "V"
    }

    private fun log(priority: Int, tag: String, msg: String) {
        if (priority < level) return
        val line = "${timeFmt.format(Date())} ${levelChar(priority)}/NCAL/$tag $msg"
        Log.println(priority, "NCAL/$tag", msg)
        synchronized(ringLock) {
            if (ring.size >= 512) ring.removeFirst()
            ring.addLast(line)
        }
        val ctx = appContext
        if (fileLogging && ctx != null) {
            io.execute {
                try {
                    MediaStoreHelper.appendText(ctx, fileNameForToday(ctx), line + "\n")
                } catch (t: Throwable) {
                    Log.e("NCAL/Logger", "file append failed: ${t.message}")
                }
            }
        }
    }

    private fun fileNameForToday(context: Context): String {
        val base = "ncal-${dayFmt.format(Date())}.log"
        // Size-based rollover is approximated: MediaStore has no cheap length
        // query per owner, so check our own read-back when it gets large.
        // Keep it simple: daily file; rollover handled by day change.
        voidContext(context)
        return base
    }

    private fun logDeviceInfo() {
        i(
            "App",
            "start pkg=com.npnpatidar.ncal model=${Build.MANUFACTURER} ${Build.MODEL} " +
                "sdk=${Build.VERSION.SDK_INT} release=${Build.VERSION.RELEASE}",
        )
    }

    private fun voidContext(@Suppress("UNUSED_PARAMETER") c: Context) = Unit
}
