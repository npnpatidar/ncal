package com.npnpatidar.ncal.export

import android.content.Context
import android.net.Uri
import com.npnpatidar.ncal.logging.NcalLogger
import com.npnpatidar.ncal.storage.MediaStoreHelper
import com.npnpatidar.ncal.tape.CalcFile
import com.npnpatidar.ncal.tape.CalcMeta
import com.npnpatidar.ncal.tape.TapeEvaluator

/**
 * `.calc` import/export compatible with CalcTape.
 *
 * - Export writes a canonical file (fixed header order, 17-wide amounts,
 *   18-dash separators, balance restatements from recomputed subtotals) to
 *   `Download/ncal/<name>.calc` — the same folder as the log files.
 * - [exportTxt] writes the human-readable tape (body only, `.txt`).
 * - Import accepts pasted/file-picked text with or without a header.
 */
object CalcExport {

    fun exportCalc(context: Context, docName: String, tapeText: String): Uri? {
        val parsed = CalcFile.parse(tapeText)
        NcalLogger.i(
            "Export",
            "exportCalc name=$docName lines=${parsed.lines.size} " +
                "warnings=${parsed.warnings} decimals=${parsed.meta.decimals}",
        )
        val eval = TapeEvaluator.evaluate(parsed.lines, parsed.meta.decimals)
        for (err in eval.errors) NcalLogger.w("Export", "eval: $err")
        val meta = parsed.meta.copy(
            caretLine = parsed.lines.size,
            caretOffset = 0,
        )
        val text = CalcFile.write(
            parsed.copy(meta = meta),
            eval.subtotals,
        )
        val fileName = sanitize(docName) + ".calc"
        val uri = MediaStoreHelper.writeText(context, fileName, text)
        NcalLogger.i("Export", "wrote $fileName uri=$uri bytes=${text.length}")
        return uri
    }

    fun exportTxt(context: Context, docName: String, tapeText: String): Uri? {
        val parsed = CalcFile.parse(tapeText)
        val eval = TapeEvaluator.evaluate(parsed.lines, parsed.meta.decimals)
        val body = buildString {
            appendLine("# ncal export ${java.util.UUID.randomUUID()}")
            append(tapeText.trimEnd())
            appendLine()
            appendLine("= ${eval.grandTotal}")
        }
        val uri = MediaStoreHelper.writeText(context, sanitize(docName) + ".txt", body)
        NcalLogger.i("Export", "wrote txt uri=$uri grand=${eval.grandTotal}")
        return uri
    }

    /** Import: parse anything (header optional) and return canonical display text. */
    fun importToTapeText(text: String): ImportResult {
        val doc = CalcFile.parse(text)
        NcalLogger.i(
            "Import",
            "lines=${doc.lines.size} warnings=${doc.warnings} decimals=${doc.meta.decimals}",
        )
        val eval = TapeEvaluator.evaluate(doc.lines, doc.meta.decimals)
        for (err in eval.errors) NcalLogger.w("Import", "eval: $err")
        val canonical = CalcFile.write(doc, eval.subtotals)
        // Strip the regenerated header for the on-screen tape (header is
        // re-created on export); keep body only.
        val body = canonical.substringAfter("</SFRCalculatorHeader>\n").trimEnd() + "\n"
        return ImportResult(body, doc.meta, eval.grandTotal.toPlainString())
    }

    data class ImportResult(val tapeText: String, val meta: CalcMeta, val grandTotal: String)

    private fun sanitize(name: String): String {
        val s = name.trim().ifBlank { "ncal" }.replace(Regex("[^A-Za-z0-9_-]+"), "_")
        return s.take(64)
    }
}
