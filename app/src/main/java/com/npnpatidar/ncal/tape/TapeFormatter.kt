package com.npnpatidar.ncal.tape

import java.math.BigDecimal
import java.math.RoundingMode

enum class Grouping { OFF, COMMA, INDIAN }

object TapeFormatter {

    fun pretty(
        tapeText: String,
        decimals: Int,
        indent: Int = 2,
        grouping: Grouping = Grouping.OFF,
        meta: CalcMeta? = null,
    ): String {
        val fallbackMeta = meta ?: CalcMeta(decimals = TapeLimits.safeDecimals(decimals))
        val doc = CalcFile.parse(tapeText, fallbackMeta)
        val decimalSeparator = doc.meta.decSep
        val thousandsSeparator = doc.meta.thouSep
        TapeLimits.ensureRenderedBudget(
            estimatedPrettySize(doc, null, decimals, indent, decimalSeparator, thousandsSeparator),
        )
        val eval = TapeEvaluator.evaluate(doc.lines, decimals)
        TapeLimits.ensureRenderedBudget(
            estimatedPrettySize(doc, eval, decimals, indent, decimalSeparator, thousandsSeparator),
        )
        val gap = " ".repeat(indent.coerceIn(1, 8))
        val width = doc.lines.mapIndexedNotNull { i, line ->
            when (line) {
                is TapeLine.Entry -> {
                    val (_, abs) = displayParts(line.op, line.amount)
                    formatEntryNum(
                        abs,
                        decimals,
                        line.isPercent,
                        grouping,
                        decimalSeparator,
                        thousandsSeparator,
                    ).length
                }
                is TapeLine.Balance -> {
                    val (_, abs) = displayParts('+', eval.balanceTotals[i] ?: line.value)
                    formatNum(
                        abs,
                        decimals,
                        false,
                        grouping,
                        decimalSeparator,
                        thousandsSeparator,
                    ).length
                }
                else -> null
            }
        }.maxOrNull()?.coerceAtLeast(1) ?: 0
        val rendered = doc.lines.mapIndexed { index, line ->
            when (line) {
                is TapeLine.Entry -> {
                    val (op, abs) = displayParts(line.op, line.amount)
                    val n = formatEntryNum(
                        abs,
                        decimals,
                        line.isPercent,
                        grouping,
                        decimalSeparator,
                        thousandsSeparator,
                    ).padEnd(width)
                    if (line.comment.isNotEmpty()) "$op $n$gap${line.comment}" else "$op ${n.trimEnd()}"
                }
                is TapeLine.Balance -> {
                    val (op, abs) = displayParts('+', eval.balanceTotals[index] ?: line.value)
                    val n = formatNum(
                        abs,
                        decimals,
                        false,
                        grouping,
                        decimalSeparator,
                        thousandsSeparator,
                    ).padEnd(width)
                    if (line.comment.isNotEmpty()) "$op $n$gap${line.comment}" else "$op ${n.trimEnd()}"
                }
                is TapeLine.Separator -> CalcFile.SEPARATOR
                is TapeLine.Blank -> ""
                is TapeLine.Heading -> line.raw
                is TapeLine.Comment -> line.raw
            }
        }.toMutableList()
        while (rendered.lastOrNull() == "") rendered.removeAt(rendered.lastIndex)
        if (rendered.lastOrNull() == CalcFile.SEPARATOR) {
            rendered[rendered.lastIndex] = CalcFile.SEPARATOR.trimEnd()
        }
        val result = rendered.joinToString("\n") + "\n"
        TapeLimits.ensureRenderedTextBudget(result)
        return result
    }

