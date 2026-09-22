package com.npnpatidar.ncal.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.npnpatidar.ncal.export.CalcExport
import com.npnpatidar.ncal.logging.NcalLogger
import com.npnpatidar.ncal.settings.AppSettings
import com.npnpatidar.ncal.settings.NoteSort
import com.npnpatidar.ncal.settings.SettingsStore
import com.npnpatidar.ncal.storage.NotesRepository
import com.npnpatidar.ncal.tape.CalcFile
import com.npnpatidar.ncal.tape.CalcMeta
import com.npnpatidar.ncal.tape.TapeEvaluator
import com.npnpatidar.ncal.tape.TapeFormatter
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.math.BigDecimal
import java.math.RoundingMode
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.TextRange

enum class KeypadMode { CALC, SYSTEM, HIDDEN }

data class TapeUiState(
    val tapeText: String = " + 0\n",
    val tapeSel: TextRange = TextRange.Zero,
    val totalText: String = "0",
    val grandText: String = "0",
    val memoryText: String = "0",
    val errors: List<String> = emptyList(),
    val message: String? = null,
    val decimals: Int = 5,
    val settings: AppSettings = AppSettings(),
    val notes: List<NotesRepository.NoteMeta> = emptyList(),
    val noteId: String = "",
    val noteName: String = "",
    val keypadMode: KeypadMode = KeypadMode.CALC,
    val appVersion: String = "",
)

/**
 * Single source of truth for the tape. Every mutation is logged exhaustively
 * (see Download/ncal/ncal-*.log), immediately re-evaluated, and autosaved
 * (debounced) into the current sidebar note.
 */
class TapeViewModel(app: Application) : AndroidViewModel(app) {

    private val _state = MutableStateFlow(TapeUiState())
    val state: StateFlow<TapeUiState> = _state.asStateFlow()

    private val repo = NotesRepository(app)
    private val settingsStore = SettingsStore(app)
    private var memory: BigDecimal = BigDecimal.ZERO
    private val undoStack = ArrayDeque<String>(50)
    private val redoStack = ArrayDeque<String>(50)
    private var meta: CalcMeta = CalcMeta()
    // Display decimals are VM state, NOT re-parsed: the on-screen tape has no
    // header, so adopting parse defaults here would reset them every keystroke.
    private var decimals: Int = 5
    private var saveJob: Job? = null

    init {
        NcalLogger.i("Tape", "ViewModel init")
        @Suppress("DEPRECATION")
        val pkg = app.packageManager.getPackageInfo(app.packageName, 0)
        val loaded = settingsStore.load()
        decimals = loaded.decimals
        _state.update {
            it.copy(
                appVersion = "v${pkg.versionName} (${pkg.versionCode})",
                settings = loaded,
                decimals = loaded.decimals,
            )
        }
        var metas = refreshNotes()
        if (metas.isEmpty()) {
            repo.create("Note 1")
            metas = refreshNotes()
        }
        val last = repo.lastOpen()?.takeIf { id -> metas.any { it.id == id } }
            ?: metas.first().id
        loadNote(last)
        reevaluate("init")
    }

    // ---- notes (sidebar) ----

    /** Notes in the sidebar order of the current sort setting. */
    private fun refreshNotes(): List<NotesRepository.NoteMeta> {
        val metas = repo.list()
        return when (_state.value.settings.noteSort) {
            NoteSort.DATE -> metas.sortedByDescending { repo.lastModified(it.id) }
            NoteSort.NAME_ASC -> metas.sortedBy { it.name.lowercase() }
            NoteSort.NAME_DESC -> metas.sortedByDescending { it.name.lowercase() }
        }
    }

    fun selectNote(id: String) {
        if (id == _state.value.noteId) return
        saveCurrent()
        loadNote(id)
        reevaluate("switch")
        NcalLogger.i("Tape", "selectNote id=$id")
    }

    fun createNote() {
        saveCurrent()
        val existing = _state.value.notes.map { it.name }
        var n = existing.size + 1
        var name = "Note $n"
        while (existing.contains(name)) {
            n++
            name = "Note $n"
        }
        loadNote(repo.create(name))
        reevaluate("new")
    }

