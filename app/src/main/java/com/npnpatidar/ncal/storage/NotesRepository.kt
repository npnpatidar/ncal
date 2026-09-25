package com.npnpatidar.ncal.storage

import android.content.Context
import com.npnpatidar.ncal.export.CalcExport
import com.npnpatidar.ncal.logging.NcalLogger
import com.npnpatidar.ncal.tape.CalcFile
import com.npnpatidar.ncal.tape.CalcMeta
import com.npnpatidar.ncal.tape.TapeEvaluator
import com.npnpatidar.ncal.tape.TapeLimits
import com.npnpatidar.ncal.tape.TapeDoc
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.UUID

sealed interface SaveResult {
    data object Success : SaveResult
    data object Superseded : SaveResult
    data class Failure(val message: String, val cause: Throwable? = null) : SaveResult
}

class NotesRepository(private val context: Context) {

    data class NoteMeta(val id: String, val name: String)

    private val prefs = context.getSharedPreferences("ncal_notes", Context.MODE_PRIVATE)
    private val mutationLock = Any()
    private val deletedIds = mutableSetOf<String>()
    private val dir: File get() = File(context.filesDir, "notes").apply { mkdirs() }
    private val tombstones: FileTombstones by lazy { FileTombstones(dir) }
    private fun file(id: String): File = File(dir, "${safeId(id)}.calc")

    private fun <T> withNotesLock(block: () -> T): T {
        RandomAccessFile(File(dir, ".lock"), "rw").use { handle ->
            handle.channel.lock().use { return block() }
        }
    }

    fun list(): List<NoteMeta> {
        val order = prefs.getString(KEY_ORDER, null)
            ?.split(",")
            ?.filter { it.isNotBlank() }
            ?: emptyList()
        val known = order.mapNotNull { id ->
            val target = file(id)
            if (!isValidNoteFile(target)) null
            else NoteMeta(id, cleanName(prefs.getString(KEY_NAME + id, "Note") ?: "Note"))
        }.toMutableList()
        dir.listFiles()
            ?.filter { it.isFile && it.extension == "calc" && isValidNoteFile(it) }
            ?.filter { candidate ->
                known.none { it.id == candidate.nameWithoutExtension } &&
                    candidate.nameWithoutExtension !in deletedIds &&
                    !tombstones.isMarked(candidate.nameWithoutExtension)
            }
            ?.sortedBy { it.lastModified() }
            ?.forEach { known.add(NoteMeta(it.nameWithoutExtension, cleanName(it.nameWithoutExtension))) }
        return known.distinctBy { it.id }
    }

    fun lastOpen(): String? = prefs.getString(KEY_LAST, null)

    fun isDeleted(id: String): Boolean = synchronized(mutationLock) {
        id in deletedIds || tombstones.isMarked(id)
    }

    fun lastModified(id: String): Long {
        return try {
            file(id).takeIf { it.exists() }?.lastModified() ?: 0L
        } catch (_: Throwable) {
            0L
        }
    }

    fun setLastOpen(id: String) {
        prefs.edit().putString(KEY_LAST, safeId(id)).apply()
    }

    fun create(name: String): String? {
        val id = UUID.randomUUID().toString()
        val clean = cleanName(name.ifBlank { "Note" }).take(64)
        return try {
            atomicWrite(file(id), CalcFile.write(TapeDoc(CalcMeta(), emptyList()), emptyMap()))
            persist(list().filter { it.id != id } + NoteMeta(id, clean))
            NcalLogger.i("Notes", "created")
            id
        } catch (t: Throwable) {
            NcalLogger.e("Notes", "create failed", t)
            null
        }
    }

    fun rename(id: String, name: String): Boolean {
        val clean = cleanName(name).trim().ifBlank { return false }.take(64)
        if (!file(id).exists()) return false
        persist(list().map { if (it.id == id) it.copy(name = clean) else it })
        NcalLogger.i("Notes", "renamed")
        return true
    }