    private fun estimatedPrettySize(
        doc: TapeDoc,
        eval: EvalResult?,
        decimals: Int,
        indent: Int,
        decimalSeparator: Char = '.',
        thousandsSeparator: Char = ',',
    ): Long {
        val gap = indent.coerceIn(1, 8).toLong()
        val rawSizes = LongArray(doc.lines.size)
        var numericCount = 0L
        var numericWidth = 0L
        doc.lines.forEachIndexed { index, line ->
            val amountSize = when (line) {
                is TapeLine.Entry -> {
                    val (_, amount) = displayParts(line.op, line.amount)
                    TapeLimits.estimatedAmountChars(
                        amount,
                        decimals,
                        line.isPercent,
                        true,
                        decimalSeparator,
                        thousandsSeparator,
                    )
                }
                is TapeLine.Balance -> {
                    val (_, amount) = displayParts('+', eval?.balanceTotals?.get(index) ?: line.value)
                    TapeLimits.estimatedAmountChars(
                        amount,
                        decimals,
                        false,
                        false,
                        decimalSeparator,
                        thousandsSeparator,
                    )
                }
                else -> 0L
            }
            rawSizes[index] = when (line) {
                is TapeLine.Entry -> TapeLimits.utf8Size(line.comment) + gap
                is TapeLine.Balance -> TapeLimits.utf8Size(line.comment) + gap
                is TapeLine.Heading -> TapeLimits.utf8Size(line.raw) + 1L
                is TapeLine.Comment -> TapeLimits.utf8Size(line.raw) + 1L
                TapeLine.Separator -> TapeLimits.utf8Size(CalcFile.SEPARATOR) + 1L
                TapeLine.Blank -> 1L
            }
            if (amountSize > 0L) {
                numericCount++
                numericWidth = maxOf(numericWidth, amountSize)
            }
        }
        var estimate = 64L + numericCount * (numericWidth + gap + 4L)
        rawSizes.forEach { estimate += it; TapeLimits.ensureRenderedBudget(estimate) }
        TapeLimits.ensureRenderedBudget(estimate)
        return estimate
    }

    fun formatNum(
        v: BigDecimal,
        decimals: Int,
        pct: Boolean,
        grouping: Grouping,
        decimalSeparator: Char = '.',
        thousandsSeparator: Char = ',',
    ): String {
        val safeDecimal = if (decimalSeparator.isISOControl()) '.' else decimalSeparator
        val safeThousands = if (thousandsSeparator.isISOControl() || thousandsSeparator == safeDecimal) {
            ','
        } else {
            thousandsSeparator
        }
        val text = formatAmount(v, decimals, pct, false).replace('.', safeDecimal)
        return if (grouping == Grouping.OFF || text.contains('E') || text.contains('e')) {
            text
        } else {
            groupNumber(text, grouping, safeDecimal, safeThousands)
        }
    }

    fun formatEntryNum(
        v: BigDecimal,
        decimals: Int,
        pct: Boolean,
        grouping: Grouping,
        decimalSeparator: Char = '.',
        thousandsSeparator: Char = ',',
    ): String {
        val safeDecimal = if (decimalSeparator.isISOControl()) '.' else decimalSeparator
        val safeThousands = if (thousandsSeparator.isISOControl() || thousandsSeparator == safeDecimal) {
            ','
        } else {
            thousandsSeparator
        }
        val text = formatAmount(v, decimals, pct, true).replace('.', safeDecimal)
        return if (grouping == Grouping.OFF || text.contains('E') || text.contains('e')) {
            text
        } else {
            groupNumber(text, grouping, safeDecimal, safeThousands)
        }
    }

    fun formatAmount(v: BigDecimal, decimals: Int, pct: Boolean, preserveScale: Boolean): String {
        TapeLimits.requireSupportedNumber(v)
        val safeDecimals = TapeLimits.safeDecimals(decimals)
        val normalized = if (v.signum() == 0) BigDecimal.ZERO else v
        val targetScale = if (preserveScale) {
            maxOf(safeDecimals, normalized.scale().coerceAtMost(TapeLimits.MAX_SCALE))
        } else safeDecimals
        val rounded = normalized.setScale(targetScale, RoundingMode.HALF_UP)
        val text = if (TapeLimits.wouldExceedRenderedWidth(rounded, targetScale)) {
            rounded.round(TapeEvaluator.MC).toString()
        } else {
            rounded.toPlainString()
        }
        return if (pct) "$text%" else text
    }

    fun displayParts(op: Char, amount: BigDecimal): Pair<Char, BigDecimal> {
        if (amount.signum() == 0) {
            return (if (op == '+' || op == '-') '+' else op) to BigDecimal.ZERO
        }
        if ((op == '+' || op == '-') && amount.signum() < 0) {
            return (if (op == '+') '-' else '+') to amount.negate()
        }
        return op to amount
    }