    fun deleteNote(id: String) {
        repo.delete(id)
        NcalLogger.i("Tape", "deleteNote id=$id")
        if (id == _state.value.noteId) {
            val rest = refreshNotes()
            if (rest.isEmpty()) loadNote(repo.create("Note 1"))
            else loadNote(rest.first().id)
            reevaluate("delete-switch")
        } else {
            _state.update { it.copy(notes = refreshNotes()) }
        }
    }

    fun renameNote(name: String) {
        renameNoteById(_state.value.noteId, name)
    }

    fun renameNoteById(id: String, name: String) {
        val clean = name.trim().take(64)
        if (id.isBlank() || clean.isBlank()) return
        repo.rename(id, clean)
        _state.update {
            it.copy(
                notes = refreshNotes(),
                noteName = if (id == it.noteId) clean else it.noteName,
            )
        }
    }

    fun duplicateNote(id: String) {
        if (id.isBlank()) return
        saveCurrent()
        val newId = repo.duplicate(id)
        _state.update {
            it.copy(
                notes = refreshNotes(),
                message = if (newId != null) "Duplicated note" else "Duplicate failed (see log)",
            )
        }
    }

    /** Export any note (current note exports live unsaved edits). */
    fun exportNote(id: String, asCalc: Boolean) {
        val s = _state.value
        val name = s.notes.firstOrNull { it.id == id }?.name?.ifBlank { "ncal" } ?: "ncal"
        val text = if (id == s.noteId) s.tapeText else repo.loadRaw(id)
        if (text == null) {
            _state.update { it.copy(message = "Export failed (see log)") }
            return
        }
        val app = getApplication<Application>()
        val uri = if (asCalc) CalcExport.exportCalc(app, name, text)
        else CalcExport.exportTxt(app, name, text)
        _state.update {
            it.copy(message = if (uri != null) "Saved $name.${if (asCalc) "calc" else "txt"} → Download/ncal"
                else "Export failed (see log)")
        }
    }

    /** Import picked files (.calc/.txt, multiple) as new notes. */
    fun importFiles(uris: List<android.net.Uri>) {
        if (uris.isEmpty()) return
        val app = getApplication<Application>()
        var ok = 0
        var firstId: String? = null
        for (uri in uris) {
            try {
                val text = app.contentResolver.openInputStream(uri)
                    ?.bufferedReader()?.readText() ?: continue
                val name = displayNameOf(app, uri) ?: "Imported"
                val id = repo.importDoc(name, text) ?: continue
                if (firstId == null) firstId = id
                ok++
            } catch (t: Throwable) {
                NcalLogger.e("Tape", "importFiles failed uri=$uri", t)
            }
        }
        _state.update {
            it.copy(
                notes = refreshNotes(),
                message = if (ok > 0) "Imported $ok note${if (ok == 1) "" else "s"}" else "Import failed (see log)",
            )
        }
        NcalLogger.i("Tape", "importFiles ok=$ok/${uris.size}")
    }

    private fun loadNote(id: String) {
        val res = repo.load(id)
        val notes = refreshNotes()
        val name = notes.firstOrNull { it.id == id }?.name ?: "Note"
        if (res != null) {
            // Keep the file's UUID for save continuity, but display decimals
            // always follow the global setting (single source of truth).
            meta = res.meta.copy(decimals = decimals)
            val s = _state.value.settings
            _state.update {
                it.copy(
                    tapeText = TapeFormatter.pretty(res.tapeText, decimals, s.indent, s.grouping),
                    tapeSel = TextRange.Zero,
                    decimals = decimals,
                    notes = notes,
                    noteId = id,
                    noteName = name,
                )
            }
        } else {
            _state.update { it.copy(notes = notes, noteId = id, noteName = name) }
        }
        undoStack.clear()
        redoStack.clear()
        repo.setLastOpen(id)
    }

    // ---- settings ----

