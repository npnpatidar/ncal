package com.npnpatidar.ncal.tape

import java.math.BigDecimal
import java.math.RoundingMode

object CalcFile {

    const val SEPARATOR = " ------------------ "
    private const val AMOUNT_WIDTH = 17
    private const val HEADER_OPEN = "<SFRCalculatorHeader>"
    private const val HEADER_CLOSE = "</SFRCalculatorHeader>"

    private val separatorRe = Regex("""^\s*-{2,}\s?$""")
    private val headingRe = Regex("""^(#+)\s?(.*)$""")
    private const val NUM_CORE = """(?:\.\p{Nd}+|\p{Nd}[\p{Nd}.,]*)(?:[eE][+-]?[0-9]+)?"""
    private val headNumRe = Regex("([+-]?\\s*$NUM_CORE)(%?)")
    private val splitRe = Regex("([+\\-*/^])\\s*([+-]?\\s*$NUM_CORE)(%?)")
    private val bareOpRe = Regex("""^\s*[+\-*/^]\s*([+-]\s*)?${'$'}""")
    private val currencyLeadRe = Regex("""^[\p{Sc}\s]+""")
    private val varAssignRe = Regex("""^[A-Za-z_][A-Za-z0-9_]*\s*=.*""")
    private val isoDateRe = Regex("""^\d{4}[-/]\d{1,2}[-/]\d{1,2}$""")
    private val isoDateTimeRe = Regex(
        """^\d{4}[-/]\d{1,2}[-/]\d{1,2}(?:[T ]\d{1,2}:\d{2}(?::\d{2}(?:[.,]\d+)?)?(?:Z|[+-]\d{2}:?\d{2})?)?$""",
    )

    fun isBareOpLine(raw: String): Boolean = bareOpRe.matches(raw)

    private fun isSafeSeparator(separator: Char): Boolean =
        !separator.isLetterOrDigit() && !separator.isWhitespace() && !separator.isISOControl() &&
            separator !in "+-*/^%()"

    private data class HeaderData(
        val openIndex: Int,
        val closeIndex: Int,
        val values: Map<String, String>,
        val valid: Boolean,
    )

    private fun readHeader(lines: List<String>): HeaderData? {
        val openIndex = lines.indexOfFirst { it.trim() == HEADER_OPEN }
        if (openIndex != lines.indexOfFirst { it.isNotBlank() }) return null
        if (openIndex < 0) return null
        val closeIndex = lines.indexOfFirst { it.trim() == HEADER_CLOSE }
        if (closeIndex <= openIndex) return null
        if (lines.subList(openIndex + 1, closeIndex).any { it.trim() == HEADER_OPEN }) return null
        val values = mutableMapOf<String, String>()
        var malformedLine = false
        for (line in lines.subList(openIndex + 1, closeIndex)) {
            val eq = line.indexOf('=')
            if (eq > 0) {
                values[line.substring(0, eq).trim()] = line.substring(eq + 1).trim()
            } else if (line.isNotBlank()) {
                malformedLine = true
            }
        }
        val decimalsText = values["DECIMALS"]
        val decimals = decimalsText?.toIntOrNull()
        val decSep = values["DECSEP"]
        val thouSep = values["THOUSEP"]
        val uuid = values["UUID"]
        val caretLineText = values["CARETLINE"]
        val caretLine = caretLineText?.toIntOrNull()
        val caretOffsetText = values["CARETLINEOFFSET"]
        val caretOffset = caretOffsetText?.toIntOrNull()
        val separatorsValid = when {
            decSep == null && thouSep == null -> true
            decSep == null || thouSep == null -> false
            else -> decSep.length == 1 && thouSep.length == 1 && decSep[0] != thouSep[0] &&
                isSafeSeparator(decSep[0]) && isSafeSeparator(thouSep[0])
        }
        val valid = !malformedLine &&
            (decimalsText == null || (decimals != null && decimals in 0..TapeLimits.MAX_DECIMALS)) &&
            separatorsValid &&
            (uuid == null || (uuid.length <= 128 && uuid.none { it.isISOControl() })) &&

            (caretLineText == null || (caretLine != null && caretLine >= 0 && caretLine <= TapeLimits.MAX_LINES)) &&
            (caretOffsetText == null || (caretOffset != null && caretOffset >= 0 && caretOffset <= TapeLimits.MAX_LINE_CHARS))
        return HeaderData(openIndex, closeIndex, values, valid)
    }

