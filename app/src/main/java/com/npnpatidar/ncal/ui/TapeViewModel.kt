package com.npnpatidar.ncal.ui

import android.content.Context
import androidx.lifecycle.ViewModel
import com.npnpatidar.ncal.export.CalcExport
import com.npnpatidar.ncal.logging.NcalLogger
import com.npnpatidar.ncal.tape.CalcFile
import com.npnpatidar.ncal.tape.CalcMeta
import com.npnpatidar.ncal.tape.TapeEvaluator
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.math.BigDecimal
import java.math.RoundingMode

data class TapeUiState(
    val tapeText: String = DEFAULT_TAPE,
    val totalText: String = "0",
    val grandText: String = "0",
    val memoryText: String = "0",
    val errors: List<String> = emptyList(),
    val message: String? = null,
    val darkTheme: Boolean = false,
    val decimals: Int = 5,
) {
    companion object {
        val DEFAULT_TAPE = " + 0\n"
    }
}

/**
 * Single source of truth for the tape. Every mutation is logged exhaustively
 * (see Download/ncal/ncal-*.log) and immediately re-evaluated.
 */
class TapeViewModel : ViewModel() {

    private val _state = MutableStateFlow(TapeUiState())
    val state: StateFlow<TapeUiState> = _state.asStateFlow()

    private var memory: BigDecimal = BigDecimal.ZERO
    private val undoStack = ArrayDeque<String>(50)
    private val redoStack = ArrayDeque<String>(50)
    private var meta: CalcMeta = CalcMeta()

    init {
        NcalLogger.i("Tape", "ViewModel init")
        reevaluate("init")
    }

    fun onTapeChange(text: String) {
        pushUndo(_state.value.tapeText)
        _state.update { it.copy(tapeText = text) }
        reevaluate("edit len=${text.length}")
    }

    /** Keypad press: append a token at the end of the tape. */
    fun key(token: String) {
        val prev = _state.value.tapeText
        pushUndo(prev)
        // Trim trailing whitespace first so operator tokens ("\n + ") never
        // create an accidental blank line (a blank starts a new section).
        val cur = prev.trimEnd()
        val next = "$cur$token"
        _state.update { it.copy(tapeText = next) }
        NcalLogger.d("Tape", "key=${token.trim()} lines=${next.lines().size}")
        reevaluate("key")
    }

    fun newLine() {
        val prev = _state.value.tapeText
        pushUndo(prev)
        _state.update { it.copy(tapeText = prev.trimEnd() + "\n ") }
        reevaluate("newline")
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
    }

    fun clear() {
        pushUndo(_state.value.tapeText)
        _state.update { it.copy(tapeText = TapeUiState.DEFAULT_TAPE) }
        NcalLogger.i("Tape", "AC")
        reevaluate("ac")
    }

    fun undo() {
        val prev = undoStack.removeLastOrNull() ?: return
        redoStack.addLast(_state.value.tapeText)
        _state.update { it.copy(tapeText = prev) }
        NcalLogger.d("Tape", "undo")
        reevaluate("undo")
    }

    fun redo() {
        val next = redoStack.removeLastOrNull() ?: return
        undoStack.addLast(_state.value.tapeText)
        _state.update { it.copy(tapeText = next) }
        NcalLogger.d("Tape", "redo")
        reevaluate("redo")
    }

    fun memoryAdd() = memoryOp("M+") { it.add(currentTotal(), TapeEvaluator.MC) }
    fun memorySub() = memoryOp("M-") { it.subtract(currentTotal(), TapeEvaluator.MC) }
    fun memoryClear() = memoryOp("MC") { BigDecimal.ZERO }

    fun memoryRecall() {
        val cur = _state.value.tapeText
        pushUndo(cur)
        val line = CalcFile.formatEntry('+', memory, false, "MR", meta)
        _state.update { it.copy(tapeText = "$cur\n$line\n") }
        NcalLogger.i("Tape", "MR value=$memory")
        reevaluate("mr")
    }

    fun toggleTheme() {
        _state.update { it.copy(darkTheme = !it.darkTheme) }
        NcalLogger.i("Tape", "theme dark=${_state.value.darkTheme}")
    }

    fun setDecimals(d: Int) {
        meta = meta.copy(decimals = d.coerceIn(0, 8))
        _state.update { it.copy(decimals = meta.decimals) }
        NcalLogger.i("Tape", "decimals=$d")
        reevaluate("decimals")
    }

    fun exportCalc(context: Context, name: String) {
        val uri = CalcExport.exportCalc(context, name, _state.value.tapeText)
        _state.update { it.copy(message = if (uri != null) "Saved $name.calc → Download/ncal" else "Export failed (see log)") }
    }

    fun exportTxt(context: Context, name: String) {
        val uri = CalcExport.exportTxt(context, name, _state.value.tapeText)
        _state.update { it.copy(message = if (uri != null) "Saved $name.txt → Download/ncal" else "Export failed (see log)") }
    }

    fun importText(text: String) {
        pushUndo(_state.value.tapeText)
        val res = CalcExport.importToTapeText(text)
        meta = res.meta
        _state.update { it.copy(tapeText = res.tapeText, decimals = res.meta.decimals) }
        NcalLogger.i("Tape", "imported grand=${res.grandTotal}")
        reevaluate("import")
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

    private fun fmt(v: BigDecimal): String =
        v.setScale(_state.value.decimals, RoundingMode.HALF_UP).toPlainString()

    private fun pushUndo(text: String) {
        undoStack.addLast(text)
        if (undoStack.size > 50) undoStack.removeFirst()
        redoStack.clear()
    }
}