    /** Persist settings; re-layout the tape when display settings change. */
    fun updateSettings(next: AppSettings) {
        val prev = _state.value.settings
        settingsStore.save(next)
        _state.update { it.copy(settings = next) }
        NcalLogger.i("Tape", "settings decimals=${next.decimals} indent=${next.indent} " +
            "grouping=${next.grouping} sort=${next.noteSort} theme=${next.themeMode}")
        if (next.decimals != prev.decimals || next.indent != prev.indent || next.grouping != prev.grouping) {
            decimals = next.decimals
            meta = meta.copy(decimals = decimals)
            _state.update {
                it.copy(
                    tapeText = TapeFormatter.pretty(it.tapeText, decimals, next.indent, next.grouping),
                    decimals = decimals,
                )
            }
            reevaluate("settings")
            scheduleSave()
        }
        if (next.noteSort != prev.noteSort) {
            _state.update { it.copy(notes = refreshNotes()) }
        }
    }

    // ---- editing ----

    fun onTapeChange(v: TextFieldValue) {
        val prev = _state.value.tapeText
        if (_state.value.keypadMode == KeypadMode.SYSTEM && v.text == "$prev\n") {
            // Enter pressed at the very end of the tape: close the block,
            // exactly like `=`. (Enter anywhere else inserts a plain newline.)
            equals()
            return
        }
        pushUndo(prev)
        _state.update { it.copy(tapeText = v.text, tapeSel = v.selection) }
        reevaluate("edit len=${v.text.length}")
        scheduleSave()
    }

    /** Keypad press: append a token at the end of the tape. */
    fun key(token: String) {
        val prev = _state.value.tapeText
        pushUndo(prev)
        // Trim trailing whitespace first so operator tokens ("\n + ") never
        // create an accidental blank line (a blank starts a new section).
        val next = prev.trimEnd() + token
        _state.update { it.copy(tapeText = next, tapeSel = TextRange(next.length)) }
        NcalLogger.d("Tape", "key=${token.trim()} lines=${next.lines().size}")
        reevaluate("key")
        scheduleSave()
    }

    /** Backspace key: delete the last character. */
    fun backspace() {
        val prev = _state.value.tapeText
        val next = prev.trimEnd().dropLast(1)
        if (next == prev) return
        pushUndo(prev)
        _state.update { it.copy(tapeText = next, tapeSel = TextRange(next.length)) }
        NcalLogger.d("Tape", "backspace")
        reevaluate("backspace")
        scheduleSave()
    }

    fun newLine() {
        val prev = _state.value.tapeText
        pushUndo(prev)
        val next = prev.trimEnd() + "\n "
        _state.update { it.copy(tapeText = next, tapeSel = TextRange(next.length)) }
        reevaluate("newline")
        scheduleSave()
    }

    /** `=`: close the block — append separator + recomputed balance line,
     * then re-layout the tape in 3 left-aligned columns. Ignored when there
     * is no open block (empty tape, or tape already ending at a subtotal). */
    fun equals() {
        // NOTE: no trailing newline — parse() would turn it into a Blank line,
        // which resets the open section and made `=` append a 0.00000 balance.
        val cur = _state.value.tapeText.trimEnd()
        val probe = CalcFile.parse(cur)
        if (!TapeEvaluator.hasOpenEntries(probe.lines)) {
            _state.update { it.copy(message = "Nothing to total") }
            NcalLogger.d("Tape", "equals ignored: no open entries")
            return
        }
        pushUndo(_state.value.tapeText)
        NcalLogger.d("Tape", "equals before: ${snapshot(cur)}")
        val doc = CalcFile.parse(cur)
        val eval = TapeEvaluator.evaluate(doc.lines, decimals)
        val bal = CalcFile.formatEntry('+', eval.openTotal, false, "", doc.meta.copy(decimals = decimals))
        val s = _state.value.settings
        val next = TapeFormatter.pretty(
            "$cur\n${CalcFile.SEPARATOR}\n$bal",
            decimals,
            s.indent,
            s.grouping,
        )
        _state.update { it.copy(tapeText = next, tapeSel = TextRange(next.length)) }
        NcalLogger.i("Tape", "equals total=${eval.openTotal} subs=${eval.subtotals.size}")
        NcalLogger.d("Tape", "equals after: ${snapshot(next)}")
        reevaluate("equals")
        scheduleSave()
    }

