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

    private val separatorRe = Regex("""^\s*-{2,}\s?$""")
    private val headingRe = Regex("""^(#+)\s?(.*)$""")
    // \p{Nd} = any Unicode decimal digit (Devanagari, Arabic-Indic, …);
    // values are folded to ASCII in normalizeNumber, comments stay verbatim.
    private const val NUM_CORE = """(?:\.\p{Nd}+|\p{Nd}[\p{Nd}.,]*)(?:[eE][+-]?[0-9]+)?"""
    // Head number (optional unary sign, leading-dot and scientific forms) and
    // inline `op number` splits (matchAt/find: no anchors). Splits also take
    // a sign after the operator, so `2^-3` is 0.125 and `5 * -2` is -10.
    private val headNumRe = Regex("([+-]?\\s*$NUM_CORE)(%?)")
    private val splitRe = Regex("([+\\-*/^])\\s*([+-]?\\s*$NUM_CORE)(%?)")
    private val bareOpRe = Regex("""^\s*[+\-*/^]\s*([+-]\s*)?${'$'}""")
    private val currencyLeadRe = Regex("""^[$€₹£¥¢₩₽₺₫₪\s]+""")
    private val varAssignRe = Regex("""^[A-Za-z_][A-Za-z0-9_]*\s*=.*""")

    /** True for an open operator line with no digits yet (` * `, ` *- `). */
    fun isBareOpLine(raw: String): Boolean = bareOpRe.matches(raw)

    /** True when the text carries a `.calc` header block (explicit metadata). */
    fun hasHeader(text: String): Boolean = text.contains(HEADER_OPEN)

    /**
     * Hint when a comment line looks like an unsupported construct: brackets
     * holding a calculation (`(5+3)`), or a variable definition (`x = 5`).
     * The line is always preserved; this just explains why it doesn't count.
     * Pure prose (`(see receipt)`, `call mom`) stays silent.
     */
    private fun unsupportedHint(ln: String, idx: Int): String? {
        val t = ln.trim()
        if (t.contains('(') && t.contains(')') && t.any { it.isDigit() } &&
            t.any { it == '+' || it == '-' || it == '*' || it == '/' || it == '^' || it == '%' }
        ) {
            return "line ${idx + 1}: brackets aren't calculated yet — kept as a note"
        }
        if (varAssignRe.matches(t)) {
            return "line ${idx + 1}: variables aren't supported yet — kept as a note"
        }
        return null
    }

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
                separatorRe.matches(ln) ->
                    lines.add(TapeLine.Separator)
                // A lone `=` typed in ABC closes the block, like Enter/=.
                ln.trim() == "=" ->
                    lines.add(TapeLine.Separator)
                ln.trim().startsWith("#") -> {
                    val m = headingRe.matchEntire(ln.trim())!!
                    lines.add(TapeLine.Heading(m.groupValues[2], ln))
                }
                else -> {
                    val raws = tokenizeEntryLine(ln.trim())
                    if (raws == null) {
                        // A lone operator being typed (` + `) is silent;
                        // garbage after an operator warns once — unless a more
                        // specific hint applies (brackets/variables below).
                        val t = ln.trim()
                        val leadOp = t.firstOrNull()?.let { o ->
                            o == '+' || o == '-' || o == '*' || o == '/' || o == '^'
                        } == true
                        val hint = unsupportedHint(ln, idx)
                        when {
                            hint != null -> warnMsgs.add(hint)
                            leadOp && t.substring(1).trim().isNotEmpty() ->
                                warnMsgs.add("line ${idx + 1}: bad number, kept as comment")
                        }
                        lines.add(TapeLine.Comment(ln))
                    } else {
                        val tmp = mutableListOf<TapeLine.Entry>()
                        var ok = true
                        for (r in raws) {
                            val amount = normalizeNumber(r.num, meta).toBigDecimalOrNull()
                            if (amount == null) {
                                ok = false
                                break
                            }
                            tmp.add(TapeLine.Entry(r.op, amount, r.pct, r.comment))
                        }
                        if (!ok) {
                            warnMsgs.add("line ${idx + 1}: bad number, kept as comment")
                            lines.add(TapeLine.Comment(ln))
                        } else {
                            lines.addAll(tmp)
                        }
                    }
                }
            }
        }

        // Post-pass: `+X`/`-X` directly after a separator is a balance
        // restatement (kept signed, comment included). The evaluator always
        // treats it as display-only and snaps it to the recomputed running
        // total — stale mid-edit values heal instead of inflating.
        val fixed = lines.mapIndexed { i, l ->
            if (l is TapeLine.Entry && (l.op == '+' || l.op == '-') && !l.isPercent &&
                i > 0 && lines[i - 1] is TapeLine.Separator
            ) {
                val signed = if (l.op == '-') l.amount.negate() else l.amount
                TapeLine.Balance(signed, l.comment)
            } else l
        }
        return TapeDoc(meta, fixed, warnMsgs)
    }

    /**
     * Canonical writer. [balances] supplies the evaluated running total for each
     * [TapeLine.Balance] in order (see [TapeEvaluator.subtotals]); entries and
     * comments round-trip verbatim.
     *
     * Trailing blank lines are normalized to exactly two, so export is
     * idempotent: `parse(write(d))` re-exports byte-identically.
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
        val core = doc.lines.dropLastWhile { it is TapeLine.Blank }
        for (line in core) {
            when (line) {
                is TapeLine.Entry -> body.add(formatEntry(line.op, line.amount, line.isPercent, line.comment, m))
                is TapeLine.Separator -> body.add(SEPARATOR)
                is TapeLine.Balance -> {
                    val v = balances.getOrNull(bi++) ?: line.value
                    body.add(formatEntry('+', v, false, line.comment, m))
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

    /** ` +         45.00000 hdfc` — 1 space, op, 17-wide amount, space, comment.
     * Negative `+X` renders as `- X` (and `-(-X)` as `+ X`) so the operator
     * column only ever carries the sign and columns stay aligned. */
    fun formatEntry(op: Char, amount: java.math.BigDecimal, isPercent: Boolean, comment: String, meta: CalcMeta): String {
        val (dop, abs) = TapeFormatter.displayParts(op, amount)
        var digits = abs.setScale(meta.decimals, RoundingMode.HALF_UP).toPlainString()
        if (meta.decSep != '.') digits = digits.replace('.', meta.decSep)
        if (isPercent) digits += "%"
        return " " + dop + digits.padStart(AMOUNT_WIDTH) + " " + comment.trim()
    }

    private fun normalizeNumber(raw: String, meta: CalcMeta): String {
        // Arabic decimal/grouping separators first (unambiguous).
        val arabic = raw.replace("٬", "").replace("٫", ".")
        val nospace = asciiDigits(arabic).replace(" ", "")
        if (meta.decSep == '.' && meta.thouSep == ',') {
            // Default/US meta, smart comma handling:
            // - "3,50" or German "1.234,56" (comma + trailing digits) use the
            //   comma as the decimal point (fixes 100x silent errors);
            // - anything else treats commas as grouping ("1,000" -> 1000,
            //   "10,00,000" -> 1000000, "1,2,3" -> 123).
            // Known trade-off: ambiguous "1,00" reads as 1.00, not 100.
            if (nospace.matches(Regex("""^\d+,\d{1,2}$""")) ||
                nospace.matches(Regex("""^\d{1,3}(\.\d{3})+,\d+$"""))
            ) {
                return nospace.replace(".", "").replace(",", ".")
            }
            return nospace.replace(",", "")
        }
        if (meta.decSep != '.') {
            // Explicit foreign separators (e.g. German file header): split on
            // the LAST decimal separator so grouping chars never eat the
            // fraction ("1.234,56" -> 1234.56, never 1.23456).
            val cut = nospace.lastIndexOf(meta.decSep)
            if (cut >= 0) {
                val intPart = nospace.substring(0, cut).replace(meta.thouSep.toString(), "")
                return intPart + "." + nospace.substring(cut + 1)
            }
            return nospace.replace(meta.thouSep.toString(), "")
        }
        return nospace.replace(meta.thouSep.toString(), "")
    }

    private data class RawEntry(val op: Char, val num: String, val pct: Boolean, val comment: String)

    /** Unicode decimal-digit block starts (Devanagari, Bengali, Gurmukhi,
     * Gujarati, Oriya, Tamil, Telugu, Kannada, Malayalam, Arabic-Indic,
     * Extended Arabic-Indic, fullwidth) mapped onto ASCII 0-9. */
    private val DIGIT_BASES = intArrayOf(
        0x0966, 0x09E6, 0x0A66, 0x0AE6, 0x0B66, 0x0BE6, 0x0C66, 0x0CE6, 0x0D66,
        0x0660, 0x06F0, 0xFF10,
    )

    private fun asciiDigits(s: String): String {
        var asciiOnly = true
        for (ch in s) {
            if (ch !in '0'..'9' && ch != '.' && ch != ',' && ch != '%' &&
                ch != '+' && ch != '-' && ch != ' ' && ch != 'e' && ch != 'E'
            ) {
                asciiOnly = false
                break
            }
        }
        if (asciiOnly) return s
        val sb = StringBuilder(s.length)
        for (ch in s) {
            val code = ch.code
            val base = DIGIT_BASES.firstOrNull { code in it..it + 9 }
            sb.append(if (base != null) '0' + (code - base) else ch)
        }
        return sb.toString()
    }

    /**
     * Split an entry line on inline `op number` boundaries (CalcTape behavior:
     * an operator behind a number starts the next calculation line, even in the
     * middle of the comment), so ABC-typed `+ 100 + 20` — or even `100+5` —
     * works exactly like the calculator keys. Returns null when the line
     * doesn't start with an operator or a digit (pure text stays a comment).
     */
    private fun tokenizeEntryLine(trimmed: String): List<RawEntry>? {
        if (trimmed.isEmpty()) return null
        // Lookalike operators from other keyboards/clipboards (× ÷ − –);
        // em-dash is prose punctuation and stays untouched.
        var line = trimmed
            .replace('×', '*').replace('÷', '/').replace('−', '-').replace('–', '-')
            .replaceFirst(currencyLeadRe, "")
        if (line.isEmpty()) return null
        var rest: String
        var op: Char
        val first = line[0]
        val explicitOp = first == '+' || first == '-' || first == '*' || first == '/' || first == '^'
        if (explicitOp) {
            op = first
            // Currency may also hug the number ("+ ₹500").
            rest = line.substring(1).replaceFirst(currencyLeadRe, "").trimStart()
        } else if (headNumRe.matchAt(line, 0) != null) {
            // Bare number, leading-dot and scientific forms included.
            op = '+'
            rest = line
        } else {
            return null
        }
        val head = headNumRe.matchAt(rest, 0)
        if (head == null) {
            // Alphabetic text where a number belongs (`+ abc`): keep the row
            // with a neutral identity amount (0 for +/-, 1 for *//^) and treat
            // everything — including the non-number — as its comment. Silent.
            if (rest.trim().isEmpty()) return null
            val identity = if (op == '*' || op == '/' || op == '^') "1" else "0"
            return listOf(RawEntry(op, identity, false, rest.trim()))
        }
        val out = mutableListOf<RawEntry>()
        var curOp = op
        var curNum = head.groupValues[1]
        var curPct = head.groupValues[2] == "%"
        var cursor = head.range.last + 1
        while (true) {
            val m = splitRe.find(rest, cursor) ?: break
            out.add(RawEntry(curOp, curNum, curPct, rest.substring(cursor, m.range.first).trim()))
            curOp = m.groupValues[1][0]
            curNum = m.groupValues[2]
            curPct = m.groupValues[3] == "%"
            cursor = m.range.last + 1
        }
        out.add(RawEntry(curOp, curNum, curPct, rest.substring(cursor).trim()))
        return out
    }
}
