package com.npnpatidar.ncal.tape

import java.math.BigDecimal

/**
 * Tape model for the CalcTape-style notepad calculator.
 *
 * File format reference: CalcTape `.calc` plain text, e.g.
 * ```
 *  +         45.00000 hdfc     -> Entry(+, 45, comment="hdfc")
 *  ------------------          -> Separator
 *  +        456.00000          -> Balance(456) display-only restatement
 * ```
 * A [Balance] line is an `+X` line directly after a [Separator]: it restates the
 * running total and must NOT be added again (verified against hisab.calc:
 * 9/9 balance lines match the chained running total).
 */
sealed interface TapeLine {
    /** ` <op> <amount 17-wide> <comment>`, e.g. `- 155.00000 mohan anytime`. */
    data class Entry(
        val op: Char,
        val amount: BigDecimal,
        val isPercent: Boolean,
        val comment: String,
    ) : TapeLine

    /** ` ------------------ ` (18 dashes). Purely visual block boundary. */
    data object Separator : TapeLine

    /** Post-separator restatement (`+ <total>` or `- <total>` plus optional
     * comment). Always display-only: the evaluator snaps it to the recomputed
     * running total and never adds it, so stale mid-edit values heal. The
     * [comment] is user content and is preserved verbatim through
     * pretty-print, save and export. */
    data class Balance(val value: BigDecimal, val comment: String = "") : TapeLine

    /** Empty line: starts an independent calculation (running total resets). */
    data object Blank : TapeLine

    /** `# heading` line. Ignored for math. */
    data class Heading(val text: String, val raw: String) : TapeLine

    /** Any other text. Ignored for math, preserved verbatim on export. */
    data class Comment(val raw: String) : TapeLine
}

/** Header metadata of a `.calc` file. Unknown keys are ignored on import. */
data class CalcMeta(
    val decimals: Int = 5,
    val decSep: Char = '.',
    val thouSep: Char = ',',
    val uuid: String = java.util.UUID.randomUUID().toString(),
    val caretLine: Int = 0,
    val caretOffset: Int = 0,
)

data class TapeDoc(
    val meta: CalcMeta,
    val lines: List<TapeLine>,
    /** Non-fatal import notes, e.g. unparseable lines kept as comments. */
    val warnings: List<String> = emptyList(),
)

object TapeLimits {
    const val MAX_DECIMALS = 8
    const val MAX_INPUT_CHARS = 1_000_000
    const val MAX_LINES = 20_000
    const val MAX_LINE_CHARS = 16_384
    const val MAX_TOKEN_CHARS = 256
    const val MAX_NUMERIC_PRECISION = 256
    const val MAX_SCALE = 10_000
    const val MAX_ADJUSTED_EXPONENT = 10_000
    const val MAX_RENDERED_CHARS = 32_768
    const val MAX_RENDERED_BYTES = 8 * 1024 * 1024

    fun isSupportedNumber(value: BigDecimal): Boolean {
        val scale = value.scale().toLong()
        val precision = value.precision().toLong()
        if (scale < -MAX_SCALE || scale > MAX_SCALE) return false
        if (precision > MAX_NUMERIC_PRECISION) return false
        val adjusted = precision - scale - 1L
        return adjusted >= -MAX_ADJUSTED_EXPONENT && adjusted <= MAX_ADJUSTED_EXPONENT
    }

    fun safeDecimals(value: Int): Int = value.coerceIn(0, MAX_DECIMALS)

    fun wouldExceedRenderedWidth(value: BigDecimal, scale: Int): Boolean {
        val adjusted = value.precision().toLong() - value.scale().toLong() - 1L
        val estimated = maxOf(0L, adjusted) + scale.toLong() + 2L
        return estimated > MAX_RENDERED_CHARS
    }

    fun requireSupportedNumber(value: BigDecimal) {
        if (!isSupportedNumber(value)) {
            throw IllegalArgumentException("number exceeds supported limits")
        }
    }

    fun estimatedAmountChars(
        value: BigDecimal,
        decimals: Int,
        pct: Boolean = false,
        preserveScale: Boolean = false,
        decimalSeparator: Char = '.',
        thousandsSeparator: Char = ',',
    ): Long {
        requireSupportedNumber(value)
        val safeDecimals = safeDecimals(decimals)
        val normalized = if (value.signum() == 0) BigDecimal.ZERO else value
        val targetScale = if (preserveScale) {
            maxOf(safeDecimals, normalized.scale().coerceAtMost(MAX_SCALE))
        } else safeDecimals
        val adjusted = normalized.precision().toLong() - normalized.scale().toLong() - 1L
        val plain = if (wouldExceedRenderedWidth(normalized, targetScale)) {
            96L
        } else {
            val integerDigits = if (adjusted >= 0) adjusted + 1L else 1L
            val leadingZeros = if (adjusted < 0) -adjusted - 1L else 0L
            integerDigits + leadingZeros + targetScale.toLong() + 3L
        }
        val groupCount = if (adjusted >= 0) (adjusted + 1L) / 3L else 0L
        val separatorBytes = maxOf(
            decimalSeparator.toString().toByteArray(Charsets.UTF_8).size,
            thousandsSeparator.toString().toByteArray(Charsets.UTF_8).size,
        )
        val separatorExtra = (separatorBytes - 1).coerceAtLeast(0) * (groupCount + 1L)
        val base = plain + if (pct) 1L else 0L
        return base + base / 3L + 32L + separatorExtra
    }

    fun utf8Size(value: String): Long = value.toByteArray(Charsets.UTF_8).size.toLong()

    fun ensureRenderedTextBudget(value: String) {
        ensureRenderedBudget(utf8Size(value))
    }

    fun ensureRenderedBudget(estimated: Long) {
        if (estimated > MAX_RENDERED_BYTES) {
            throw IllegalArgumentException("rendered output exceeds the supported size")
        }
    }
}
