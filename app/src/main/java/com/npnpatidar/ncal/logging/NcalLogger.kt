package com.npnpatidar.ncal.logging

import android.content.Context
import android.net.Uri
import android.os.Build
import android.util.Log
import com.npnpatidar.ncal.BuildConfig
import com.npnpatidar.ncal.storage.MediaStoreHelper
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Date
import java.util.Locale
import java.util.UUID
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.RejectedExecutionHandler
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

object NcalLogger {

    @Volatile private var level: Int = if (BuildConfig.DEBUG) Log.DEBUG else Log.WARN
    @Volatile private var fileLogging: Boolean = BuildConfig.DEBUG

    private var appContext: Context? = null
    private val io = ThreadPoolExecutor(
        1,
        1,
        0L,
        TimeUnit.MILLISECONDS,
        ArrayBlockingQueue(256),
        RejectedExecutionHandler { _, _ -> },
    )
    private val timeFmt = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)
        .withZone(ZoneId.systemDefault())
    private val dayFmt = DateTimeFormatter.ofPattern("yyyy-MM-dd", Locale.US)
        .withZone(ZoneId.systemDefault())
    private val ring = ArrayDeque<String>(512)
    private val ringLock = Any()
    private val uriCache = mutableMapOf<String, Uri>()
    private val uriLock = Any()
    private val crashInstalled = AtomicBoolean(false)
    private val lastPruneDay = AtomicReference("")
    private val dailyBytes = AtomicLong(0)
    private const val MAX_DAILY_FILE_BYTES = 512 * 1024L

    @Volatile var appVersion: String = "v?.? (?)"
        private set

    fun init(context: Context) {
        appContext = context.applicationContext
        fileLogging = BuildConfig.DEBUG
        level = if (BuildConfig.DEBUG) Log.DEBUG else Log.WARN
        appVersion = try {
            @Suppress("DEPRECATION")
            val pkg = context.packageManager.getPackageInfo(context.packageName, 0)
            "v${pkg.versionName} (${pkg.versionCode})"
        } catch (_: Throwable) {
            "v?.? (?)"
        }
        logDeviceInfo()
    }

    fun installCrashHandler() {
        if (!crashInstalled.compareAndSet(false, true)) return
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                if (fileLogging) writeCrashFile(thread, throwable)
            } catch (_: Throwable) {
            }
            previous?.uncaughtException(thread, throwable)
        }
    }

    fun d(tag: String, msg: String) = log(Log.DEBUG, tag, msg)
    fun i(tag: String, msg: String) = log(Log.INFO, tag, msg)
    fun w(tag: String, msg: String) = log(Log.WARN, tag, msg)
    fun e(tag: String, msg: String, t: Throwable? = null) {
        val detail = if (t == null) msg else "$msg | ${Log.getStackTraceString(t)}"
        log(Log.ERROR, tag, detail)
    }

    fun recent(): List<String> = synchronized(ringLock) { ring.toList() }

    private fun levelChar(priority: Int): String = when (priority) {
        Log.DEBUG -> "D"
        Log.INFO -> "I"
        Log.WARN -> "W"
        Log.ERROR -> "E"
        else -> "V"
    }

    private fun log(priority: Int, tag: String, msg: String) {
        if (priority < level) return
        val safeTag = clean(tag, 64)
        val safeMsg = clean(if (BuildConfig.DEBUG) msg else redact(msg), 4096)
        val line = "${timeFmt.format(Instant.now())} ${levelChar(priority)}/NCAL/$safeTag $safeMsg"
        Log.println(priority, "NCAL/$safeTag", safeMsg)
        synchronized(ringLock) {
            if (ring.size >= 512) ring.removeFirst()
            ring.addLast(line)
        }
        val ctx = appContext
        if (fileLogging && BuildConfig.DEBUG && ctx != null) {
            io.execute {
                try {
                    val day = dayFmt.format(Instant.now())
                    if (lastPruneDay.getAndSet(day) != day) {
                        MediaStoreHelper.trimOwnLogs(ctx, "ncal-", 7, 512 * 1024)
                        MediaStoreHelper.trimOwnLogs(ctx, "crash-", 30, 512 * 1024)
                        dailyBytes.set(
                            MediaStoreHelper.ownFileSize(ctx, dayFileName())
                                .coerceIn(0, MAX_DAILY_FILE_BYTES),
                        )
                    }
                    val entry = line + "\n"
                    val entryBytes = entry.toByteArray(Charsets.UTF_8).size.toLong()
                    if (dailyBytes.get() + entryBytes > MAX_DAILY_FILE_BYTES) return@execute
                    dailyBytes.addAndGet(entryBytes)
                    appendCached(ctx, dayFileName(), entry)
                } catch (t: Throwable) {
                    Log.e("NCAL/Logger", "file append failed")
                }
            }
        }
    }

    private fun appendCached(context: Context, fileName: String, text: String) {
        val uri = synchronized(uriLock) { uriCache[fileName] }
            ?: MediaStoreHelper.appendAndGetUri(context, fileName, text)?.also { fresh ->
                synchronized(uriLock) { uriCache[fileName] = fresh }
                return
            } ?: return
        try {
            context.contentResolver.openOutputStream(uri, "wa")?.use {
                it.write(text.toByteArray(Charsets.UTF_8))
            } ?: error("append stream null")
        } catch (_: Throwable) {
            synchronized(uriLock) { uriCache.remove(fileName) }
        }
    }

    private fun writeCrashFile(thread: Thread, throwable: Throwable) {
        val ctx = appContext ?: return
        val header = buildString {
            appendLine("ncal CRASH ${timeFmt.format(Instant.now())} ${clean(appVersion, 64)}")
            appendLine("thread=${clean(thread.name, 128)}")
            appendLine(
                "device=${clean("${Build.MANUFACTURER} ${Build.MODEL}", 128)} " +
                    "sdk=${Build.VERSION.SDK_INT} release=${clean(Build.VERSION.RELEASE ?: "?", 32)}",
            )
            appendLine("--- recent log ---")
            recent().takeLast(100).forEach { appendLine(it) }
            appendLine("--- stack ---")
            appendLine(cleanMultiline(Log.getStackTraceString(throwable), 32 * 1024))
        }.take(64 * 1024)
        val name = "crash-${java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss-SSS", Locale.US)
            .withZone(ZoneId.systemDefault()).format(Instant.now())}-${UUID.randomUUID().toString().take(8)}.log"
        MediaStoreHelper.writeText(ctx, name, header, "text/plain")
    }

    private fun dayFileName(): String = "ncal-${dayFmt.format(Instant.now())}.log"

    private fun logDeviceInfo() {
        i(
            "App",
            "start $appVersion model=${Build.MANUFACTURER} ${Build.MODEL} sdk=${Build.VERSION.SDK_INT}",
        )
    }

    private fun clean(value: String, limit: Int): String =
        value.map { if (it.isISOControl()) '_' else it }.joinToString("").take(limit)

    private fun cleanMultiline(value: String, limit: Int): String =
        value.map { if (it == '\n' || !it.isISOControl()) it else '_' }.joinToString("").take(limit)

    private fun redact(value: String): String = value
        .replace(Regex("content://[^\\s]+"), "content://[redacted]")
        .replace(Regex("(?i)\\b[0-9a-f]{8}-[0-9a-f-]{27,}\\b"), "[id]")
        .replace(Regex("/[^\\s]*(?:notes|logs|Download)[^\\s]*"), "[path]")
}