    fun duplicate(id: String): String? {
        if (!file(id).exists()) return null
        val newId = UUID.randomUUID().toString()
        return try {
            val text = readText(file(id))
            val doc = CalcFile.parse(text)
            val eval = TapeEvaluator.evaluate(doc.lines, doc.meta.decimals)
            val meta = doc.meta.copy(uuid = newId, caretLine = 0, caretOffset = 0)
            atomicWrite(file(newId), CalcFile.write(doc.copy(meta = meta), eval.balanceTotals))
            val oldName = list().firstOrNull { it.id == id }?.name ?: "Note"
            persist(list().filter { it.id != newId } + NoteMeta(newId, "$oldName copy".take(64)))
            NcalLogger.i("Notes", "duplicated")
            newId
        } catch (t: Throwable) {
            NcalLogger.e("Notes", "duplicate failed", t)
            null
        }
    }

    fun importDoc(name: String, text: String, fallbackMeta: CalcMeta = CalcMeta()): String? {
        if (text.length > TapeLimits.MAX_INPUT_CHARS) return null
        return try {
            val doc = CalcFile.parse(text, fallbackMeta)
            if (doc.warnings.any { it.contains("input exceeds") || it.contains("too many lines") }) return null
            val eval = TapeEvaluator.evaluate(doc.lines, doc.meta.decimals)
            val id = UUID.randomUUID().toString()
            val meta = doc.meta.copy(uuid = id, caretLine = 0, caretOffset = 0)
            atomicWrite(file(id), CalcFile.write(doc.copy(meta = meta), eval.balanceTotals))
            val clean = cleanName(name.substringBeforeLast(".")).ifBlank { "Imported" }.take(64)
            persist(list().filter { it.id != id } + NoteMeta(id, clean))
            NcalLogger.i("Notes", "imported lines=${doc.lines.size}")
            id
        } catch (t: Throwable) {
            NcalLogger.e("Notes", "import failed", t)
            null
        }
    }

    fun loadRaw(id: String): String? {
        return try {
            file(id).takeIf { it.exists() }?.let {
                readText(it).takeIf { text -> CalcFile.hasHeader(text) }
            }
        } catch (t: Throwable) {
            NcalLogger.e("Notes", "loadRaw failed", t)
            null
        }
    }

    fun delete(id: String): Boolean = synchronized(mutationLock) {
        try {
            withNotesLock {
                deletedIds += id
                tombstones.mark(id)
                val target = file(id)
                val deleted = !target.exists() || target.delete()
                if (!deleted) {
                    NcalLogger.e("Notes", "delete failed")
                    return@withNotesLock false
                }
                persist(list().filterNot { it.id == id })
                prefs.edit().remove(KEY_NAME + id).apply()
                NcalLogger.i("Notes", "deleted")
                true
            }
        } catch (t: Throwable) {
            NcalLogger.e("Notes", "delete failed", t)
            false
        }
    }

    fun reconcileTombstones() {
        val stones = try {
            dir.listFiles { file -> file.isFile && file.name.startsWith(".tombstone-") }
        } catch (_: Throwable) {
            null
        } ?: return
        for (stone in stones) {
            val target = File(dir, stone.name.removePrefix(".tombstone-") + ".calc")
            try {
                if (target.exists() && !target.delete()) {
                    NcalLogger.e("Notes", "tombstone reconcile failed")
                }
            } catch (t: Throwable) {
                NcalLogger.e("Notes", "tombstone reconcile failed", t)
            }
        }
    }

    fun load(id: String): CalcExport.ImportResult? {
        return try {
            val text = readText(file(id))
            if (!CalcFile.hasHeader(text)) return null
            val result = CalcExport.importToTapeText(text)
            NcalLogger.i("Notes", "loaded")
            result
        } catch (t: Throwable) {
            NcalLogger.e("Notes", "load failed", t)
            null
        }
    }

    fun save(id: String, tapeText: String, meta: CalcMeta): SaveResult =
        saveInternal(id, tapeText, meta, null)

    fun saveIfCurrent(
        id: String,
        tapeText: String,
        meta: CalcMeta,
        isCurrent: () -> Boolean,
    ): SaveResult = saveInternal(id, tapeText, meta, isCurrent)

