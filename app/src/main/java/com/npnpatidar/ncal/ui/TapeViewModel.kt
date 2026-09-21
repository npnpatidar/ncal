package com.npnpatidar.ncal.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.npnpatidar.ncal.export.CalcExport
import com.npnpatidar.ncal.logging.NcalLogger
import com.npnpatidar.ncal.storage.NotesRepository
import com.npnpatidar.ncal.tape.CalcFile
import com.npnpatidar.ncal.tape.CalcMeta
import com.npnpatidar.ncal.tape.TapeEvaluator
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.math.BigDecimal
import java.math.RoundingMode

enum class KeypadMode { CALC, SYSTEM }

data class TapeUiState(
    val tapeText: String = " + 0\n",
    val totalText: String = "0",
    val grandText: String = "0",
    val memoryText: String = "0",
    val errors: List<String> = emptyList(),
    val message: String? = null,
    val darkTheme: Boolean = false,
    val decimals: Int = 5,
    val notes: List<NotesRepository.NoteMeta> = emptyList(),
    val noteId: String = "",
    val noteName: String = "",
    val keypadMode: KeypadMode = KeypadMode.CALC,
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
    private var memory: BigDecimal = BigDecimal.ZERO
    private val undoStack = ArrayDeque<String>(50)
    private val redoStack = ArrayDeque<String>(50)
    private var meta: CalcMeta = CalcMeta()
    private var saveJob: Job? = null

    init {
        NcalLogger.i("Tape", "ViewModel init")
        var metas = repo.list()
        if (metas.isEmpty()) {
            repo.create("Note 1")
            metas = repo.list()
        }
        val last = repo.lastOpen()?.takeIf { id -> metas.any { it.id == id } }
            ?: metas.first().id
        loadNote(last)
        reevaluate("init")
    }

    // ---- notes (sidebar) ----

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
            val rest = repo.list()
            if (rest.isEmpty()) loadNote(repo.create("Note 1"))
            else loadNote(rest.first().id)
            reevaluate("delete-switch")
        } else {
            _state.update { it.copy(notes = repo.list()) }
        }
    }

    fun renameNote(name: String) {
        val clean = name.trim().take(64)
        if (clean.isBlank()) return
        repo.rename(_state.value.noteId, clean)
        _state.update { it.copy(noteName = clean, notes = repo.list()) }
    }

    private fun loadNote(id: String) {
        val res = repo.load(id)
        val notes = repo.list()
        val name = notes.firstOrNull { it.id == id }?.name ?: "Note"
        if (res != null) {
            meta = res.meta
            _state.update {
                it.copy(
                    tapeText = res.tapeText,
                    decimals = res.meta.decimals,
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

    // ---- editing ----

    fun onTapeChange(text: String) {
        pushUndo(_state.value.tapeText)
        _state.update { it.copy(tapeText = text) }
        reevaluate("edit len=${text.length}")
        scheduleSave()
    }

    /** Keypad press: append a token at the end of the tape. */
    fun key(token: String) {
        val prev = _state.value.tapeText
        pushUndo(prev)
        // Trim trailing whitespace first so operator tokens ("\n + ") never
        // create an accidental blank line (a blank starts a new section).
        val next = prev.trimEnd() + token
        _state.update { it.copy(tapeText = next) }
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
        _state.update { it.copy(tapeText = next) }
        NcalLogger.d("Tape", "backspace")
        reevaluate("backspace")
        scheduleSave()
    }

    fun newLine() {
        val prev = _state.value.tapeText
        pushUndo(prev)
        _state.update { it.copy(tapeText = prev.trimEnd() + "\n ") }
        reevaluate("newline")
        scheduleSave()
    }

    /** `=`: close the block — append separator + recomputed balance line. */
    fun equals() {
        val cur = _state.value.tapeText.trimEnd()
        pushUndo(_state.value.tapeText)
        val doc = CalcFile.parse(cur + "\n")
        val eval = TapeEvaluator.evaluate(doc.lines, doc.meta.decimals)
        val bal = CalcFile.formatEntry('+', eval.openTotal, false, "", doc.meta)
        val next = "$cur\n${CalcFile.SEPARATOR}\n$bal\n"
        _state.update { it.copy(tapeText = next) }
        NcalLogger.i("Tape", "equals total=${eval.openTotal} subs=${eval.subtotals.size}")
        reevaluate("equals")
        scheduleSave()
    }

    /** AC: clear the whole notepad (restorable via Undo). */
    fun clear() {
        pushUndo(_state.value.tapeText)
        _state.update { it.copy(tapeText = " + 0\n") }
        NcalLogger.i("Tape", "AC note=${_state.value.noteName}")
        reevaluate("ac")
        scheduleSave()
    }

    fun undo() {
        val prev = undoStack.removeLastOrNull() ?: return
        redoStack.addLast(_state.value.tapeText)
        _state.update { it.copy(tapeText = prev) }
        NcalLogger.d("Tape", "undo")
        reevaluate("undo")
        scheduleSave()
    }

    fun redo() {
        val next = redoStack.removeLastOrNull() ?: return
        undoStack.addLast(_state.value.tapeText)
        _state.update { it.copy(tapeText = next) }
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
        _state.update { it.copy(tapeText = cur.trimEnd() + "\n$line\n") }
        NcalLogger.i("Tape", "MR value=$memory")
        reevaluate("mr")
        scheduleSave()
    }

    fun toggleTheme() {
        _state.update { it.copy(darkTheme = !it.darkTheme) }
        NcalLogger.i("Tape", "theme dark=${_state.value.darkTheme}")
    }

    fun setKeypadMode(mode: KeypadMode) {
        _state.update { it.copy(keypadMode = mode) }
        NcalLogger.i("Tape", "keypadMode=$mode")
    }

    fun setDecimals(d: Int) {
        meta = meta.copy(decimals = d.coerceIn(0, 8))
        _state.update { it.copy(decimals = meta.decimals) }
        NcalLogger.i("Tape", "decimals=$d")
        reevaluate("decimals")
        scheduleSave()
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
        meta = res.meta
        _state.update { it.copy(tapeText = res.tapeText, decimals = res.meta.decimals) }
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
        return TapeEvaluator.evaluate(doc.lines, doc.meta.decimals).grandTotal
    }

    private fun reevaluate(why: String) {
        val text = _state.value.tapeText
        val doc = CalcFile.parse(text)
        meta = doc.meta
        val eval = TapeEvaluator.evaluate(doc.lines, doc.meta.decimals)
        for (w in doc.warnings) NcalLogger.w("Tape", "import: $w")
        for (e in eval.errors) NcalLogger.w("Tape", "eval: $e")
        NcalLogger.d(
            "Tape",
            "eval why=$why lines=${doc.lines.size} subs=${eval.subtotals.size} " +
                "grand=${eval.grandTotal} errs=${eval.errors.size}",
        )
        _state.update {
            it.copy(
                totalText = fmt(eval.grandTotal),
                grandText = fmt(eval.grandTotal),
                errors = eval.errors,
                decimals = doc.meta.decimals,
            )
        }
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
        v.setScale(_state.value.decimals, RoundingMode.HALF_UP).toPlainString()

    private fun pushUndo(text: String) {
        undoStack.addLast(text)
        if (undoStack.size > 50) undoStack.removeFirst()
        redoStack.clear()
    }
}
