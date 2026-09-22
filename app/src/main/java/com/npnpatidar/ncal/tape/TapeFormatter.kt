package com.npnpatidar.ncal.tape

import java.math.RoundingMode

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

    fun pretty(tapeText: String, decimals: Int): String {
        val doc = CalcFile.parse(tapeText)
        fun num(v: java.math.BigDecimal, pct: Boolean): String {
            val s = v.setScale(decimals, RoundingMode.HALF_UP).toPlainString()
            return if (pct) "$s%" else s
        }
        val width = doc.lines.mapNotNull {
            when (it) {
                is TapeLine.Entry -> num(it.amount, it.isPercent).length
                is TapeLine.Balance -> num(it.value, false).length
                else -> null
            }
        }.maxOrNull()?.coerceAtLeast(1) ?: 0
        val out = doc.lines.map { line ->
            when (line) {
                is TapeLine.Entry -> {
                    val n = num(line.amount, line.isPercent).padEnd(width)
                    if (line.comment.isNotBlank()) "${line.op} $n  ${line.comment}"
                    else "${line.op} $n".trimEnd()
                }
                is TapeLine.Balance -> {
                    val n = num(line.value, false).padEnd(width)
                    if (line.comment.isNotBlank()) "+ $n  ${line.comment}"
                    else "+ $n".trimEnd()
                }
                is TapeLine.Separator -> CalcFile.SEPARATOR
                is TapeLine.Blank -> ""
                is TapeLine.Heading -> line.raw
                is TapeLine.Comment -> line.raw
            }
        }
        return out.joinToString("\n").trimEnd() + "\n"
    }
}
