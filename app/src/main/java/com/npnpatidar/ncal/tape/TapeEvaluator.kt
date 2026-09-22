package com.npnpatidar.ncal.tape

import java.math.BigDecimal
import java.math.MathContext
import java.math.RoundingMode
import kotlin.math.pow

/** Per-line outcome: a line's own contribution, or an error if it can't be computed. */
data class LineResult(val index: Int, val value: BigDecimal?, val error: String?)

data class EvalResult(
    val lineResults: List<LineResult>,
    /** Running total captured at each [TapeLine.Separator], in order. */
    val subtotals: List<BigDecimal>,
    /** Sum of independent sections (blank-line separated); equals running total if no blanks. */
    val grandTotal: BigDecimal,
    /** Running total of the currently open (last) section. */
    val openTotal: BigDecimal,
    /**
     * Running total just BEFORE each [TapeLine.Balance] line (by doc index).
     * Drives live display refresh: the shown subtotal always tracks this.
     */
    val balanceTotals: Map<Int, BigDecimal>,
    val errors: List<String>,
)

/**
 * Chain evaluator.
 *
 * - Entries accumulate into a running total; `* / ^` bind tighter than `+ -`
 *   across lines (`+10, +2, *3` = 16).
 * - `%` resolves against the running subtotal (base + current block so far).
 * - [TapeLine.Balance] lines are display-only restatements (verified, never added).
 * - [TapeLine.Blank] ends the section: the section total feeds the grand total and
 *   the running total resets (independent calculation, like CalcTape).
 * - Internally full BigDecimal precision (34-digit context); rounding to
 *   [decimals] with HALF_UP happens only for display.
 */
object TapeEvaluator {

    val MC = MathContext(34, RoundingMode.HALF_UP)

    fun evaluate(lines: List<TapeLine>, decimals: Int): EvalResult {
        val results = mutableListOf<LineResult>()
        val subtotals = mutableListOf<BigDecimal>()
        val errors = mutableListOf<String>()
        val sectionTotals = mutableListOf<BigDecimal>()
        val balanceTotals = mutableMapOf<Int, BigDecimal>()
        var running = BigDecimal.ZERO
        var block = mutableListOf<IndexedEntry>()

        fun flushBlock() {
            if (block.isEmpty()) return
            val (delta, lineValues, errs) = evalBlock(block, running)
            errs.forEach { errors.add(it) }
            lineValues.forEach { (idx, v, err) -> results.add(LineResult(idx, v, err)) }
            running = running.add(delta, MC)
            block = mutableListOf()
        }

        lines.forEachIndexed { index, line ->
            when (line) {
                is TapeLine.Entry -> block.add(IndexedEntry(index, line))
                is TapeLine.Separator -> {
                    flushBlock()
                    subtotals.add(running)
                    results.add(LineResult(index, running, null))
                }
                is TapeLine.Balance -> {
                    flushBlock()
                    // A post-separator `+X` matching the running total is a
                    // computed restatement (display-only). A mismatching one
                    // is fresh user input typed right after the separator, so
                    // it counts as a normal `+X` entry. Either way the display
                    // snaps to the running total (see balanceTotals).
                    balanceTotals[index] = running
                    if (line.value.setScale(decimals, RoundingMode.HALF_UP)
                            .compareTo(running.setScale(decimals, RoundingMode.HALF_UP)) == 0
                    ) {
                        results.add(LineResult(index, running, null))
                    } else {
                        running = running.add(line.value, MC)
                        results.add(LineResult(index, line.value, null))
                    }
                }
                is TapeLine.Blank -> {
                    flushBlock()
                    sectionTotals.add(running)
                    running = BigDecimal.ZERO
                    results.add(LineResult(index, null, null))
                }
                is TapeLine.Heading, is TapeLine.Comment -> {
                    results.add(LineResult(index, null, null))
                }
            }
        }
        flushBlock()
        val grand = sectionTotals.fold(running) { acc, s -> acc.add(s, MC) }
        return EvalResult(results, subtotals, grand, running, balanceTotals, errors)
    }

    private data class IndexedEntry(val index: Int, val entry: TapeLine.Entry)

    /**
     * True when the tape has entries after the last separator/balance — i.e.
     * there is an open block worth closing with `=`.
     */
    fun hasOpenEntries(lines: List<TapeLine>): Boolean {
        val cut = lines.indexOfLast { it is TapeLine.Separator || it is TapeLine.Balance }
        return lines.drop(cut + 1).any { it is TapeLine.Entry }
    }

