package com.npnpatidar.ncal.tape

import java.math.RoundingMode

/**
 * `.calc` plain-text reader/writer, byte-compatible with CalcTape exports.
 *
 * Locked against `hisab.calc` (54 lines):
 * - Entry:     `" " + op + amount.padStart(17) + " " + comment`
 * - Separator: `" ------------------ "` (1 space + 18 dashes + 1 space)
 * - Balance:   same as entry with empty comment, e.g. `" +        456.00000 "`
 * - Header keys in fixed order; body ends with two blank lines + trailing LF.
 * - File may start with a BOM (stripped on import, never written).
 * - Writer omits thousands grouping (always parseable); parser accepts it.
 */
object CalcFile {

    const val SEPARATOR = " ------------------ "
    private const val AMOUNT_WIDTH = 17
    private const val HEADER_OPEN = "<SFRCalculatorHeader>"
    private const val HEADER_CLOSE = "</SFRCalculatorHeader>"

    private val entryRe = Regex("""^\s*([+\-*/^])\s*(.*)$""")
    private val numberHeadRe = Regex("""^([0-9][0-9.,]*)(%?)\s?(.*)$""")
    private val separatorRe = Regex("""^ -{2,} ?$""")
    private val headingRe = Regex("""^(#+)\s?(.*)$""")

    fun parse(text: String): TapeDoc {
        val clean = text.removePrefix("\uFEFF")
        val all = clean.split("\n").map { it.removeSuffix("\r") }
        var meta = CalcMeta()
        val warnMsgs = mutableListOf<String>()
        var body = all
        val openIdx = all.indexOfFirst { it.trim() == HEADER_OPEN }
        val closeIdx = all.indexOfFirst { it.trim() == HEADER_CLOSE }
        if (openIdx >= 0 && closeIdx > openIdx) {
            val map = mutableMapOf<String, String>()
            for (i in openIdx + 1 until closeIdx) {
                val eq = all[i].indexOf('=')
                if (eq > 0) map[all[i].substring(0, eq).trim()] = all[i].substring(eq + 1)
            }
            meta = CalcMeta(
                decimals = map["DECIMALS"]?.toIntOrNull() ?: 5,
                decSep = map["DECSEP"]?.firstOrNull() ?: '.',
                thouSep = map["THOUSEP"]?.firstOrNull() ?: ',',
                uuid = map["UUID"]?.ifBlank { null } ?: java.util.UUID.randomUUID().toString(),
                caretLine = map["CARETLINE"]?.toIntOrNull() ?: 0,
                caretOffset = map["CARETLINEOFFSET"]?.toIntOrNull() ?: 0,
            )
            body = all.drop(closeIdx + 1)
        }

        val lines = mutableListOf<TapeLine>()
        body.forEachIndexed { idx, raw ->
            val ln = raw
            when {
                ln.isEmpty() -> lines.add(TapeLine.Blank)
                separatorRe.matches(ln.trimEnd()) && ln.trim().startsWith("-") ->
                    lines.add(TapeLine.Separator)
                ln.trim().startsWith("#") -> {
                    val m = headingRe.matchEntire(ln.trim())!!
                    lines.add(TapeLine.Heading(m.groupValues[2], ln))
                }
                else -> {
                    val em = entryRe.matchEntire(ln)
                    val nm = em?.let { numberHeadRe.matchEntire(it.groupValues[2]) }
                    if (em != null && nm != null) {
                        val op = em.groupValues[1][0]
                        val digits = normalizeNumber(nm.groupValues[1], meta)
                        val amount = digits.toBigDecimalOrNull()
                        if (amount != null) {
                            lines.add(
                                TapeLine.Entry(
                                    op = op,
                                    amount = amount,
                                    isPercent = nm.groupValues[2] == "%",
                                    comment = nm.groupValues[3].trim(),
                                ),
                            )
                        } else {
                            warnMsgs.add("line ${idx + 1}: bad number, kept as comment")
                            lines.add(TapeLine.Comment(ln))
                        }
                    } else {
                        lines.add(TapeLine.Comment(ln))
                    }
                }
            }
        }

        // Post-pass: `+X` directly after a separator is a balance restatement.
        val fixed = lines.mapIndexed { i, l ->
            if (l is TapeLine.Entry && l.op == '+' && !l.isPercent &&
                i > 0 && lines[i - 1] is TapeLine.Separator
            ) {
                TapeLine.Balance(l.amount)
            } else l
        }
        return TapeDoc(meta, fixed, warnMsgs)
    }

    /**
     * Canonical writer. [balances] supplies the evaluated running total for each
     * [TapeLine.Balance] in order (see [TapeEvaluator.subtotals]); entries and
     * comments round-trip verbatim.
     */
    fun write(doc: TapeDoc, balances: List<java.math.BigDecimal>): String {
        val m = doc.meta
        val header = listOf(
            HEADER_OPEN,
            "CARETLINE=${m.caretLine}",
            "CARETLINEOFFSET=${m.caretOffset}",
            "CFGVER=1",
            "DECIMALS=${m.decimals}",
            "DECSEP=${m.decSep}",
            "EXTSYN=0",
            "THOUSEP=${m.thouSep}",
            "TXTMODE=0",
            "TXTSTYLE=0",
            "UUID=${m.uuid}",
            "VARINFO=",
            HEADER_CLOSE,
        )
        val body = mutableListOf<String>()
        var bi = 0
        for (line in doc.lines) {
            when (line) {
                is TapeLine.Entry -> body.add(formatEntry(line.op, line.amount, line.isPercent, line.comment, m))
                is TapeLine.Separator -> body.add(SEPARATOR)
                is TapeLine.Balance -> {
                    val v = balances.getOrNull(bi++) ?: line.value
                    body.add(formatEntry('+', v, false, "", m))
                }
                is TapeLine.Blank -> body.add("")
                is TapeLine.Heading -> body.add(line.raw)
                is TapeLine.Comment -> body.add(line.raw)
            }
        }
        body.add("")
        body.add("")
        return (header + body).joinToString("\n") + "\n"
    }

    /** ` +         45.00000 hdfc` — 1 space, op, 17-wide amount, space, comment. */
    fun formatEntry(op: Char, amount: java.math.BigDecimal, isPercent: Boolean, comment: String, meta: CalcMeta): String {
        var digits = amount.setScale(meta.decimals, RoundingMode.HALF_UP).toPlainString()
        if (meta.decSep != '.') digits = digits.replace('.', meta.decSep)
        if (isPercent) digits += "%"
        return " " + op + digits.padStart(AMOUNT_WIDTH) + " " + comment.trim()
    }

    private fun normalizeNumber(raw: String, meta: CalcMeta): String {
        var s = raw
        if (meta.thouSep != '.') s = s.replace(meta.thouSep.toString(), "")
        else s = s.replace(",", "")
        if (meta.decSep != '.') s = s.replace(meta.decSep, '.')
        return s
    }
}
