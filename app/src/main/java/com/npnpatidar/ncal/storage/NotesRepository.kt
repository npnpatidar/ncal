package com.npnpatidar.ncal.storage

import android.content.Context
import com.npnpatidar.ncal.export.CalcExport
import com.npnpatidar.ncal.logging.NcalLogger
import com.npnpatidar.ncal.tape.CalcFile
import com.npnpatidar.ncal.tape.CalcMeta
import com.npnpatidar.ncal.tape.TapeEvaluator
import com.npnpatidar.ncal.tape.TapeDoc
import java.io.File
import java.util.UUID

/**
 * Sidebar notes: each note is one canonical `.calc` file under the app-private
 * `notes/` dir (same format as the Download exports, so a note can be saved
 * out verbatim). Display names + order live in SharedPreferences.
 */
class NotesRepository(private val context: Context) {

    data class NoteMeta(val id: String, val name: String)

    private val prefs = context.getSharedPreferences("ncal_notes", Context.MODE_PRIVATE)
    private val dir: File get() = File(context.filesDir, "notes").apply { mkdirs() }
    private fun file(id: String) = File(dir, "$id.calc")

    fun list(): List<NoteMeta> {
        val order = prefs.getString(KEY_ORDER, null)
            ?.split(",")?.filter { it.isNotBlank() } ?: emptyList()
        val known = order.mapNotNull { id ->
            if (!file(id).exists()) null
            else NoteMeta(id, prefs.getString(KEY_NAME + id, "Note") ?: "Note")
        }.toMutableList()
        // Adopt orphan files (e.g. restored backups) instead of losing them.
        dir.listFiles()
            ?.filter { it.extension == "calc" && known.none { n -> n.id == it.nameWithoutExtension } }
            ?.sortedBy { it.lastModified() }
            ?.forEach { known.add(NoteMeta(it.nameWithoutExtension, it.nameWithoutExtension)) }
        // Defensive: duplicate ids (e.g. from an early create/persist race) would
        // crash LazyColumn keys, so collapse them — first occurrence wins.
        return known.distinctBy { it.id }
    }

    fun lastOpen(): String? = prefs.getString(KEY_LAST, null)

    /** Last-modified time of a note file (for date sorting); 0 if missing. */
    fun lastModified(id: String): Long {
        return try {
            file(id).takeIf { it.exists() }?.lastModified() ?: 0L
        } catch (_: Throwable) {
            0L
        }
    }

    fun setLastOpen(id: String) {
        prefs.edit().putString(KEY_LAST, id).apply()
    }

    /** Create an empty note; returns its id. */
    fun create(name: String): String {
        val id = UUID.randomUUID().toString()
        val clean = name.ifBlank { "Note" }.take(64)
        file(id).writeText(CalcFile.write(TapeDoc(CalcMeta(), emptyList()), emptyList()))
        // Filter first: list() would adopt the just-written file as an orphan,
        // which would persist the id twice without this.
        persist(list().filter { it.id != id } + NoteMeta(id, clean))
        NcalLogger.i("Notes", "created id=$id name=$clean")
        return id
    }

    fun rename(id: String, name: String) {
        val clean = name.trim().ifBlank { return }.take(64)
        persist(list().map { if (it.id == id) it.copy(name = clean) else it })
        NcalLogger.i("Notes", "renamed id=$id name=$clean")
    }

    /** Duplicate a note (new id/UUID, "<name> copy"); returns the new id. */
    fun duplicate(id: String): String? {
        if (!file(id).exists()) return null
        val newId = UUID.randomUUID().toString()
        return try {
            val text = file(id).readText()
            val doc = CalcFile.parse(text)
            val eval = TapeEvaluator.evaluate(doc.lines, doc.meta.decimals)
            val meta = doc.meta.copy(uuid = newId, caretLine = 0, caretOffset = 0)
            file(newId).writeText(CalcFile.write(doc.copy(meta = meta), eval.subtotals))
            val oldName = list().firstOrNull { it.id == id }?.name ?: "Note"
            persist(list().filter { it.id != newId } + NoteMeta(newId, "$oldName copy".take(64)))
            NcalLogger.i("Notes", "duplicated id=$id -> $newId")
            newId
        } catch (t: Throwable) {
            NcalLogger.e("Notes", "duplicate failed id=$id", t)
            null
        }
    }

    /** Import raw text (pasted, picked, or shared) as a new note; returns its id. */
    fun importDoc(name: String, text: String): String? {
        return try {
            val doc = CalcFile.parse(text)
            val eval = TapeEvaluator.evaluate(doc.lines, doc.meta.decimals)
            val id = UUID.randomUUID().toString()
            val meta = doc.meta.copy(uuid = id, caretLine = 0, caretOffset = 0)
            file(id).writeText(CalcFile.write(doc.copy(meta = meta), eval.subtotals))
            val clean = name.substringBeforeLast(".").trim().ifBlank { "Imported" }.take(64)
            persist(list().filter { it.id != id } + NoteMeta(id, clean))
            NcalLogger.i("Notes", "imported id=$id name=$clean lines=${doc.lines.size}")
            id
        } catch (t: Throwable) {
            NcalLogger.e("Notes", "import failed name=$name", t)
            null
        }
    }

    /** Raw stored file text (with header), e.g. for exporting a background note. */
    fun loadRaw(id: String): String? {
        return try {
            file(id).takeIf { it.exists() }?.readText()
        } catch (t: Throwable) {
            NcalLogger.e("Notes", "loadRaw failed id=$id", t)
            null
        }
    }

    fun delete(id: String) {
        if (file(id).delete()) NcalLogger.i("Notes", "deleted id=$id")
        persist(list().filter { it.id != id })
        prefs.edit().remove(KEY_NAME + id).apply()
    }

    /** Load note body text (header stripped) for the editor. Null if unreadable. */
    fun load(id: String): CalcExport.ImportResult? {
        return try {
            val text = file(id).readText()
            CalcExport.importToTapeText(text).also {
                NcalLogger.i("Notes", "loaded id=$id grand=${it.grandTotal}")
            }
        } catch (t: Throwable) {
            NcalLogger.e("Notes", "load failed id=$id", t)
            null
        }
    }

    /** Canonical save of the editor text (debounced autosave calls this). */
    fun save(id: String, tapeText: String, meta: CalcMeta) {
        try {
            val parsed = CalcFile.parse(tapeText)
            val eval = TapeEvaluator.evaluate(parsed.lines, parsed.meta.decimals)
            file(id).writeText(CalcFile.write(parsed.copy(meta = meta), eval.subtotals))
            NcalLogger.d("Notes", "saved id=$id lines=${parsed.lines.size} grand=${eval.grandTotal}")
        } catch (t: Throwable) {
            NcalLogger.e("Notes", "save failed id=$id", t)
        }
    }

    private fun persist(metas: List<NoteMeta>) {
        val deduped = metas.distinctBy { it.id }
        val ed = prefs.edit().putString(KEY_ORDER, deduped.joinToString(",") { it.id })
        deduped.forEach { ed.putString(KEY_NAME + it.id, it.name) }
        ed.apply()
    }

    companion object {
        private const val KEY_ORDER = "order"
        private const val KEY_NAME = "name_"
        private const val KEY_LAST = "last"
    }
}
