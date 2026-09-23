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