    fun hasHeader(text: String): Boolean {
        if (text.length > TapeLimits.MAX_INPUT_CHARS) return false
        val clean = text.removePrefix("\uFEFF")
        return readHeader(clean.lines())?.valid == true
    }

    private val operatorChars = setOf('+', '-', '*', '/', '^', '%', '×', '÷', '−', '–')

    private fun unsupportedHint(ln: String, idx: Int): String? {
        val t = ln.trim()
        if ((t.contains('(') || t.contains(')')) && t.any { it.isDigit() } &&
            t.any { it in operatorChars }
        ) {
            return "line ${idx + 1}: brackets aren't calculated yet — kept as a note"
        }
        if (varAssignRe.matches(t)) {
            return "line ${idx + 1}: variables aren't supported yet — kept as a note"
        }
        return null
    }

    private val datePrefixRe = Regex(
        """^\d{4}[-/]\d{1,2}[-/]\d{1,2}(?:[T ]\d{1,2}:\d{2}(?::\d{2}(?:[.,]\d+)?)?(?:Z|[+-]\d{2}:?\d{2})?)?""",
    )

    fun parse(text: String, fallbackMeta: CalcMeta = CalcMeta()): TapeDoc {
        val safeDecSep = if (fallbackMeta.decSep.isISOControl()) '.' else fallbackMeta.decSep
        var safeThouSep = if (fallbackMeta.thouSep.isISOControl()) ',' else fallbackMeta.thouSep
        if (safeThouSep == safeDecSep) safeThouSep = if (safeDecSep == ',') '.' else ','
        val safeFallback = fallbackMeta.copy(
            decimals = TapeLimits.safeDecimals(fallbackMeta.decimals),
            decSep = safeDecSep,
            thouSep = safeThouSep,
            uuid = fallbackMeta.uuid.take(128),
            caretLine = fallbackMeta.caretLine.coerceIn(0, TapeLimits.MAX_LINES),
            caretOffset = fallbackMeta.caretOffset.coerceIn(0, TapeLimits.MAX_LINE_CHARS),
        )
        if (text.length > TapeLimits.MAX_INPUT_CHARS) {
            return TapeDoc(safeFallback, emptyList(), listOf("input exceeds the supported size"))
        }
        val clean = text.removePrefix("\uFEFF")
        val all = clean.lines()
        if (all.size > TapeLimits.MAX_LINES) {
            return TapeDoc(safeFallback, emptyList(), listOf("input has too many lines"))
        }
        val warnMsgs = mutableListOf<String>()
        val header = readHeader(all)
        var meta = safeFallback
        var body = all
        if (header != null) {
            if (header.valid) {
                val decSep = header.values["DECSEP"]?.singleOrNull() ?: '.'
                val thouSep = header.values["THOUSEP"]?.singleOrNull() ?: ','
                meta = CalcMeta(
                    decimals = TapeLimits.safeDecimals(header.values["DECIMALS"]?.toIntOrNull() ?: 5),
                    decSep = decSep,
                    thouSep = thouSep,
                    uuid = header.values["UUID"]?.ifBlank { null } ?: java.util.UUID.randomUUID().toString(),
                    caretLine = header.values["CARETLINE"]?.toIntOrNull()
                        ?.coerceIn(0, TapeLimits.MAX_LINES) ?: 0,
                    caretOffset = header.values["CARETLINEOFFSET"]?.toIntOrNull()
                        ?.coerceIn(0, TapeLimits.MAX_LINE_CHARS) ?: 0,
                )
                body = all.drop(header.closeIndex + 1)
            } else {
                warnMsgs.add("header metadata is invalid; using defaults")
            }
        }

        val lines = mutableListOf<TapeLine>()
        body.forEachIndexed { idx, raw ->
            if (raw.length > TapeLimits.MAX_LINE_CHARS) {
                warnMsgs.add("line ${idx + 1}: line is too long, kept as a comment")
                lines.add(TapeLine.Comment(raw))
                return@forEachIndexed
            }
            val ln = raw
            when {
                ln.isBlank() -> lines.add(TapeLine.Blank)
                separatorRe.matches(ln) -> lines.add(TapeLine.Separator)
                ln.trim() == "=" -> lines.add(TapeLine.Separator)
                ln.trimStart().startsWith("#") -> {
                    val headingRaw = ln.trimStart()
                    val m = headingRe.matchEntire(headingRaw)
                    if (m != null) lines.add(TapeLine.Heading(m.groupValues[2], ln)) else lines.add(TapeLine.Comment(ln))
                }
                isoDateRe.matches(ln.trim()) || isoDateTimeRe.matches(ln.trim()) -> lines.add(TapeLine.Comment(ln))
                datePrefixRe.find(ln.trim())?.let { match ->
                    ln.trim().substring(match.range.last + 1).takeIf {
                        it.isNotBlank() && it.trimStart().firstOrNull() !in operatorChars
                    }
                } != null -> lines.add(TapeLine.Comment(ln))
                else -> {
                    val hint = unsupportedHint(ln, idx)
                    if (hint != null) {
                        warnMsgs.add(hint)
                        lines.add(TapeLine.Comment(ln))
                        return@forEachIndexed
                    }
                    val raws = tokenizeEntryLine(ln)
                    if (raws == null) {
                        val t = ln.trim()
                        val leadOp = t.firstOrNull()?.let { o ->
                            o == '+' || o == '-' || o == '*' || o == '/' || o == '^'
                        } == true
                        if (leadOp && t.substring(1).trim().isNotEmpty()) {
                            warnMsgs.add("line ${idx + 1}: bad number, kept as a comment")
                        }
                        lines.add(TapeLine.Comment(ln))
                    } else {
                        val tmp = mutableListOf<TapeLine.Entry>()
                        var ok = true
                        var limitHit = false
                        for (r in raws) {
                            if (r.num.length > TapeLimits.MAX_TOKEN_CHARS) {
                                ok = false
                                limitHit = true
                                break
                            }
                            val amount = try {
                                normalizeNumber(r.num, meta)?.toBigDecimalOrNull()
                            } catch (_: RuntimeException) {
                                null
                            }
                            if (amount == null || !TapeLimits.isSupportedNumber(amount)) {
                                ok = false
                                limitHit = limitHit || amount != null
                                break
                            }
                            tmp.add(TapeLine.Entry(r.op, amount, r.pct, r.comment))
                        }
                        if (!ok) {
                            warnMsgs.add(
                                "line ${idx + 1}: " +
                                    if (limitHit) "number exceeds supported limits, kept as a comment"
                                    else "bad number, kept as a comment",
                            )
                            lines.add(TapeLine.Comment(ln))
                        } else {
                            lines.addAll(tmp)
                        }
                    }
                }
            }
        }

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

    fun write(doc: TapeDoc, balanceTotals: Map<Int, BigDecimal>): String =
        writeInternal(doc, balanceTotals)

    private fun writeInternal(
        doc: TapeDoc,
        balanceTotals: Map<Int, BigDecimal>,
    ): String {
        val m = sanitizedMeta(doc.meta)
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
        val core = doc.lines.dropLastWhile { it is TapeLine.Blank }
        var estimatedSize = TapeLimits.utf8Size(header.joinToString("\n")) + 16L
        for ((index, line) in core.withIndex()) {
            val amountSize = when (line) {
                is TapeLine.Entry -> TapeLimits.estimatedAmountChars(
                    line.amount,
                    m.decimals,
                    line.isPercent,
                    true,
                    m.decSep,
                    m.thouSep,
                )
                is TapeLine.Balance -> {
                    val value = balanceTotals[index] ?: line.value
                    TapeLimits.estimatedAmountChars(
                        value,
                        m.decimals,
                        false,
                        false,
                        m.decSep,
                        m.thouSep,
                    )
                }
                else -> 0L
            }
            val rawSize = when (line) {
                is TapeLine.Entry -> TapeLimits.utf8Size(line.comment) + AMOUNT_WIDTH + 4L
                is TapeLine.Balance -> TapeLimits.utf8Size(line.comment) + AMOUNT_WIDTH + 4L
                is TapeLine.Heading -> TapeLimits.utf8Size(line.raw) + 1L
                is TapeLine.Comment -> TapeLimits.utf8Size(line.raw) + 1L
                TapeLine.Separator -> TapeLimits.utf8Size(SEPARATOR) + 1L
                TapeLine.Blank -> 1L
            }
            estimatedSize += amountSize + 8L + rawSize
            TapeLimits.ensureRenderedBudget(estimatedSize)
        }
        for ((index, line) in core.withIndex()) {
            when (line) {
                is TapeLine.Entry -> body.add(formatEntry(line.op, line.amount, line.isPercent, line.comment, m))
                is TapeLine.Separator -> body.add(SEPARATOR)
                is TapeLine.Balance -> {
                    val v = balanceTotals[index] ?: line.value
                    body.add(formatBalance(v, line.comment, m))
                }
                is TapeLine.Blank -> body.add("")
                is TapeLine.Heading -> body.add(line.raw)
                is TapeLine.Comment -> body.add(line.raw)
            }
        }
        body.add("")
        body.add("")
        val result = (header + body).joinToString("\n") + "\n"
        TapeLimits.ensureRenderedTextBudget(result)
        return result
    }

    fun formatEntry(
        op: Char,
        amount: BigDecimal,
        isPercent: Boolean,
        comment: String,
        meta: CalcMeta,
    ): String = formatEntryInternal(op, amount, isPercent, comment, meta, true)

    fun formatBalance(amount: BigDecimal, comment: String, meta: CalcMeta): String =
        formatEntryInternal('+', amount, false, comment, meta, false)

    private fun sanitizedMeta(meta: CalcMeta): CalcMeta {
        val decSep = if (meta.decSep.isISOControl()) '.' else meta.decSep
        var thouSep = if (meta.thouSep.isISOControl()) ',' else meta.thouSep
        if (thouSep == decSep) thouSep = if (decSep == ',') '.' else ','
        val uuid = meta.uuid.filterNot { it.isISOControl() }.take(128)
        return meta.copy(
            decimals = TapeLimits.safeDecimals(meta.decimals),
            decSep = decSep,
            thouSep = thouSep,
            uuid = uuid.ifBlank { java.util.UUID.randomUUID().toString() },
            caretLine = meta.caretLine.coerceIn(0, TapeLimits.MAX_LINES),
            caretOffset = meta.caretOffset.coerceIn(0, TapeLimits.MAX_LINE_CHARS),
        )
    }

    private fun formatEntryInternal(
        op: Char,
        amount: BigDecimal,
        isPercent: Boolean,
        comment: String,
        meta: CalcMeta,
        preserveScale: Boolean,
    ): String {
        val safeMeta = sanitizedMeta(meta)
        val (dop, abs) = TapeFormatter.displayParts(op, amount)
        val digits = TapeFormatter.formatAmount(abs, safeMeta.decimals, isPercent, preserveScale)
            .replace('.', safeMeta.decSep)
        return " " + dop + digits.padStart(AMOUNT_WIDTH) + " " + comment
    }

    private fun normalizeNumber(raw: String, meta: CalcMeta): String? {
        val arabic = raw.replace("٬", "").replace("٫", ".")
        val nospace = asciiDigits(arabic).replace(" ", "")
        if (nospace.isEmpty() || meta.decSep == meta.thouSep) return null
        val exponentIndex = nospace.indexOfFirst { it == 'e' || it == 'E' }
        val mantissa = if (exponentIndex >= 0) nospace.substring(0, exponentIndex) else nospace
        val exponent = if (exponentIndex >= 0) nospace.substring(exponentIndex) else ""
        if (exponent.isNotEmpty() && !exponent.matches(Regex("[eE][+-]?[0-9]+"))) return null
        val sign = if (mantissa.startsWith("+") || mantissa.startsWith("-")) mantissa.take(1) else ""
        val unsigned = if (sign.isNotEmpty()) mantissa.drop(1) else mantissa
        if (unsigned.isEmpty()) return null

        fun digitsOnly(value: String): Boolean = value.isNotEmpty() && value.all { it in '0'..'9' }
        fun validGrouping(value: String, separator: Char): Boolean {
            val parts = value.split(separator)
            if (parts.size == 1) return digitsOnly(value)
            if (parts.any { it.isEmpty() || it.any { ch -> ch !in '0'..'9' } }) return false
            val western = parts.first().length in 1..3 && parts.drop(1).all { it.length == 3 }
            val indian = parts.last().length == 3 && parts.dropLast(1).all { it.length == 2 } &&
                parts.first().length in 1..3
            return western || indian
        }

        if (meta.decSep == '.' && meta.thouSep == ',') {
            if (unsigned.contains(',')) {
                if (unsigned.contains('.')) {
                    val cut = unsigned.lastIndexOf('.')
                    if (cut <= 0 || cut == unsigned.lastIndex ||
                        !validGrouping(unsigned.substring(0, cut), ',') ||
                        !digitsOnly(unsigned.substring(cut + 1))) return null
                    return sign + unsigned.substring(0, cut).replace(",", "") + "." +
                        unsigned.substring(cut + 1) + exponent
                }
                val parts = unsigned.split(',')
                if (parts.size == 2 && parts[1].length in 1..2 && digitsOnly(unsigned.replace(",", ""))) {
                    return sign + parts[0] + "." + parts[1] + exponent
                }
                if (unsigned.startsWith("0,") && parts.size == 2 && parts[1].length == 3) return null
                if (!validGrouping(unsigned, ',')) return null
                return sign + unsigned.replace(",", "") + exponent
            }
            if (unsigned.count { it == '.' } > 1) return null
            val cut = unsigned.indexOf('.')
            if (cut == unsigned.lastIndex) return null
            if (cut > 0 && !digitsOnly(unsigned.substring(0, cut))) return null
            if (cut >= 0 && !digitsOnly(unsigned.substring(cut + 1))) return null
            return sign + unsigned + exponent
        }

        if (unsigned.contains(meta.decSep)) {
            val cut = unsigned.lastIndexOf(meta.decSep)
            if (cut <= 0 || cut == unsigned.lastIndex ||
                !validGrouping(unsigned.substring(0, cut), meta.thouSep) ||
                !digitsOnly(unsigned.substring(cut + 1))) return null
            return sign + unsigned.substring(0, cut).replace(meta.thouSep.toString(), "") + "." +
                unsigned.substring(cut + 1) + exponent
        }
        if (unsigned.contains(meta.thouSep)) {
            if (!validGrouping(unsigned, meta.thouSep)) return null
            return sign + unsigned.replace(meta.thouSep.toString(), "") + exponent
        }
        if (!digitsOnly(unsigned)) return null
        return sign + unsigned + exponent
    }

    private data class RawEntry(val op: Char, val num: String, val pct: Boolean, val comment: String)

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

    private fun commentPart(value: String): String = value.trimStart()

    private fun tokenizeEntryLine(raw: String): List<RawEntry>? {
        var line = raw.trimStart()
            .replace('×', '*').replace('÷', '/').replace('−', '-').replace('–', '-')
            .replaceFirst(currencyLeadRe, "")
        if (line.isEmpty()) return null
        var op: Char
        val first = line[0]
        val explicitOp = first == '+' || first == '-' || first == '*' || first == '/' || first == '^'
        if (explicitOp) {
            op = first
            line = line.substring(1).replaceFirst(currencyLeadRe, "").trimStart()
        } else if (headNumRe.matchAt(line, 0) != null) {
            op = '+'
        } else {
            return null
        }
        if (line.isEmpty()) return null
        val head = headNumRe.matchAt(line, 0)
        if (head == null) {
            val identity = if (op == '*' || op == '/' || op == '^') "1" else "0"
            return listOf(RawEntry(op, identity, false, line))
        }
        val next = line.getOrNull(head.range.last + 1)
        if ((next == '.' || next == ',' || next == 'e' || next == 'E') &&
            line.substring(head.range.last + 1).isNotBlank()
        ) return null
        val out = mutableListOf<RawEntry>()
        var curOp = op
        var curNum = head.groupValues[1]
        var curPct = head.groupValues[2] == "%"
        var cursor = head.range.last + 1
        while (true) {
            val m = splitRe.find(line, cursor) ?: break
            out.add(RawEntry(curOp, curNum, curPct, commentPart(line.substring(cursor, m.range.first))))
            curOp = m.groupValues[1][0]
            curNum = m.groupValues[2]
            curPct = m.groupValues[3] == "%"
            cursor = m.range.last + 1
        }
        out.add(RawEntry(curOp, curNum, curPct, commentPart(line.substring(cursor))))
        return out
    }
}
