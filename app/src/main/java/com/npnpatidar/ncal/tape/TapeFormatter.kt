package com.npnpatidar.ncal.tape

import java.math.RoundingMode

/** Thousands grouping for display. Both comma styles stay parseable
 * ([CalcFile] strips commas); there is deliberately no space style, which
 * would be ambiguous in plain-text parsing. */
enum class Grouping { OFF, COMMA, INDIAN }

/**
 * Display layout for the notepad. `=` rewrites the tape with three
 * left-aligned columns — operator, number, comment:
 * ```
 * + 10.00  alpha
 * + 2.50   b
 *  ------------------
 * + 12.50
 * ```
 * Storage/export stay canonical `.calc` ([CalcFile.write] re-canonicalizes on
 * every save/export), and the parser reads both layouts, so this is purely
 * presentational. Separators keep the canonical shape on purpose.
 */
object TapeFormatter {

    fun pretty(
        tapeText: String,
        decimals: Int,
        indent: Int = 2,
        grouping: Grouping = Grouping.OFF,
    ): String {
        val doc = CalcFile.parse(tapeText)
        // Snap balances to recomputed running totals: `=` (and any reformat)
        // must never cement a stale mid-edit value into the file.
        val eval = TapeEvaluator.evaluate(doc.lines, decimals)
        val gap = " ".repeat(indent.coerceIn(1, 8))
        fun num(v: java.math.BigDecimal, pct: Boolean): String =
            formatNum(v, decimals, pct, grouping)
        val width = doc.lines.mapIndexedNotNull { i, line ->
            when (line) {
                is TapeLine.Entry -> {
                    val (_, abs) = displayParts(line.op, line.amount)
                    num(abs, line.isPercent).length
                }
                is TapeLine.Balance -> {
                    val (_, abs) = displayParts('+', eval.balanceTotals[i] ?: line.value)
                    num(abs, false).length
                }
                else -> null
            }
        }.maxOrNull()?.coerceAtLeast(1) ?: 0
        val out = doc.lines.mapIndexed { i, line ->
            when (line) {
                is TapeLine.Entry -> {
                    val (op, abs) = displayParts(line.op, line.amount)
                    val n = num(abs, line.isPercent).padEnd(width)
                    if (line.comment.isNotBlank()) "$op $n$gap${line.comment}"
                    else "$op $n".trimEnd()
                }
                is TapeLine.Balance -> {
                    val (op, abs) = displayParts('+', eval.balanceTotals[i] ?: line.value)
                    val n = num(abs, false).padEnd(width)
                    if (line.comment.isNotBlank()) "$op $n$gap${line.comment}"
                    else "$op $n".trimEnd()
                }
                is TapeLine.Separator -> CalcFile.SEPARATOR
                is TapeLine.Blank -> ""
                is TapeLine.Heading -> line.raw
                is TapeLine.Comment -> line.raw
            }
        }
        return out.joinToString("\n").trimEnd() + "\n"
    }

    /** Scaled (+ grouped, + `%`) rendering of one amount. Shared by [pretty]
     * and [patchBalances] so live display and `=` agree exactly. */
    fun formatNum(v: java.math.BigDecimal, decimals: Int, pct: Boolean, grouping: Grouping): String {
        val s = groupNumber(
            v.setScale(decimals, RoundingMode.HALF_UP).toPlainString(),
            grouping,
        )
        return if (pct) "$s%" else s
    }

    /**
     * Effective display operator + magnitude: a negative `+X` shows as `- X`
     * (and `-(-X)` as `+ X`), so the operator column only ever carries the
     * sign and the number column stays aligned. Zero (including `-0.00`)
     * stays `+`.
     */
    fun displayParts(op: Char, amount: java.math.BigDecimal): Pair<Char, java.math.BigDecimal> {
        if ((op == '+' || op == '-') && amount.signum() < 0) {
            return (if (op == '+') '-' else '+') to amount.negate()
        }
        return op to amount
    }