    private fun saveInternal(
        id: String,
        tapeText: String,
        meta: CalcMeta,
        isCurrent: (() -> Boolean)?,
    ): SaveResult = synchronized(mutationLock) {
        if (id.isBlank() || tapeText.length > TapeLimits.MAX_INPUT_CHARS) {
            return@synchronized SaveResult.Failure("note content exceeds supported limits")
        }
        val parsed = try {
            CalcFile.parse(tapeText, meta)
        } catch (t: Throwable) {
            NcalLogger.e("Notes", "save failed", t)
            return@synchronized SaveResult.Failure(t.message ?: "save failed", t)
        }
        if (parsed.warnings.any { it.contains("input exceeds") || it.contains("too many lines") }) {
            return@synchronized SaveResult.Failure("note content exceeds supported limits")
        }
        val eval = TapeEvaluator.evaluate(parsed.lines, parsed.meta.decimals)
        val safeMeta = meta.copy(
            decimals = TapeLimits.safeDecimals(meta.decimals),
            uuid = meta.uuid.take(128),
            caretLine = meta.caretLine.coerceIn(0, TapeLimits.MAX_LINES),
            caretOffset = meta.caretOffset.coerceIn(0, TapeLimits.MAX_LINE_CHARS),
        )
        val text = try {
            CalcFile.write(parsed.copy(meta = safeMeta), eval.balanceTotals)
        } catch (t: Throwable) {
            NcalLogger.e("Notes", "save failed", t)
            return@synchronized SaveResult.Failure(t.message ?: "save failed", t)
        }
        return@synchronized try {
            withNotesLock {
                if (isCurrent != null && !isCurrent()) return@withNotesLock SaveResult.Superseded
                if (id in deletedIds || tombstones.isMarked(id)) {
                    return@withNotesLock SaveResult.Failure("note was deleted")
                }
                try {
                    atomicWrite(file(id), text)
                    NcalLogger.d("Notes", "saved lines=${parsed.lines.size}")
                    SaveResult.Success
                } catch (t: Throwable) {
                    NcalLogger.e("Notes", "save failed", t)
                    SaveResult.Failure(t.message ?: "save failed", t)
                }
            }
        } catch (t: Throwable) {
            NcalLogger.e("Notes", "save failed", t)
            SaveResult.Failure("notes lock unavailable", t)
        }
    }

    private fun isValidNoteFile(file: File): Boolean {
        return try {
            file.isFile && file.length() > 0L && file.length() <= TapeLimits.MAX_INPUT_CHARS &&
                CalcFile.hasHeader(readText(file))
        } catch (_: Throwable) {
            false
        }
    }

    private fun readText(file: File): String {
        require(file.length() <= TapeLimits.MAX_INPUT_CHARS) { "file exceeds supported size" }
        return file.inputStream().use { input ->
            val output = java.io.ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            var total = 0
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                total += count
                if (total > TapeLimits.MAX_INPUT_CHARS) throw IOException("file exceeds supported size")
                output.write(buffer, 0, count)
            }
            val decoder = Charsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
            decoder.decode(ByteBuffer.wrap(output.toByteArray())).toString()
        }
    }

    private fun atomicWrite(target: File, text: String) {
        target.parentFile?.mkdirs()
        val temporary = File.createTempFile(".${target.name}.", ".tmp", target.parentFile)
        try {
            FileOutputStream(temporary).use { output ->
                output.write(text.toByteArray(Charsets.UTF_8))
                output.fd.sync()
            }
            try {
                Files.move(
                    temporary.toPath(),
                    target.toPath(),
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING,
                )
            } catch (_: AtomicMoveNotSupportedException) {
                if (!temporary.renameTo(target)) throw IOException("atomic rename failed")
            }
            syncDirectory(target.parentFile ?: target)
        } finally {
            if (temporary.exists()) temporary.delete()
        }
    }

    private fun persist(metas: List<NoteMeta>) {
        val deduped = metas.distinctBy { it.id }
        val editor = prefs.edit().putString(KEY_ORDER, deduped.joinToString(",") { it.id })
        deduped.forEach { editor.putString(KEY_NAME + it.id, it.name) }
        editor.apply()
    }

    private fun safeId(id: String): String = id.replace(Regex("[^A-Za-z0-9_-]"), "_").take(128)

    private fun cleanName(name: String): String = name.map { if (it.isISOControl()) '_' else it }.joinToString("")

    companion object {
        private const val KEY_ORDER = "order"
        private const val KEY_NAME = "name_"
        private const val KEY_LAST = "last"
    }
}