    fun patchBalances(
        rawText: String,
        doc: TapeDoc,
        eval: EvalResult,
        decimals: Int,
        indent: Int,
        grouping: Grouping,
        decimalSeparator: Char = '.',
        thousandsSeparator: Char = ',',
    ): String? {
        val lineBreak = when {
            rawText.contains("\r\n") -> "\r\n"
            rawText.contains('\n') -> "\n"
            rawText.contains('\r') -> "\r"
            else -> "\n"
        }
        val raws = rawText.split(Regex("\\r\\n|\\n|\\r"))
        if (raws.size != doc.lines.size) return null
        TapeLimits.ensureRenderedBudget(
            estimatedPrettySize(doc, eval, decimals, indent, decimalSeparator, thousandsSeparator),
        )
        val gap = " ".repeat(indent.coerceIn(1, 8))
        val width = doc.lines.mapIndexedNotNull { i, line ->
            when (line) {
                is TapeLine.Entry -> {
                    val (_, abs) = displayParts(line.op, line.amount)
                    formatEntryNum(
                        abs,
                        decimals,
                        line.isPercent,
                        grouping,
                        decimalSeparator,
                        thousandsSeparator,
                    ).length
                }
                is TapeLine.Balance -> {
                    val (_, abs) = displayParts('+', eval.balanceTotals[i] ?: line.value)
                    formatNum(
                        abs,
                        decimals,
                        false,
                        grouping,
                        decimalSeparator,
                        thousandsSeparator,
                    ).length
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
                val n = formatNum(
                    abs,
                    decimals,
                    false,
                    grouping,
                    decimalSeparator,
                    thousandsSeparator,
                ).padEnd(width)
                val fresh = if (line.comment.isNotEmpty()) "$op $n$gap${line.comment}" else "$op $n"
                if (fresh != raw) changed = true
                fresh
            } else raw
        }
        val result = if (changed) out.joinToString(lineBreak) else null
        if (result != null) TapeLimits.ensureRenderedTextBudget(result)
        return result
    }

    data class LineMark(val bold: Boolean, val negative: Boolean)

    fun markLines(doc: TapeDoc, eval: EvalResult): List<LineMark> {
        return doc.lines.mapIndexed { i, line ->
            when (line) {
                is TapeLine.Entry -> {
                    val neg = if (line.op == '*' || line.op == '/' || line.op == '^') {
                        line.amount.signum() < 0
                    } else {
                        displayParts(line.op, line.amount).first == '-'
                    }
                    LineMark(false, neg)
                }
                is TapeLine.Balance -> {
                    val v = eval.balanceTotals[i] ?: line.value
                    LineMark(true, v.signum() < 0)
                }
                else -> LineMark(false, false)
            }
        }
    }

    fun groupNumber(
        s: String,
        grouping: Grouping,
        decimalSeparator: Char = '.',
        thousandsSeparator: Char = ',',
    ): String {
        if (grouping == Grouping.OFF || s.contains('E') || s.contains('e')) return s
        if (decimalSeparator == thousandsSeparator) return s
        if (decimalSeparator.isISOControl() || thousandsSeparator.isISOControl()) return s
        val pct = s.endsWith("%")
        val core = if (pct) s.dropLast(1) else s
        val decimalIndex = core.indexOf(decimalSeparator)
        val intPart = if (decimalIndex < 0) core else core.substring(0, decimalIndex)
        val fracPart = if (decimalIndex < 0) "" else core.substring(decimalIndex)
        val grouped = when (grouping) {
            Grouping.OFF -> intPart
            Grouping.COMMA -> intPart.replace(Regex("(\\d)(?=(\\d{3})+$)")) { match ->
                match.groupValues[1] + thousandsSeparator
            }
            Grouping.INDIAN -> indian(intPart, thousandsSeparator)
        }
        return grouped + fracPart + if (pct) "%" else ""
    }

    private fun indian(intPart: String, separator: Char): String {
        val negative = intPart.startsWith("-")
        val digits = if (negative) intPart.drop(1) else intPart
        if (digits.length <= 3) return intPart
        val tail = digits.takeLast(3)
        val head = digits.dropLast(3).reversed().chunked(2).joinToString(separator.toString()).reversed()
        return (if (negative) "-" else "") + "$head$separator$tail"
    }
}