    /**
     * Live refresh: rewrite ONLY balance lines to their recomputed running
     * totals ([EvalResult.balanceTotals]), leaving every entry byte-identical
     * so typing is never disturbed. Returns null when nothing would change.
     * Line count and order never change, so the cursor stays on its line.
     */
    fun patchBalances(
        rawText: String,
        doc: TapeDoc,
        eval: EvalResult,
        decimals: Int,
        indent: Int,
        grouping: Grouping,
    ): String? {
        val raws = rawText.split("\n")
        if (raws.size != doc.lines.size) return null
        val gap = " ".repeat(indent.coerceIn(1, 8))
        val width = doc.lines.mapIndexedNotNull { i, line ->
            when (line) {
                is TapeLine.Entry -> {
                    val (_, abs) = displayParts(line.op, line.amount)
                    formatNum(abs, decimals, line.isPercent, grouping).length
                }
                is TapeLine.Balance -> {
                    val (_, abs) = displayParts('+', eval.balanceTotals[i] ?: line.value)
                    formatNum(abs, decimals, false, grouping).length
                }
                else -> null
            }
        }.maxOrNull()?.coerceAtLeast(1) ?: 0
        var changed = false
        val out = raws.mapIndexed { i, raw ->
            val line = doc.lines[i]
            if (line is TapeLine.Balance) {
                val v = eval.balanceTotals[i] ?: line.value
                val (op, abs) = displayParts('+', v)
                val n = formatNum(abs, decimals, false, grouping).padEnd(width)
                val fresh = if (line.comment.isNotBlank()) "$op $n$gap${line.comment}"
                else "$op $n".trimEnd()
                if (fresh != raw) changed = true
                fresh
            } else {
                raw
            }
        }
        return if (changed) out.joinToString("\n") else null
    }
    /** Per-line display style: subtotals bold, negative amounts red. */
    data class LineMark(val bold: Boolean, val negative: Boolean)

    /**
     * One mark per doc line (1:1, same order). Balance negativity uses the
     * recomputed running totals so marks always agree with what [patchBalances]
     * displays.
     */
    fun markLines(doc: TapeDoc, eval: EvalResult): List<LineMark> {
        return doc.lines.mapIndexed { i, line ->
            when (line) {
                is TapeLine.Entry -> {
                    val neg = if (line.op == '*' || line.op == '/' || line.op == '^') {
                        line.amount.signum() < 0
                    } else {
                        displayParts(line.op, line.amount).first == '-'
                    }
                    LineMark(bold = false, negative = neg)
                }
                is TapeLine.Balance -> {
                    val v = eval.balanceTotals[i] ?: line.value
                    LineMark(bold = true, negative = v.signum() < 0)
                }
                else -> LineMark(bold = false, negative = false)
            }
        }
    }

    /** Insert thousands separators into a plain scaled number (`1234567.89`). */
    fun groupNumber(s: String, grouping: Grouping): String {
        if (grouping == Grouping.OFF) return s
        val pct = s.endsWith("%")
        val core = if (pct) s.dropLast(1) else s
        val dot = core.indexOf('.')
        val intPart = if (dot < 0) core else core.substring(0, dot)
        val fracPart = if (dot < 0) "" else core.substring(dot)
        val grouped = when (grouping) {
            Grouping.OFF -> intPart
            Grouping.COMMA -> intPart.replace(Regex("(\\d)(?=(\\d{3})+$)"), "$1,")
            Grouping.INDIAN -> indian(intPart)
        }
        return grouped + fracPart + if (pct) "%" else ""
    }

    private fun indian(intPart: String): String {
        if (intPart.length <= 3) return intPart
        val tail = intPart.takeLast(3)
        val head = intPart.dropLast(3).reversed().chunked(2).joinToString(",").reversed()
        return "$head,$tail"
    }
}
