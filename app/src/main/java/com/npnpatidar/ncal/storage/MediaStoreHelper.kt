package com.npnpatidar.ncal.storage

import androidx.annotation.RequiresApi
import android.content.ClipData
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import androidx.core.content.FileProvider
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.UUID

object MediaStoreHelper {

    const val FOLDER = "ncal"
    private const val RELATIVE = "Download/$FOLDER/"
    private const val PROVIDER_SUFFIX = ".fileprovider"
    private val writeLock = Any()

    private data class PendingRow(
        val id: Long,
        val name: String,
        val path: String,
        val owner: String,
        val pending: Int,
    )

    fun appendText(context: Context, fileName: String, text: String): Boolean {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                appendMediaStore(context, safeName(fileName), text) != null
            } else {
                appendPrivate(context, safeName(fileName), text)
                true
            }
        } catch (_: Throwable) {
            false
        }
    }

    fun writeText(context: Context, fileName: String, text: String, mime: String = "text/plain"): Uri? {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                writeMediaStore(context, safeName(fileName), text, mime)
            } else {
                synchronized(writeLock) {
                    val requested = externalFile(context, safeName(fileName))
                    val target = if (requested.exists()) {
                        externalFile(context, uniqueName(requested.name))
                    } else requested
                    atomicWrite(target, text)
                    providerUri(context, target)
                }
            }
        } catch (_: Throwable) {
            null
        }
    }

    fun ownFileSize(context: Context, fileName: String): Long {
        return try {
            val name = safeName(fileName)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val uri = findOwn(context, name) ?: return 0L
                context.contentResolver.query(
                    uri,
                    arrayOf(MediaStore.Downloads.SIZE),
                    null,
                    null,
                    null,
                )?.use { cursor ->
                    if (cursor.moveToFirst() && !cursor.isNull(0)) cursor.getLong(0) else 0L
                } ?: 0L
            } else {
                File(context.filesDir, "logs/$name").takeIf { it.exists() }?.length() ?: 0L
            }
        } catch (_: Throwable) {
            0L
        }
    }

    fun readOwnFile(context: Context, fileName: String): String? {
        return try {
            val name = safeName(fileName)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val uri = findOwn(context, name) ?: return null
                context.contentResolver.openInputStream(uri)?.use { readBounded(it) }
            } else {
                File(context.filesDir, "logs/$name").takeIf { it.exists() }?.inputStream()?.use { readBounded(it) }
            }
        } catch (_: Throwable) {
            null
        }
    }

    fun findOwn(context: Context, fileName: String): Uri? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return null
        val name = safeName(fileName)
        val cr = context.contentResolver
        val files = MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val projection = arrayOf(
            MediaStore.Downloads._ID,
            MediaStore.Downloads.DISPLAY_NAME,
            MediaStore.Downloads.RELATIVE_PATH,
            MediaStore.MediaColumns.OWNER_PACKAGE_NAME,
            MediaStore.Downloads.IS_PENDING,
        )
        val selection = "${MediaStore.Downloads.DISPLAY_NAME}=? AND " +
            "${MediaStore.Downloads.RELATIVE_PATH}=? AND " +
            "${MediaStore.MediaColumns.OWNER_PACKAGE_NAME}=? AND " +
            "${MediaStore.Downloads.IS_PENDING}=0"
        val args = arrayOf(name, RELATIVE, context.packageName)
        val cursor = cr.query(files, projection, selection, args, null)
            ?: throw IOException("MediaStore query returned no cursor")
        return cursor.use {
            val id = it.getColumnIndexOrThrow(MediaStore.Downloads._ID)
            val nameIndex = it.getColumnIndexOrThrow(MediaStore.Downloads.DISPLAY_NAME)
            val pathIndex = it.getColumnIndexOrThrow(MediaStore.Downloads.RELATIVE_PATH)
            val ownerIndex = it.getColumnIndexOrThrow(MediaStore.MediaColumns.OWNER_PACKAGE_NAME)
            val pendingIndex = it.getColumnIndexOrThrow(MediaStore.Downloads.IS_PENDING)
            var foundId: Long? = null
            while (it.moveToNext()) {
                val rowId = it.getLong(id)
                val rowName = it.getString(nameIndex)
                val rowPath = it.getString(pathIndex)
                val rowOwner = it.getString(ownerIndex)
                val rowPending = if (it.isNull(pendingIndex)) -1 else it.getInt(pendingIndex)
                if (rowName != name || rowPath != RELATIVE || rowOwner != context.packageName || rowPending != 0) {
                    throw IOException("MediaStore query returned an unverified row")
                }
                if (foundId == null || rowId < foundId) foundId = rowId
            }
            foundId?.let { Uri.withAppendedPath(files, it.toString()) }
        }
    }

    fun appendAndGetUri(context: Context, fileName: String, text: String): Uri? {
        return try {
            val name = safeName(fileName)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                appendMediaStore(context, name, text)
            } else {
                synchronized(writeLock) {
                    val target = appendPrivate(context, name, text)
                    providerUri(context, target)
                }
            }
        } catch (_: Throwable) {
            null
        }
    }

    fun createShareIntent(uri: Uri, mime: String = "text/plain"): Intent =
        Intent(Intent.ACTION_SEND).apply {
            type = mime
            putExtra(Intent.EXTRA_STREAM, uri)
            clipData = ClipData.newRawUri(uri.lastPathSegment ?: "file", uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }

    @RequiresApi(Build.VERSION_CODES.Q)
    private fun appendMediaStore(context: Context, fileName: String, text: String): Uri? =
        synchronized(writeLock) {
            val cr = context.contentResolver
            val bytes = text.toByteArray(Charsets.UTF_8)
            discardInterruptedPendingRows(context, fileName)
            val existing = findOwn(context, fileName)
            if (existing != null) {
                return@synchronized try {
                    writeDurably(cr, existing, "wa", bytes)
                    existing
                } catch (_: Throwable) {
                    null
                }
            }
            val files = MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, fileName)
                put(MediaStore.Downloads.MIME_TYPE, "text/plain")
                put(MediaStore.Downloads.RELATIVE_PATH, RELATIVE)
                put(MediaStore.Downloads.IS_PENDING, 1)
            }
            val uri = cr.insert(files, values) ?: return@synchronized null
            try {
                writeDurably(cr, uri, "w", bytes)
                publish(context, uri, fileName)
                uri
            } catch (_: Throwable) {
                discardPendingUri(context, uri, fileName)
                null
            }
        }

    @RequiresApi(Build.VERSION_CODES.Q)
    private fun writeMediaStore(context: Context, fileName: String, text: String, mime: String): Uri? = synchronized(writeLock) {
        val cr = context.contentResolver
        discardInterruptedPendingRows(context, fileName)
        val publishedName = if (findOwn(context, fileName) == null) fileName else uniqueName(fileName)
        val files = MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, publishedName)
            put(MediaStore.Downloads.MIME_TYPE, mime)
            put(MediaStore.Downloads.RELATIVE_PATH, RELATIVE)
            put(MediaStore.Downloads.IS_PENDING, 1)
        }
        val uri = cr.insert(files, values) ?: return@synchronized null
        try {
            writeDurably(cr, uri, "w", text.toByteArray(Charsets.UTF_8))
            publish(context, uri, fileName)
            uri
        } catch (_: Throwable) {
            discardPendingUri(context, uri, fileName)
            null
        }
    }

    fun trimOwnLogs(
        context: Context,
        prefix: String,
        maxAgeDays: Long,
        maxBytes: Long,
        maxFiles: Int = 50,
        maxTotalBytes: Long = 8 * 1024 * 1024,
    ) = synchronized(writeLock) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val cr = context.contentResolver
            val files = MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
            val projection = arrayOf(
                MediaStore.Downloads._ID,
                MediaStore.Downloads.DISPLAY_NAME,
                MediaStore.Downloads.SIZE,
                MediaStore.Downloads.DATE_MODIFIED,
            )
            val selection = "${MediaStore.Downloads.RELATIVE_PATH}=? AND " +
                "${MediaStore.MediaColumns.OWNER_PACKAGE_NAME}=? AND " +
                "${MediaStore.Downloads.IS_PENDING}=0 AND " +
                "${MediaStore.Downloads.DISPLAY_NAME} LIKE ?"
            val cutoff = System.currentTimeMillis() - maxAgeDays * 86_400_000L
            try {
                cr.query(files, projection, selection, arrayOf(RELATIVE, context.packageName, "$prefix%"), null)?.use { cursor ->
                    val id = cursor.getColumnIndexOrThrow(MediaStore.Downloads._ID)
                    val name = cursor.getColumnIndexOrThrow(MediaStore.Downloads.DISPLAY_NAME)
                    val size = cursor.getColumnIndexOrThrow(MediaStore.Downloads.SIZE)
                    val date = cursor.getColumnIndexOrThrow(MediaStore.Downloads.DATE_MODIFIED)
                    val rows = mutableListOf<LogRow>()
                    while (cursor.moveToNext()) {
                        val rowName = cursor.getString(name).orEmpty()
                        if (!rowName.startsWith(prefix)) continue
                        rows += LogRow(
                            cursor.getLong(id),
                            if (cursor.isNull(size)) 0L else cursor.getLong(size),
                            cursor.getLong(date) * 1000L,
                        )
                    }
                    val oversized = rows.filter { it.size > maxBytes || it.modified < cutoff }
                        .map { it.id }.toMutableSet()
                    val newest = rows.sortedByDescending { it.modified }
                    newest.drop(maxFiles).forEach { oversized += it.id }
                    var retainedBytes = 0L
                    for (row in newest) {
                        if (row.id in oversized) continue
                        retainedBytes += row.size
                        if (retainedBytes > maxTotalBytes) oversized += row.id
                    }
                    for (rowId in oversized) {
                        try {
                            cr.delete(Uri.withAppendedPath(files, rowId.toString()), null, null)
                        } catch (_: Throwable) {
                        }
                    }
                }
            } catch (_: Throwable) {
            }
        } else {
            val root = File(context.filesDir, "logs")
            val cutoff = System.currentTimeMillis() - maxAgeDays * 86_400_000L
            val candidates = root.listFiles()
                ?.filter { it.isFile && it.name.startsWith(prefix) }
                ?.sortedByDescending { it.lastModified() }
                ?: emptyList()
            var retainedBytes = 0L
            candidates.forEachIndexed { index, file ->
                val size = try {
                    file.length()
                } catch (_: Throwable) {
                    0L
                }
                if (size > maxBytes || file.lastModified() < cutoff || index >= maxFiles) {
                    try {
                        file.delete()
                    } catch (_: Throwable) {
                    }
                } else {
                    retainedBytes += size
                    if (retainedBytes > maxTotalBytes) {
                        try {
                            file.delete()
                        } catch (_: Throwable) {
                        }
                    }
                }
            }
        }
    }

    private data class LogRow(val id: Long, val size: Long, val modified: Long)

    @RequiresApi(Build.VERSION_CODES.Q)
    private fun publish(context: Context, uri: Uri, fileName: String) {
        val row = pendingRow(context, uri) ?: throw IOException("MediaStore pending row is missing")
        if (!isCollisionName(row.name, fileName)) {
            throw IOException("MediaStore pending row name mismatch")
        }
        val cr = context.contentResolver
        val values = ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) }
        val selection = "${MediaStore.Downloads._ID}=? AND " +
            "${MediaStore.Downloads.IS_PENDING}=1 AND " +
            "${MediaStore.MediaColumns.OWNER_PACKAGE_NAME}=? AND " +
            "${MediaStore.Downloads.RELATIVE_PATH}=? AND " +
            "${MediaStore.Downloads.DISPLAY_NAME}=?"
        val args = arrayOf(row.id.toString(), context.packageName, RELATIVE, row.name)
        if (cr.update(uri, values, selection, args) != 1) {
            throw IOException("MediaStore publish failed")
        }
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    private fun pendingRow(context: Context, uri: Uri): PendingRow? {
        val cr = context.contentResolver
        val projection = arrayOf(
            MediaStore.Downloads._ID,
            MediaStore.Downloads.DISPLAY_NAME,
            MediaStore.Downloads.RELATIVE_PATH,
            MediaStore.MediaColumns.OWNER_PACKAGE_NAME,
            MediaStore.Downloads.IS_PENDING,
        )
        val cursor = cr.query(uri, projection, null, null, null)
            ?: throw IOException("MediaStore pending query returned no cursor")
        return cursor.use {
            val id = it.getColumnIndexOrThrow(MediaStore.Downloads._ID)
            val name = it.getColumnIndexOrThrow(MediaStore.Downloads.DISPLAY_NAME)
            val path = it.getColumnIndexOrThrow(MediaStore.Downloads.RELATIVE_PATH)
            val owner = it.getColumnIndexOrThrow(MediaStore.MediaColumns.OWNER_PACKAGE_NAME)
            val pending = it.getColumnIndexOrThrow(MediaStore.Downloads.IS_PENDING)
            var found: PendingRow? = null
            while (it.moveToNext()) {
                val rowName = it.getString(name)
                    ?: throw IOException("MediaStore pending row has no name")
                val rowPath = it.getString(path)
                val rowOwner = it.getString(owner)
                val rowPending = if (it.isNull(pending)) -1 else it.getInt(pending)
                if (rowPath == null || rowOwner == null || rowPath != RELATIVE || rowOwner != context.packageName || rowPending != 1) {
                    throw IOException("MediaStore pending row could not be verified")
                }
                if (found != null) throw IOException("MediaStore pending query returned multiple rows")
                found = PendingRow(it.getLong(id), rowName, rowPath, rowOwner, rowPending)
            }
            found
        }
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    private fun discardPendingUri(context: Context, uri: Uri, fileName: String) {
        val row = pendingRow(context, uri) ?: return
        if (!isCollisionName(row.name, fileName)) {
            throw IOException("MediaStore pending row name mismatch")
        }
        deletePendingRow(context, row)
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    private fun discardInterruptedPendingRows(context: Context, fileName: String) {
        val cr = context.contentResolver
        val files = MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val projection = arrayOf(
            MediaStore.Downloads._ID,
            MediaStore.Downloads.DISPLAY_NAME,
            MediaStore.Downloads.RELATIVE_PATH,
            MediaStore.MediaColumns.OWNER_PACKAGE_NAME,
            MediaStore.Downloads.IS_PENDING,
        )
        val selection = "${MediaStore.Downloads.RELATIVE_PATH}=? AND " +
            "${MediaStore.MediaColumns.OWNER_PACKAGE_NAME}=? AND " +
            "${MediaStore.Downloads.IS_PENDING}=1"
        val args = arrayOf(RELATIVE, context.packageName)
        val cursor = cr.query(files, projection, selection, args, null)
            ?: throw IOException("MediaStore pending query returned no cursor")
        val pendingRows = mutableListOf<PendingRow>()
        cursor.use {
            val id = it.getColumnIndexOrThrow(MediaStore.Downloads._ID)
            val name = it.getColumnIndexOrThrow(MediaStore.Downloads.DISPLAY_NAME)
            val path = it.getColumnIndexOrThrow(MediaStore.Downloads.RELATIVE_PATH)
            val owner = it.getColumnIndexOrThrow(MediaStore.MediaColumns.OWNER_PACKAGE_NAME)
            val pending = it.getColumnIndexOrThrow(MediaStore.Downloads.IS_PENDING)
            while (it.moveToNext()) {
                val rowPath = it.getString(path)
                val rowOwner = it.getString(owner)
                val rowPending = if (it.isNull(pending)) -1 else it.getInt(pending)
                if (rowPath == null || rowOwner == null) {
                    throw IOException("MediaStore pending row has no ownership metadata")
                }
                if (rowPath != RELATIVE || rowOwner != context.packageName) continue
                if (rowPending != 1) throw IOException("MediaStore pending row has an unexpected state")
                val rowName = it.getString(name)
                    ?: throw IOException("MediaStore pending row has no name")
                if (rowName.isBlank()) throw IOException("MediaStore pending row has no name")
                if (isCollisionName(rowName, fileName)) {
                    pendingRows += PendingRow(it.getLong(id), rowName, rowPath, rowOwner, rowPending)
                }
            }
        }
        pendingRows.sortedBy { it.id }.forEach { deletePendingRow(context, it) }
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    private fun deletePendingRow(context: Context, row: PendingRow) {
        if (row.owner != context.packageName || row.path != RELATIVE || row.pending != 1 || row.name.isBlank()) {
            throw IOException("MediaStore pending row could not be verified")
        }
        val cr = context.contentResolver
        val files = MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val uri = Uri.withAppendedPath(files, row.id.toString())
        val selection = "${MediaStore.Downloads._ID}=? AND " +
            "${MediaStore.Downloads.IS_PENDING}=1 AND " +
            "${MediaStore.MediaColumns.OWNER_PACKAGE_NAME}=? AND " +
            "${MediaStore.Downloads.RELATIVE_PATH}=? AND " +
            "${MediaStore.Downloads.DISPLAY_NAME}=?"
        val args = arrayOf(row.id.toString(), context.packageName, RELATIVE, row.name)
        if (cr.delete(uri, selection, args) != 1) {
            throw IOException("MediaStore pending cleanup failed")
        }
    }

    private fun writeDurably(cr: android.content.ContentResolver, uri: Uri, mode: String, bytes: ByteArray) {
        val descriptor = cr.openFileDescriptor(uri, mode) ?: throw IOException("MediaStore descriptor failed")
        ParcelFileDescriptor.AutoCloseOutputStream(descriptor).use { output ->
            output.write(bytes)
            output.flush()
            output.fd.sync()
        }
    }

    private fun appendPrivate(context: Context, fileName: String, text: String): File = synchronized(writeLock) {
        val target = File(context.filesDir, "logs/$fileName")
        val parent = target.parentFile ?: throw IOException("log directory missing")
        if (!parent.exists() && !parent.mkdirs()) throw IOException("log directory creation failed")
        if (!parent.isDirectory) throw IOException("log path is not a directory")
        FileOutputStream(target, true).use { output ->
            output.write(text.toByteArray(Charsets.UTF_8))
            output.flush()
            output.fd.sync()
        }
        target
    }

    private fun externalFile(context: Context, fileName: String): File {
        val root = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)
            ?: File(context.filesDir, "exports")
        return File(File(root, FOLDER), fileName)
    }

    private fun providerUri(context: Context, file: File): Uri {
        return FileProvider.getUriForFile(context, context.packageName + PROVIDER_SUFFIX, file)
    }

    private fun atomicWrite(target: File, text: String) {
        val parent = target.parentFile ?: throw IOException("export directory missing")
        if (!parent.exists() && !parent.mkdirs()) throw IOException("export directory creation failed")
        if (!parent.isDirectory) throw IOException("export path is not a directory")
        val temporary = File.createTempFile(".${target.name}.", ".tmp", parent)
        try {
            FileOutputStream(temporary).use { output ->
                output.write(text.toByteArray(Charsets.UTF_8))
                output.flush()
                output.fd.sync()
            }
            try {
                Files.move(
                    temporary.toPath(),
                    target.toPath(),
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING,
                )
            } catch (_: java.nio.file.AtomicMoveNotSupportedException) {
                if (!temporary.renameTo(target)) throw IOException("atomic rename failed")
            }
            syncDirectory(target.parentFile ?: target)
        } finally {
            if (temporary.exists()) temporary.delete()
        }
    }

    private fun readBounded(input: java.io.InputStream): String {
        val output = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        var total = 0
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            total += count
            if (total > 1_048_576) throw IOException("file exceeds supported size")
            output.write(buffer, 0, count)
        }
        val decoder = Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
        return decoder.decode(ByteBuffer.wrap(output.toByteArray())).toString()
    }

    private fun safeName(name: String): String {
        val clean = File(name).name.trim()
        require(clean.isNotBlank() && clean != "." && clean != "..")
        return clean.take(128)
    }

    private fun isCollisionName(name: String, fileName: String): Boolean {
        if (name == fileName) return true
        val dot = fileName.lastIndexOf('.')
        val base = if (dot > 0) fileName.substring(0, dot) else fileName
        val ext = if (dot > 0) fileName.substring(dot) else ""
        val prefix = "$base-"
        if (!name.startsWith(prefix) || !name.endsWith(ext)) return false
        val suffixEnd = name.length - ext.length
        if (suffixEnd - prefix.length != 8) return false
        return name.substring(prefix.length, suffixEnd).all {
            it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F'
        }
    }

    private fun uniqueName(name: String): String {
        val dot = name.lastIndexOf('.')
        val base = if (dot > 0) name.substring(0, dot) else name
        val ext = if (dot > 0) name.substring(dot) else ""
        return "$base-${UUID.randomUUID().toString().take(8)}$ext"
    }
}
