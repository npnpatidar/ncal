package com.npnpatidar.ncal.export

import android.content.Context
import android.net.Uri
import com.npnpatidar.ncal.logging.NcalLogger
import com.npnpatidar.ncal.storage.MediaStoreHelper
import com.npnpatidar.ncal.tape.CalcFile
import com.npnpatidar.ncal.tape.CalcMeta
import com.npnpatidar.ncal.tape.TapeEvaluator
import com.npnpatidar.ncal.tape.TapeFormatter
import com.npnpatidar.ncal.tape.TapeLimits

object CalcExport {

    fun exportCalc(context: Context, docName: String, tapeText: String, meta: CalcMeta? = null): Uri? =
        exportCalcResult(context, docName, tapeText, meta).uri

    /** Caller meta (live editor state) wins when provided, otherwise the parsed header wins. */
    fun resolveExportMeta(caller: CalcMeta?, parsed: CalcMeta): CalcMeta = caller ?: parsed

    fun exportCalcResult(
        context: Context,
        docName: String,
        tapeText: String,
        meta: CalcMeta? = null,
    ): ExportResult {
        return try {
            if (tapeText.length > TapeLimits.MAX_INPUT_CHARS) {
                return ExportResult(null, listOf("input exceeds the supported size"), emptyList())
            }
            TapeLimits.ensureRenderedBudget(TapeLimits.utf8Size(tapeText) + 128L)
            val parsed = CalcFile.parse(tapeText, meta ?: CalcMeta())
            val blockingWarnings = exportWarnings(parsed.warnings)
            if (blockingWarnings.isNotEmpty()) {
                return ExportResult(null, blockingWarnings, parsed.warnings)
            }
            val eval = TapeEvaluator.evaluate(parsed.lines, parsed.meta.decimals)
            if (eval.errors.isNotEmpty()) {
                return ExportResult(null, eval.errors, parsed.warnings)
            }
            val effectiveMeta = resolveExportMeta(meta, parsed.meta)
            val text = CalcFile.write(parsed.copy(meta = effectiveMeta), eval.balanceTotals)
            val fileName = sanitize(docName) + ".calc"
            val uri = MediaStoreHelper.writeText(context, fileName, text, "application/octet-stream")
            NcalLogger.i("Export", "calc export lines=${parsed.lines.size} uri=${if (uri != null) "ok" else "failed"}")
            ExportResult(uri, emptyList(), parsed.warnings)
        } catch (t: Throwable) {
            ExportResult(null, listOf(t.message ?: "Export failed"), emptyList())
        }
    }

    fun exportTxt(context: Context, docName: String, tapeText: String, meta: CalcMeta? = null): Uri? =
        exportTxtResult(context, docName, tapeText, meta).uri

    fun exportTxtResult(
        context: Context,
        docName: String,
        tapeText: String,
        meta: CalcMeta? = null,
    ): ExportResult {
        return try {
            if (tapeText.length > TapeLimits.MAX_INPUT_CHARS) {
                return ExportResult(null, listOf("input exceeds the supported size"), emptyList())
            }
            TapeLimits.ensureRenderedBudget(TapeLimits.utf8Size(tapeText) + 128L)
            val parsed = CalcFile.parse(tapeText, meta ?: CalcMeta())
            val blockingWarnings = exportWarnings(parsed.warnings)
            if (blockingWarnings.isNotEmpty()) {
                return ExportResult(null, blockingWarnings, parsed.warnings)
            }
            val eval = TapeEvaluator.evaluate(parsed.lines, parsed.meta.decimals)
            if (eval.errors.isNotEmpty()) return ExportResult(null, eval.errors, parsed.warnings)
            val body = buildString {
                appendLine("# ncal export ${java.util.UUID.randomUUID()}")
                append(tapeText.trimEnd())
                appendLine()
                appendLine(
                    "= ${TapeFormatter.formatNum(
                        eval.grandTotal,
                        parsed.meta.decimals,
                        false,
                        com.npnpatidar.ncal.tape.Grouping.OFF,
                        parsed.meta.decSep,
                        parsed.meta.thouSep,
                    )}",
                )
            }
            TapeLimits.ensureRenderedTextBudget(body)
            val uri = MediaStoreHelper.writeText(context, sanitize(docName) + ".txt", body)
            NcalLogger.i("Export", "txt export uri=${if (uri != null) "ok" else "failed"}")
            ExportResult(uri, emptyList(), parsed.warnings)
        } catch (t: Throwable) {
            ExportResult(null, listOf(t.message ?: "Export failed"), emptyList())
        }
    }

    fun importToTapeText(text: String, fallbackMeta: CalcMeta = CalcMeta()): ImportResult {
        require(text.length <= TapeLimits.MAX_INPUT_CHARS) { "input exceeds the supported size" }
        val doc = CalcFile.parse(text, fallbackMeta)
        val eval = TapeEvaluator.evaluate(doc.lines, doc.meta.decimals)
        val canonical = CalcFile.write(doc, eval.balanceTotals)
        val close = "</SFRCalculatorHeader>"
        val body = canonical.substringAfter("$close\n").trimEnd() + "\n"
        val total = TapeFormatter.formatNum(
            eval.grandTotal,
            doc.meta.decimals,
            false,
            com.npnpatidar.ncal.tape.Grouping.OFF,
            doc.meta.decSep,
            doc.meta.thouSep,
        )
        return ImportResult(body, doc.meta, total, eval.errors, doc.warnings)
    }

    data class ExportResult(
        val uri: Uri?,
        val errors: List<String> = emptyList(),
        val warnings: List<String> = emptyList(),
    ) {
        val successful: Boolean get() = uri != null && errors.isEmpty()
    }

    data class ImportResult(
        val tapeText: String,
        val meta: CalcMeta,
        val grandTotal: String,
        val errors: List<String> = emptyList(),
        val warnings: List<String> = emptyList(),
    )

    private fun exportWarnings(warnings: List<String>): List<String> = warnings.filter { warning ->
        val text = warning.lowercase()
        text.contains("bad number") || text.contains("exceeds") || text.contains("too many") ||
            text.contains("too long") || text.contains("brackets") || text.contains("variables") ||
            text.contains("header metadata")
    }

    private fun sanitize(name: String): String {
        val s = name.trim().ifBlank { "ncal" }.replace(Regex("[^A-Za-z0-9_-]+"), "_")
        return s.take(64).ifBlank { "ncal" }
    }
}
