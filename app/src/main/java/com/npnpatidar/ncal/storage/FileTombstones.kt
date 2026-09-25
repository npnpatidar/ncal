package com.npnpatidar.ncal.storage

import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream

internal fun syncFile(file: File) {
    try {
        FileOutputStream(file, true).use { it.fd.sync() }
    } catch (_: Throwable) {
    }
}

internal fun syncDirectory(dir: File) {
    try {
        FileInputStream(dir).use { it.fd.sync() }
    } catch (_: Throwable) {
    }
}

class FileTombstones(private val dir: File) {

    fun mark(id: String) {
        val target = tombstoneFile(id) ?: return
        dir.mkdirs()
        if (!target.exists()) target.createNewFile()
        syncFile(target)
        syncDirectory(dir)
    }

    fun isMarked(id: String): Boolean {
        return try {
            tombstoneFile(id)?.exists() == true
        } catch (_: Throwable) {
            false
        }
    }

    private fun tombstoneFile(id: String): File? {
        val safe = id.replace(Regex("[^A-Za-z0-9_-]"), "_").take(128)
        if (safe.isBlank()) return null
        return File(dir, ".tombstone-$safe")
    }
}
