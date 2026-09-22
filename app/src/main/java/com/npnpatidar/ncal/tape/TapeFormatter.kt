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
        val gap = " ".repeat(indent.coerceIn(1, 8))
        fun num(v: java.math.BigDecimal, pct: Boolean): String {
            val s = groupNumber(
                v.setScale(decimals, RoundingMode.HALF_UP).toPlainString(),
                grouping,
            )
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
                    if (line.comment.isNotBlank()) "${line.op} $n$gap${line.comment}"
                    else "${line.op} $n".trimEnd()
                }
                is TapeLine.Balance -> {
                    val n = num(line.value, false).padEnd(width)
                    if (line.comment.isNotBlank()) "+ $n$gap${line.comment}"
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
