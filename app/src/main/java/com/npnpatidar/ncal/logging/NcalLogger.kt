package com.npnpatidar.ncal.logging

import android.content.Context
import android.net.Uri
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
 * - One file per day. The MediaStore URI is resolved once per process and
 *   cached, so logging never sprays suffixed duplicate files.
 * - All disk I/O is off the main thread and never throws (failures go to logcat).
 * - Call [installCrashHandler] first in `onCreate`: any uncaught exception is
 *   written **synchronously** to `Download/ncal/crash-<ts>.log` (unique name, so
 *   it always lands even when the process is dying) before the system handler runs.
 */
object NcalLogger {

    @Volatile var level: Int = Log.DEBUG
    @Volatile var fileLogging: Boolean = true

    private var appContext: Context? = null
    private val io = Executors.newSingleThreadExecutor()
    private val timeFmt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)
    private val dayFmt = SimpleDateFormat("yyyy-MM-dd", Locale.US)
    private val crashFmt = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US)

    /** Ring buffer of the last events (also dumped into crash logs). */
    private val ring = ArrayDeque<String>(512)
    private val ringLock = Any()

    /** Per-process cache: file name -> MediaStore URI. */
    private val uriCache = mutableMapOf<String, Uri>()
    private val uriLock = Any()

    /** App version for log clarity, e.g. `v1.2.43 (43)`. Set in [init]. */
    @Volatile var appVersion: String = "v?.? (?)"
        private set

    fun init(context: Context) {
        appContext = context.applicationContext
        appVersion = try {
            @Suppress("DEPRECATION")
            val pkg = context.packageManager.getPackageInfo(context.packageName, 0)
            "v${pkg.versionName} (${pkg.versionCode})"
        } catch (_: Throwable) {
            "v?.? (?)"
        }
        logDeviceInfo()
    }

    /**
     * Install first in Activity.onCreate (before [init]). Captures the full
     * stack trace + recent log ring to Download/ncal on any fatal crash.
     */
    fun installCrashHandler() {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                writeCrashFile(thread, throwable)
            } catch (_: Throwable) {
            }
            previous?.uncaughtException(thread, throwable)
        }
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
                    appendCached(ctx, dayFileName(), line + "\n")
                } catch (t: Throwable) {
                    Log.e("NCAL/Logger", "file append failed: ${t.message}")
                }
            }
        }
    }

    /** Append via the cached URI; resolve+insert only on first use. */
    private fun appendCached(context: Context, fileName: String, text: String) {
        val uri = synchronized(uriLock) { uriCache[fileName] }
            ?: MediaStoreHelper.appendAndGetUri(context, fileName, text)?.also { fresh ->
                synchronized(uriLock) { uriCache[fileName] = fresh }
                return
            } ?: return
        try {
            context.contentResolver.openOutputStream(uri, "wa")?.use {
                it.write(text.toByteArray())
            } ?: error("append stream null")
        } catch (t: Throwable) {
            Log.e("NCAL/Logger", "cached append failed, re-resolving: ${t.message}")
            synchronized(uriLock) { uriCache.remove(fileName) }
        }
    }

    /** Synchronous crash dump. Runs on the dying thread — keep it blocking-safe. */
    private fun writeCrashFile(thread: Thread, throwable: Throwable) {
        val ctx = appContext
        val header = buildString {
            appendLine("ncal CRASH ${timeFmt.format(Date())} $appVersion")
            appendLine("thread=${thread.name}")
            appendLine(
                "device=${Build.MANUFACTURER} ${Build.MODEL} " +
                    "sdk=${Build.VERSION.SDK_INT} release=${Build.VERSION.RELEASE}",
            )
            appendLine("--- recent log ---")
            recent().takeLast(200).forEach { appendLine(it) }
            appendLine("--- stack ---")
            appendLine(Log.getStackTraceString(throwable))
        }
        Log.e("NCAL/Crash", header)
        if (ctx != null) {
            val name = "crash-${crashFmt.format(Date())}.log"
            // Unique name each time: plain insert, no lookup needed.
            MediaStoreHelper.writeText(ctx, name, header, "text/plain")
        }
    }

    private fun dayFileName(): String = "ncal-${dayFmt.format(Date())}.log"

    private fun logDeviceInfo() {
        i(
            "App",
            "start $appVersion pkg=com.npnpatidar.ncal model=${Build.MANUFACTURER} ${Build.MODEL} " +
                "sdk=${Build.VERSION.SDK_INT} release=${Build.VERSION.RELEASE}",
        )
    }
}
