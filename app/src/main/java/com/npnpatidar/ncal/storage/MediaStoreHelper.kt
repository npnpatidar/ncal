package com.npnpatidar.ncal.storage

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import java.io.File

/**
 * File access under `Download/ncal/` — the same folder the user browses while
 * debugging (Files app, `adb pull /sdcard/Download/ncal`).
 *
 * - Android 10+ (API 29+): MediaStore Downloads with RELATIVE_PATH
 *   `Download/ncal/`. Writing the app's own files needs no storage permission.
 * - Below API 29: direct file path (WRITE_EXTERNAL_STORAGE granted at install
 *   for these API levels when declared... we don't declare it; pre-29 devices
 *   fall back to the app-private dir, still shared via the share sheet).
 */
object MediaStoreHelper {

    const val FOLDER = "ncal"
    private const val RELATIVE = "Download/$FOLDER/"

    /** Append [text] to [fileName] under Download/ncal, creating it if needed. */
    fun appendText(context: Context, fileName: String, text: String): Boolean {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                appendMediaStore(context, fileName, text)
            } else {
                appendPrivate(context, fileName, text)
            }
            true
        } catch (_: Throwable) {
            false
        }
    }

    /** Overwrite (or create) [fileName] under Download/ncal with [text]. */
    fun writeText(context: Context, fileName: String, text: String, mime: String = "text/plain"): Uri? {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                writeMediaStore(context, fileName, text, mime)
            } else {
                val f = File(context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), "$FOLDER/$fileName")
                f.parentFile?.mkdirs()
                f.writeText(text)
                Uri.fromFile(f)
            }
        } catch (_: Throwable) {
            null
        }
    }

    /** Read back a file previously written by [writeText] (own files only). */
    fun readOwnFile(context: Context, fileName: String): String? {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val uri = findOwn(context, fileName) ?: return null
                context.contentResolver.openInputStream(uri)?.bufferedReader()?.readText()
            } else {
                File(context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), "$FOLDER/$fileName")
                    .takeIf { it.exists() }?.readText()
            }
        } catch (_: Throwable) {
            null
        }
    }

    // ---- internals ----

    /**
     * Some devices append an extension from the MIME type on insert
     * (`ncal-....log` becomes `ncal-....log.txt`), so look up both spellings.
     * Prefer an exact match, fall back to the `.txt`-suffixed one.
     */
    fun findOwn(context: Context, fileName: String): Uri? {
        val cr = context.contentResolver
        val files = MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        var fallback: Uri? = null
        cr.query(
            files,
            arrayOf(MediaStore.Downloads._ID, MediaStore.Downloads.DISPLAY_NAME),
            "${MediaStore.Downloads.DISPLAY_NAME}=? OR ${MediaStore.Downloads.DISPLAY_NAME}=?",
            arrayOf(fileName, "$fileName.txt"),
            null,
        )?.use { c ->
            val idCol = c.getColumnIndexOrThrow(MediaStore.Downloads._ID)
            val nameCol = c.getColumnIndexOrThrow(MediaStore.Downloads.DISPLAY_NAME)
            while (c.moveToNext()) {
                val uri = Uri.withAppendedPath(files, c.getLong(idCol).toString())
                if (c.getString(nameCol) == fileName) return uri
                if (fallback == null) fallback = uri
            }
        }
        return fallback
    }

    /**
     * Find-or-insert [fileName], append [text], and return the URI so callers
     * can cache it and keep appending without re-querying (avoids spraying
     * suffixed duplicates when the provider renames on insert).
     */
    fun appendAndGetUri(context: Context, fileName: String, text: String): Uri? {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                appendMediaStore(context, fileName, text)
            } else {
                appendPrivate(context, fileName, text)
                null
            }
        } catch (_: Throwable) {
            null
        }
    }

    private fun appendMediaStore(context: Context, fileName: String, text: String): Uri? {
        val cr = context.contentResolver
        var uri = findOwn(context, fileName)
        if (uri == null) {
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, fileName)
                put(MediaStore.Downloads.MIME_TYPE, "text/plain")
                put(MediaStore.Downloads.RELATIVE_PATH, RELATIVE)
            }
            val files = MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
            uri = cr.insert(files, values) ?: error("MediaStore insert failed")
        }
        cr.openOutputStream(uri!!, "wa")?.use { it.write(text.toByteArray()) }
            ?: error("MediaStore append failed")
        return uri
    }

    private fun writeMediaStore(context: Context, fileName: String, text: String, mime: String): Uri? {
        val cr = context.contentResolver
        findOwn(context, fileName)?.let { cr.delete(it, null, null) }
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, fileName)
            put(MediaStore.Downloads.MIME_TYPE, mime)
            put(MediaStore.Downloads.RELATIVE_PATH, RELATIVE)
        }
        val files = MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val uri = cr.insert(files, values) ?: return null
        cr.openOutputStream(uri, "w")?.use { it.write(text.toByteArray()) } ?: return null
        return uri
    }

    private fun appendPrivate(context: Context, fileName: String, text: String) {
        val f = File(context.filesDir, "logs/$fileName")
        f.parentFile?.mkdirs()
        f.appendText(text)
    }
}
