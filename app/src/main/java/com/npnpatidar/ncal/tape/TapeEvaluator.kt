package com.npnpatidar.ncal.tape

import java.math.BigDecimal
import java.math.BigInteger
import java.math.MathContext
import java.math.RoundingMode

 data class LineResult(val index: Int, val value: BigDecimal?, val error: String?)

data class EvalResult(
    val lineResults: List<LineResult>,
    val subtotals: List<BigDecimal>,
    val grandTotal: BigDecimal,
    val openTotal: BigDecimal,
    val balanceTotals: Map<Int, BigDecimal>,
    val sectionTotals: List<BigDecimal>,
    val errors: List<String>,
)

object TapeEvaluator {

    val MC = MathContext(34, RoundingMode.HALF_UP)
    private const val MAX_EXP = 1000
    private const val MAX_ROOT_DEGREE = 32

    @Suppress("UNUSED_PARAMETER")
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
            if (!TapeLimits.isSupportedNumber(delta)) {
                errors.add("calculation total exceeds supported limits")
                running = BigDecimal.ZERO
            } else {
                val next = running.add(delta, MC)
                if (TapeLimits.isSupportedNumber(next)) {
                    running = next
                } else {
                    errors.add("running total exceeds supported limits")
                    running = BigDecimal.ZERO
                }
            }
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
        var grand = running
        for (section in sectionTotals) {
            val next = grand.add(section, MC)
            if (TapeLimits.isSupportedNumber(next)) {
                grand = next
            } else {
                errors.add("grand total exceeds supported limits")
                grand = BigDecimal.ZERO
                break
            }
        }
        return EvalResult(
            results.sortedBy { it.index },
            subtotals,
            grand,
            running,
            balanceTotals,
            sectionTotals + running,
            errors,
        )
    }

    private data class IndexedEntry(val index: Int, val entry: TapeLine.Entry)

    fun hasOpenEntries(lines: List<TapeLine>): Boolean {
        val cut = lines.indexOfLast { it is TapeLine.Separator || it is TapeLine.Balance }
        return lines.drop(cut + 1).any { it is TapeLine.Entry }
    }

    fun sectionIndexForLine(lines: List<TapeLine>, lineIndex: Int): Int {
        if (lines.isEmpty()) return 0
        val idx = lineIndex.coerceIn(0, lines.size - 1)
        return lines.take(idx).count { it is TapeLine.Blank }
    }

    private fun evalBlock(
        block: List<IndexedEntry>,
        base: BigDecimal,
    ): Triple<BigDecimal, List<Triple<Int, BigDecimal?, String?>>, List<String>> {
        val lineValues = mutableListOf<Triple<Int, BigDecimal?, String?>>()
        val errs = mutableListOf<String>()
        var sum = BigDecimal.ZERO
        var cur = BigDecimal.ZERO
        var curSet = false
        var chainIncludesBase = false

        fun chainValue(): BigDecimal = cur.subtract(base, MC)
        fun lineChainValue(): BigDecimal = if (chainIncludesBase) chainValue() else cur

        for (ie in block) {
            val index = ie.index
            val e = ie.entry
            val tag = "line ${index + 1}"
            if (!TapeLimits.isSupportedNumber(e.amount)) {
                val msg = "$tag: number exceeds supported limits"
                errs.add(msg)
                lineValues.add(Triple(index, null, msg))
                cur = base
                chainIncludesBase = true
                curSet = true
                continue
            }
            if (!curSet && sum.compareTo(BigDecimal.ZERO) == 0 &&
                (e.op == '*' || e.op == '/' || e.op == '^')
            ) {
                val factor = if (e.isPercent) e.amount.divide(BigDecimal(100), MC) else e.amount
                if (e.op == '/' && factor.compareTo(BigDecimal.ZERO) == 0) {
                    val msg = "$tag: division by zero"
                    errs.add(msg)
                    lineValues.add(Triple(index, null, msg))
                    cur = base
                    chainIncludesBase = true
                    curSet = true
                    continue
                }
                if (e.op == '^') {
                    val target = cappedPow(base, factor, tag, errs)
                    if (target == null) {
                        cur = base
                        chainIncludesBase = true
                        lineValues.add(Triple(index, null, errs.lastOrNull() ?: "$tag: invalid power"))
                    } else if (TapeLimits.isSupportedNumber(target)) {
                        cur = target
                        chainIncludesBase = true
                        lineValues.add(Triple(index, lineChainValue(), null))
                    } else {
                        val msg = "$tag: number exceeds supported limits"
                        errs.add(msg)
                        cur = base
                        chainIncludesBase = true
                        lineValues.add(Triple(index, null, msg))
                    }
                    curSet = true
                    continue
                }
                val target = try {
                    if (e.op == '*') base.multiply(factor, MC) else base.divide(factor, MC)
                } catch (_: ArithmeticException) {
                    val msg = "$tag: invalid operation"
                    errs.add(msg)
                    cur = base
                    chainIncludesBase = true
                    lineValues.add(Triple(index, null, msg))
                    curSet = true
                    continue
                }
                if (!TapeLimits.isSupportedNumber(target)) {
                    val msg = "$tag: number exceeds supported limits"
                    errs.add(msg)
                    cur = base
                    chainIncludesBase = true
                    lineValues.add(Triple(index, null, msg))
                } else {
                    cur = target
                    chainIncludesBase = true
                    lineValues.add(Triple(index, lineChainValue(), null))
                }
                curSet = true
                continue
            }

            when (e.op) {
                '+', '-' -> {
                    sum = sum.add(cur, MC)
                    if (!TapeLimits.isSupportedNumber(sum)) {
                        val msg = "$tag: subtotal exceeds supported limits"
                        errs.add(msg)
                        lineValues.add(Triple(index, null, msg))
                        cur = BigDecimal.ZERO
                    } else {
                        chainIncludesBase = false
                        cur = if (e.isPercent) {
                            val percentBase = base.add(sum, MC)
                            if (!TapeLimits.isSupportedNumber(percentBase)) {
                                val msg = "$tag: subtotal exceeds supported limits"
                                errs.add(msg)
                                lineValues.add(Triple(index, null, msg))
                                BigDecimal.ZERO
                            } else {
                                val resolved = percentOf(percentBase, e.amount)
                                if (TapeLimits.isSupportedNumber(resolved)) {
                                    lineValues.add(Triple(index, signed(e.op, resolved), null))
                                    signed(e.op, resolved)
                                } else {
                                    val msg = "$tag: result exceeds supported limits"
                                    errs.add(msg)
                                    lineValues.add(Triple(index, null, msg))
                                    BigDecimal.ZERO
                                }
                            }
                        } else {
                            lineValues.add(Triple(index, signed(e.op, e.amount), null))
                            signed(e.op, e.amount)
                        }
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
                    val factor = if (e.isPercent) e.amount.divide(BigDecimal(100), MC) else e.amount
                    if (e.op == '/' && factor.compareTo(BigDecimal.ZERO) == 0) {
                        val msg = "$tag: division by zero"
                        errs.add(msg)
                        lineValues.add(Triple(index, null, msg))
                        continue
                    }
                    if (e.op == '^') {
                        val target = cappedPow(cur, factor, tag, errs)
                        if (target == null || !TapeLimits.isSupportedNumber(target)) {
                            val msg = if (target == null) errs.lastOrNull() ?: "$tag: invalid power"
                            else "$tag: number exceeds supported limits"
                            if (target != null) errs.add(msg)
                            lineValues.add(Triple(index, null, msg))
                        } else {
                            cur = target
                            lineValues.add(Triple(index, lineChainValue(), null))
                        }
                        continue
                    }
                    val target = try {
                        when (e.op) {
                            '*' -> cur.multiply(factor, MC)
                            else -> cur.divide(factor, MC)
                        }
                    } catch (_: ArithmeticException) {
                        errs.add("$tag: invalid operation")
                        null
                    }
                    if (target == null) {
                        lineValues.add(Triple(index, null, "$tag: invalid operation"))
                    } else if (!TapeLimits.isSupportedNumber(target)) {
                        val msg = "$tag: number exceeds supported limits"
                        errs.add(msg)
                        lineValues.add(Triple(index, null, msg))
                    } else {
                        cur = target
                        lineValues.add(Triple(index, lineChainValue(), null))
                    }
                }
                else -> {
                    val msg = "$tag: unknown operator '${e.op}'"
                    errs.add(msg)
                    lineValues.add(Triple(index, null, msg))
                }
            }
        }
        val chainDelta = try {
            if (chainIncludesBase) chainValue() else cur
        } catch (_: ArithmeticException) {
            errs.add("calculation total exceeds supported limits")
            return Triple(BigDecimal.ZERO, lineValues, errs)
        }
        if (!TapeLimits.isSupportedNumber(sum) || !TapeLimits.isSupportedNumber(chainDelta)) {
            errs.add("calculation total exceeds supported limits")
            return Triple(BigDecimal.ZERO, lineValues, errs)
        }
        val total = try {
            sum.add(chainDelta, MC)
        } catch (_: ArithmeticException) {
            errs.add("calculation total exceeds supported limits")
            BigDecimal.ZERO
        }
        if (!TapeLimits.isSupportedNumber(total)) {
            errs.add("calculation total exceeds supported limits")
            return Triple(BigDecimal.ZERO, lineValues, errs)
        }
        return Triple(total, lineValues, errs)
    }

    private fun signed(op: Char, v: BigDecimal): BigDecimal =
        if (op == '-') v.negate() else v

    private fun percentOf(base: BigDecimal, pct: BigDecimal): BigDecimal =
        base.multiply(pct, MC).divide(BigDecimal(100), MC)

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
        val integer = try {
            exp.intValueExact()
        } catch (_: ArithmeticException) {
            null
        }
        if (integer != null) {
            return try {
                if (integer >= 0) {
                    base.pow(integer, MC)
                } else {
                    if (base.compareTo(BigDecimal.ZERO) == 0) {
                        errs.add("$tag: invalid power (zero raised to a negative exponent)")
                        null
                    } else {
                        BigDecimal.ONE.divide(base.pow(-integer, MC), MC)
                    }
                }
            } catch (t: Throwable) {
                errs.add("$tag: invalid power (${t.message})")
                null
            }
        }
        return decimalPow(base, exp, tag, errs)
    }

    private fun decimalPow(
        base: BigDecimal,
        exp: BigDecimal,
        tag: String,
        errs: MutableList<String>,
    ): BigDecimal? {
        val fraction = rational(exp)
        if (fraction == null) {
            errs.add("$tag: invalid power (unsupported fractional exponent)")
            return null
        }
        var numerator = fraction.first
        val denominator = fraction.second
        if (numerator.signum() == 0) return BigDecimal.ONE
        if (denominator > BigInteger.valueOf(MAX_ROOT_DEGREE.toLong()) ||
            numerator.abs() > BigInteger.valueOf(MAX_EXP.toLong())
        ) {
            errs.add("$tag: invalid power (unsupported fractional exponent)")
            return null
        }
        val negative = numerator.signum() < 0
        if (negative) numerator = numerator.negate()
        if (base.compareTo(BigDecimal.ZERO) == 0) {
            if (negative) {
                errs.add("$tag: invalid power (zero raised to a negative exponent)")
                return null
            }
            return BigDecimal.ZERO
        }
        val degree = denominator.toInt()
        if (base.signum() < 0 && (degree % 2 == 0 || !numerator.testBit(0))) {
            errs.add("$tag: invalid power (negative base and fractional exponent)")
            return null
        }
        val root = integerRoot(base.abs(), degree)
        if (root == null) {
            errs.add("$tag: invalid power (root did not converge)")
            return null
        }
        var result = try {
            root.pow(numerator.toInt(), MC)
        } catch (t: Throwable) {
            errs.add("$tag: invalid power (${t.message})")
            return null
        }
        if (base.signum() < 0) result = result.negate()
        if (negative) {
            result = try {
                BigDecimal.ONE.divide(result, MC)
            } catch (t: Throwable) {
                errs.add("$tag: invalid power (${t.message})")
                return null
            }
        }
        return if (TapeLimits.isSupportedNumber(result)) result else {
            errs.add("$tag: number exceeds supported limits")
            null
        }
    }

    private fun rational(value: BigDecimal): Pair<BigInteger, BigInteger>? {
        return try {
            val stripped = value.stripTrailingZeros()
            val scale = stripped.scale()
            val unscaled = stripped.unscaledValue()
            val ten = BigDecimal.TEN.toBigIntegerExact()
            val numerator: BigInteger
            val denominator: BigInteger
            if (scale >= 0) {
                numerator = unscaled
                denominator = ten.pow(scale)
            } else {
                numerator = unscaled.multiply(ten.pow(-scale))
                denominator = BigInteger.ONE
            }
            val common = numerator.gcd(denominator)
            if (common == BigInteger.ZERO) null else (numerator / common) to (denominator / common)
        } catch (_: ArithmeticException) {
            null
        }
    }

    private fun integerRoot(value: BigDecimal, degree: Int): BigDecimal? {
        if (degree <= 1) return value
        if (value.signum() == 0) return BigDecimal.ZERO
        if (value.signum() < 0) return null
        val adjusted = value.precision().toLong() - value.scale().toLong() - 1L
        val exponent = Math.floorDiv(adjusted, degree.toLong())
        var guess = BigDecimal.ONE.scaleByPowerOfTen(exponent.toInt())
        if (guess.signum() == 0) guess = BigDecimal.ONE
        val n = BigDecimal(degree.toLong())
        val tolerance = BigDecimal.ONE.movePointLeft(MC.precision - 4)
        repeat(256) {
            val previous = guess
            val denominator = previous.pow(degree - 1, MC)
            val next = if (denominator.signum() == 0) {
                previous
            } else {
                previous.multiply(BigDecimal(degree - 1L), MC)
                    .add(value.divide(denominator, MC), MC)
                    .divide(n, MC)
            }
            guess = next
            val delta = next.subtract(previous).abs()
            val reference = maxOf(previous.abs(), next.abs())
            if (delta <= reference.multiply(tolerance) && rootVerified(next, value, degree)) {
                return next
            }
        }
        return if (rootVerified(guess, value, degree)) guess else null
    }

    private fun rootVerified(root: BigDecimal, value: BigDecimal, degree: Int): Boolean {
        if (root.signum() <= 0) return false
        return try {
            val powered = root.pow(degree, MC)
            val reference = maxOf(powered.abs(), value.abs())
            powered.subtract(value).abs() <= reference.multiply(BigDecimal.ONE.movePointLeft(MC.precision - 4))
        } catch (_: Throwable) {
            false
        }
    }
}