    /** AC: clear the whole note — nothing left, not even a zero. Undo restores. */
    fun clear() {
        pushUndo(_state.value.tapeText)
        _state.update { it.copy(tapeText = "", tapeSel = TextRange.Zero) }
        NcalLogger.i("Tape", "AC note=${_state.value.noteName}")
        reevaluate("ac")
        scheduleSave()
    }

    fun undo() {
        val prev = undoStack.removeLastOrNull() ?: return
        redoStack.addLast(_state.value.tapeText)
        _state.update { it.copy(tapeText = prev, tapeSel = TextRange(prev.length)) }
        NcalLogger.d("Tape", "undo")
        reevaluate("undo")
        scheduleSave()
    }

    fun redo() {
        val next = redoStack.removeLastOrNull() ?: return
        undoStack.addLast(_state.value.tapeText)
        _state.update { it.copy(tapeText = next, tapeSel = TextRange(next.length)) }
        NcalLogger.d("Tape", "redo")
        reevaluate("redo")
        scheduleSave()
    }

    // ---- memory / prefs ----

    fun memoryAdd() = memoryOp("M+") { it.add(currentTotal(), TapeEvaluator.MC) }
    fun memorySub() = memoryOp("M-") { it.subtract(currentTotal(), TapeEvaluator.MC) }
    fun memoryClear() = memoryOp("MC") { BigDecimal.ZERO }

    fun memoryRecall() {
        val cur = _state.value.tapeText
        pushUndo(cur)
        val line = CalcFile.formatEntry('+', memory, false, "MR", meta)
        val recalled = cur.trimEnd() + "\n$line\n"
        _state.update { it.copy(tapeText = recalled, tapeSel = TextRange(recalled.length)) }
        NcalLogger.i("Tape", "MR value=$memory")
        reevaluate("mr")
        scheduleSave()
    }

    fun setKeypadMode(mode: KeypadMode) {
        _state.update { it.copy(keypadMode = mode) }
        NcalLogger.i("Tape", "keypadMode=$mode")
    }

    fun setDecimals(d: Int) {
        updateSettings(_state.value.settings.copy(decimals = d.coerceIn(0, 8)))
    }

    // ---- export ----

    fun exportCalc() {
        val s = _state.value
        val uri = CalcExport.exportCalc(getApplication(), s.noteName.ifBlank { "ncal" }, s.tapeText)
        _state.update { it.copy(message = if (uri != null) "Saved → Download/ncal" else "Export failed (see log)") }
    }

    fun exportTxt() {
        val s = _state.value
        val uri = CalcExport.exportTxt(getApplication(), s.noteName.ifBlank { "ncal" }, s.tapeText)
        _state.update { it.copy(message = if (uri != null) "Saved .txt → Download/ncal" else "Export failed (see log)") }
    }

    fun importText(text: String) {
        pushUndo(_state.value.tapeText)
        val res = CalcExport.importToTapeText(text)
        // Keep the file's UUID; display follows the global decimals setting.
        meta = res.meta.copy(decimals = decimals)
        val s = _state.value.settings
        _state.update {
            it.copy(
                tapeText = TapeFormatter.pretty(res.tapeText, decimals, s.indent, s.grouping),
                tapeSel = TextRange.Zero,
                decimals = decimals,
            )
        }
        NcalLogger.i("Tape", "imported grand=${res.grandTotal}")
        reevaluate("import")
        scheduleSave()
    }

    fun clearMessage() = _state.update { it.copy(message = null) }

    // ---- internals ----

    private fun memoryOp(tag: String, f: (BigDecimal) -> BigDecimal) {
        memory = f(memory)
        _state.update { it.copy(memoryText = fmt(memory)) }
        NcalLogger.i("Tape", "$tag memory=$memory")
    }

    private fun currentTotal(): BigDecimal {
        val doc = CalcFile.parse(_state.value.tapeText)
        return TapeEvaluator.evaluate(doc.lines, decimals).grandTotal
    }