    /**
     * Returns (blockDelta, perLineValues, errors).
     * Single pass: [sum] holds finished additive part, [cur] the open
     * multiplicative chain (with its sign).
     *
     * A block opening with `*`, `/` or `^` chains onto the running total
     * (`* 3` triples it, `/ 2` halves it) instead of erroring — on an empty
     * tape the base is 0, so it quietly stays 0.
     */
    private fun evalBlock(
        block: List<IndexedEntry>,
        base: BigDecimal,
    ): Triple<BigDecimal, List<Triple<Int, BigDecimal?, String?>>, List<String>> {
        val lineValues = mutableListOf<Triple<Int, BigDecimal?, String?>>()
        val errs = mutableListOf<String>()
        var sum = BigDecimal.ZERO
        var cur = BigDecimal.ZERO
        var curSet = false

        for ((entryIdx, ie) in block.withIndex()) {
            val index = ie.index
            val e = ie.entry
            val tag = "line ${index + 1}"
            if (entryIdx == 0 && (e.op == '*' || e.op == '/' || e.op == '^')) {
                val factor = if (e.isPercent) e.amount.divide(BigDecimal(100), MC) else e.amount
                if (e.op == '/' && factor.compareTo(BigDecimal.ZERO) == 0) {
                    val msg = "$tag: division by zero"
                    errs.add(msg)
                    lineValues.add(Triple(index, null, msg))
                    curSet = true
                    continue
                }
                val target = when (e.op) {
                    '*' -> base.multiply(factor, MC)
                    '/' -> base.divide(factor, MC)
                    else -> pow(base, factor, tag, errs)
                }
                val delta = target.subtract(base, MC)
                lineValues.add(Triple(index, delta, null))
                cur = delta
                curSet = true
                continue
            }
            when (e.op) {
                '+', '-' -> {
                    sum = sum.add(cur, MC)
                    cur = if (e.isPercent) {
                        val resolved = percentOf(base.add(sum, MC), e.amount)
                        lineValues.add(Triple(index, signed(e.op, resolved), null))
                        signed(e.op, resolved)
                    } else {
                        lineValues.add(Triple(index, signed(e.op, e.amount), null))
                        signed(e.op, e.amount)
                    }
                    curSet = true
                }
                '*', '/', '^' -> {
                    if (!curSet && sum.compareTo(BigDecimal.ZERO) == 0) {
                        val msg = "$tag: block must start with a number or +/-"
                        errs.add(msg)
                        lineValues.add(Triple(index, null, msg))
                        continue
                    }
                    val rhs = if (e.isPercent) {
                        val resolved = percentOf(base.add(sum, MC).add(cur, MC), e.amount)
                        lineValues.add(Triple(index, resolved, null))
                        resolved
                    } else {
                        lineValues.add(Triple(index, e.amount, null))
                        e.amount
                    }
                    cur = when (e.op) {
                        '*' -> cur.multiply(rhs, MC)
                        '/' -> {
                            if (rhs.compareTo(BigDecimal.ZERO) == 0) {
                                val msg = "$tag: division by zero"
                                errs.add(msg)
                                lineValues[lineValues.lastIndex] =
                                    Triple(index, null, msg)
                                cur // keep previous chain value
                            } else cur.divide(rhs, MC)
                        }
                        else -> pow(cur, rhs, tag, errs)
                    }
                }
                else -> {
                    val msg = "$tag: unknown operator '${e.op}'"
                    errs.add(msg)
                    lineValues.add(Triple(index, null, msg))
                }
            }
        }
        return Triple(sum.add(cur, MC), lineValues, errs)
    }

    private fun signed(op: Char, v: BigDecimal): BigDecimal =
        if (op == '-') v.negate() else v

    /** `pct` percent of [base]: base * pct / 100. */
    private fun percentOf(base: BigDecimal, pct: BigDecimal): BigDecimal =
        base.multiply(pct, MC).divide(BigDecimal(100), MC)

    private fun pow(
        base: BigDecimal,
        exp: BigDecimal,
        tag: String,
        errs: MutableList<String>,
    ): BigDecimal {
        return try {
            val ei = exp.intValueExact()
            if (ei >= 0) base.pow(ei, MC) else BigDecimal(base.toDouble().pow(ei.toDouble()), MC)
        } catch (_: ArithmeticException) {
            // Fractional exponent (e.g. ^ 0.5 = sqrt): fall back to double precision.
            BigDecimal(base.toDouble().pow(exp.toDouble()), MC)
        } catch (t: Throwable) {
            errs.add("$tag: invalid power (${t.message})")
            base
        }
    }
}
