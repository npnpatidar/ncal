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
    /**
     * Every independent section's total in order (blank-separated; the last
     * entry is the currently open section). Sums to [grandTotal]. The strip
     * shows the section under the cursor, like the reference tape.
     */
    val sectionTotals: List<BigDecimal>,
    val errors: List<String>,
)

/**
 * Chain evaluator.
 *
 * - Entries accumulate into a running total; `* / ^` bind tighter than `+ -`
 *   across lines (`+10, +2, *3` = 16).
 * - `%` resolves against the running subtotal (base + current block so far).
 * - [TapeLine.Balance] lines are computed restatements: always display-only
 *   and snapped to the running total, never added (stale ones heal instead
 *   of inflating).
 * - [TapeLine.Blank] ends the section: the section total feeds the grand total and
 *   the running total resets (independent calculation, like CalcTape).
 * - Internally full BigDecimal precision (34-digit context); rounding to
 *   [decimals] with HALF_UP happens only for display.
 */
object TapeEvaluator {

    val MC = MathContext(34, RoundingMode.HALF_UP)

    /** Exponents beyond this are rejected: exact BigDecimal powers of huge
     * exponents (e.g. `^ 99999999`) would hang or OOM the app from one line. */
    private const val MAX_EXP = 1000

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
                    // A `+X`/`-X` directly after a separator is a computed
                    // restatement: ALWAYS display-only, never added — even
                    // when stale (mid-edit). The shown value snaps to the
                    // running total (balanceTotals) and patchBalances/pretty
                    // rewrite the text, so editing an entry glides the total
                    // instead of exploding it by re-adding old subtotals.
                    // (Fresh input always arrives on its own Entry line:
                    // the keypad never types onto the total row.)
                    balanceTotals[index] = running
                    results.add(LineResult(index, running, null))
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
        return EvalResult(results, subtotals, grand, running, balanceTotals, sectionTotals + running, errors)
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
     * Which blank-separated section contains [lineIndex] (only blanks strictly
     * before it count; a cursor sitting exactly on a divider belongs to the
     * section above it).
     */
    fun sectionIndexForLine(lines: List<TapeLine>, lineIndex: Int): Int {
        if (lines.isEmpty()) return 0
        val idx = lineIndex.coerceIn(0, lines.size - 1)
        return lines.take(idx).count { it is TapeLine.Blank }
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
                if (e.op == '^') {
                    val t = cappedPow(base, factor, tag, errs)
                    if (t == null) {
                        lineValues.add(Triple(index, null, errs.last()))
                    } else {
                        val delta = t.subtract(base, MC)
                        lineValues.add(Triple(index, delta, null))
                        cur = delta
                    }
                    curSet = true
                    continue
                }
                val target = if (e.op == '*') base.multiply(factor, MC) else base.divide(factor, MC)
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
                    // A `%` behind `*`/`/`/`^` is a pure fraction of 1, NOT
                    // resolved against the running subtotal: `* 19%` means
                    // ×0.19 (so `+ 100 * 19%` is 19, not 1900) and `^ 50%`
                    // means ^0.5 (square root). This matches the block-leading
                    // path, which always used the pure fraction.
                    if (e.isPercent) {
                        val factor = e.amount.divide(BigDecimal(100), MC)
                        if (e.op == '/' && factor.compareTo(BigDecimal.ZERO) == 0) {
                            val msg = "$tag: division by zero"
                            errs.add(msg)
                            lineValues.add(Triple(index, null, msg))
                            continue
                        }
                        if (e.op == '^') {
                            val t = cappedPow(cur, factor, tag, errs)
                            if (t == null) {
                                lineValues.add(Triple(index, null, errs.last()))
                            } else {
                                cur = t
                                lineValues.add(Triple(index, cur, null))
                            }
                            continue
                        }
                        cur = if (e.op == '*') cur.multiply(factor, MC) else cur.divide(factor, MC)
                        lineValues.add(Triple(index, cur, null))
                        continue
                    }
                    val rhs = e.amount
                    lineValues.add(Triple(index, rhs, null))
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
                        else -> {
                            val t = cappedPow(cur, rhs, tag, errs)
                            if (t == null) {
                                lineValues[lineValues.lastIndex] = Triple(index, null, errs.last())
                            }
                            t ?: cur
                        }
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

    /**
     * Capped, crash-safe power for `^` lines. Rejects |exponent| > MAX_EXP
     * (exact giant powers would hang or OOM the app from one typed line) and
     * converts non-finite outcomes (`0^-1`, negative fractional powers like
     * `(-8)^0.333`) into recorded errors instead of throwing out of
     * evaluation. Returns null on failure (error already recorded).
     */
    private fun cappedPow(
        base: BigDecimal,
        exp: BigDecimal,
        tag: String,
        errs: MutableList<String>,
    ): BigDecimal? {
        if (exp.abs().compareTo(BigDecimal(MAX_EXP)) > 0) {
            errs.add("$tag: exponent too large (max $MAX_EXP)")
            return null
        }
        return try {
            val ei = exp.intValueExact()
            if (ei >= 0) base.pow(ei, MC) else doublePow(base, ei.toDouble(), tag, errs)
        } catch (_: ArithmeticException) {
            doublePow(base, exp.toDouble(), tag, errs)
        } catch (t: Throwable) {
            errs.add("$tag: invalid power (${t.message})")
            null
        }
    }

    private fun doublePow(
        base: BigDecimal,
        exp: Double,
        tag: String,
        errs: MutableList<String>,
    ): BigDecimal? {
        return try {
            BigDecimal(base.toDouble().pow(exp), MC)
        } catch (t: Throwable) {
            errs.add("$tag: invalid power (${t.message})")
            null
        }
    }
}