    /**
     * Recompute everything. Balance (subtotal) lines are refreshed live to
     * their recomputed running totals while entry lines stay byte-identical,
     * so typing is never disturbed; the cursor stays on its line. A second
     * evaluation on the patched text keeps displayed totals consistent.
     */
    private fun reevaluate(why: String) {
        val s = _state.value
        val doc = CalcFile.parse(s.tapeText)
        val eval = TapeEvaluator.evaluate(doc.lines, decimals)
        var text = s.tapeText
        var sel = s.tapeSel
        var finalDoc = doc
        var finalEval = eval
        TapeFormatter.patchBalances(text, doc, eval, decimals, s.settings.indent, s.settings.grouping)
            ?.let { patched ->
                val doc2 = CalcFile.parse(patched)
                val eval2 = TapeEvaluator.evaluate(doc2.lines, decimals)
                sel = mapCursor(s.tapeSel, s.tapeText, patched)
                text = patched
                finalDoc = doc2
                finalEval = eval2
            }
        for (w in finalDoc.warnings) NcalLogger.w("Tape", "import: $w")
        for (e in finalEval.errors) NcalLogger.w("Tape", "eval: $e")
        NcalLogger.d(
            "Tape",
            "eval why=$why lines=${finalDoc.lines.size} subs=${finalEval.subtotals.size} " +
                "grand=${finalEval.grandTotal} errs=${finalEval.errors.size}",
        )
        _state.update {
            it.copy(
                tapeText = text,
                tapeSel = sel,
                totalText = fmt(finalEval.grandTotal),
                grandText = fmt(finalEval.grandTotal),
                errors = finalEval.errors,
                decimals = decimals,
            )
        }
    }

    /** Remap a cursor across a line-count-preserving rewrite (same line, clamped column). */
    private fun mapCursor(sel: TextRange, oldText: String, newText: String): TextRange {
        fun toLineCol(text: String, off: Int): Pair<Int, Int> {
            val lines = text.split("\n")
            var rest = off.coerceIn(0, text.length)
            var li = 0
            while (li < lines.size - 1 && rest > lines[li].length) {
                rest -= lines[li].length + 1
                li++
            }
            return li to rest
        }
        val newLines = newText.split("\n")
        fun toOffset(li: Int, col: Int): Int {
            val l = li.coerceIn(0, newLines.size - 1)
            return newLines.take(l).sumOf { it.length + 1 } + col.coerceAtMost(newLines[l].length)
        }
        val (l1, c1) = toLineCol(oldText, sel.start)
        val (l2, c2) = toLineCol(oldText, sel.end)
        return TextRange(toOffset(l1, c1), toOffset(l2, c2))
    }

    private fun scheduleSave() {
        saveJob?.cancel()
        saveJob = viewModelScope.launch {
            delay(800)
            saveCurrent()
        }
    }

    private fun saveCurrent() {
        val s = _state.value
        if (s.noteId.isBlank()) return
        repo.save(s.noteId, s.tapeText, meta)
    }

    private fun fmt(v: BigDecimal): String =
        v.setScale(decimals, RoundingMode.HALF_UP).toPlainString()

    /** Single-line, capped snapshot of tape text for debug logs. */
    private fun snapshot(text: String): String {
        val oneLine = text.replace("\n", "\\n")
        return "len=${text.length} <${oneLine.take(1500)}>"
    }

    private fun displayNameOf(app: Application, uri: android.net.Uri): String? {
        return try {
            app.contentResolver.query(
                uri,
                arrayOf(android.provider.OpenableColumns.DISPLAY_NAME),
                null, null, null,
            )?.use { c ->
                if (c.moveToFirst()) c.getString(0) else null
            } ?: uri.lastPathSegment?.substringAfterLast("/")
        } catch (_: Throwable) {
            null
        }
    }

    private fun pushUndo(text: String) {
        undoStack.addLast(text)
        if (undoStack.size > 50) undoStack.removeFirst()
        redoStack.clear()
    }
}
