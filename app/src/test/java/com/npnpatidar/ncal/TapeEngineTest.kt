package com.npnpatidar.ncal

import com.npnpatidar.ncal.tape.CalcFile
import com.npnpatidar.ncal.tape.CalcMeta
import com.npnpatidar.ncal.tape.Grouping
import com.npnpatidar.ncal.tape.TapeEvaluator
import com.npnpatidar.ncal.tape.TapeFormatter
import com.npnpatidar.ncal.tape.TapeLine
import com.npnpatidar.ncal.tape.TapeDoc
import com.npnpatidar.ncal.tape.TapeEdit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigDecimal
import java.math.RoundingMode

/**
 * Engine tests. The anonymized [LEDGER] fixture mirrors the structure of a real
 * CalcTape export (chained blocks, separator + balance restatement) without
 * copying personal data into this public repo.
 */
class TapeEngineTest {

    private val meta = CalcMeta(decimals = 5)

    /** Numerical equality, immune to BigDecimal scale quirks (100 vs 1E+2). */
    private fun assertAmount(expected: String, actual: BigDecimal) {
        assertEquals(0, BigDecimal(expected).compareTo(actual))
    }

    /** Same shape as a CalcTape export: entries, separator, balance, entries... */
    private val LEDGER = """
<SFRCalculatorHeader>
CARETLINE=10
CARETLINEOFFSET=0
CFGVER=1
DECIMALS=5
DECSEP=.
EXTSYN=0
THOUSEP=,
TXTMODE=0
TXTSTYLE=0
UUID=00000000-0000-0000-0000-000000000000
VARINFO=
</SFRCalculatorHeader>
 +         45.00000 alpha
 +        119.00000 beta
 +        292.00000 gamma
 ------------------ 
 +        456.00000 
 -        155.00000 delta
 ------------------ 
 +        301.00000 
 -          8.00000 epsilon
 ------------------ 
 +        293.00000 
 +         99.00000 zeta
 ------------------ 
 +        392.00000 
""".trimIndent() + "\n\n\n"

    @Test
    fun chainedBlocksEvaluateToRunningTotals() {
        val doc = CalcFile.parse(LEDGER)
        assertTrue("warnings: ${doc.warnings}", doc.warnings.isEmpty())
        val eval = TapeEvaluator.evaluate(doc.lines, doc.meta.decimals)
        assertTrue("errors: ${eval.errors}", eval.errors.isEmpty())
        assertEquals(
            listOf("456.00000", "301.00000", "293.00000", "392.00000"),
            eval.subtotals.map { it.setScale(5).toPlainString() },
        )
        assertEquals("392.00000", eval.grandTotal.setScale(5).toPlainString())
    }

    @Test
    fun balanceLinesAreNeverReAdded() {
        // Flat re-addition of every line would give 45+119+292+456-155+301-8+293+99+392 = 1834.
        // Correct chained total is 392.
        val doc = CalcFile.parse(LEDGER)
        val balances = doc.lines.filterIsInstance<TapeLine.Balance>()
        assertEquals(4, balances.size)
        val eval = TapeEvaluator.evaluate(doc.lines, doc.meta.decimals)
        assertEquals(BigDecimal("392.00000"), eval.grandTotal.setScale(5))
    }

    @Test
    fun multiplicationBindsBeforeAdditionAcrossLines() {
        // From the CalcTape manual: +10, +2, *3 = 16, not 36.
        val doc = CalcFile.parse(" + 10\n + 2\n * 3\n")
        val eval = TapeEvaluator.evaluate(doc.lines, 2)
        assertTrue(eval.errors.isEmpty())
        assertAmount("16", eval.grandTotal)
    }

    @Test
    fun percentResolvesAgainstRunningSubtotal() {
        // 1000 net + 19% VAT = 1190.
        val doc = CalcFile.parse(" + 1000.00 net\n + 19.00% vat\n")
        val eval = TapeEvaluator.evaluate(doc.lines, 2)
        assertTrue(eval.errors.isEmpty())
        assertEquals(BigDecimal("1190.00"), eval.grandTotal.setScale(2))
    }

    @Test
    fun discountPercent() {
        val doc = CalcFile.parse(" + 100.00\n - 20.00%\n")
        val eval = TapeEvaluator.evaluate(doc.lines, 2)
        assertEquals(BigDecimal("80.00"), eval.grandTotal.setScale(2))
    }

    @Test
    fun blankLineStartsIndependentCalculation() {
        val doc = CalcFile.parse(" + 5.00\n\n + 7.00\n")
        val eval = TapeEvaluator.evaluate(doc.lines, 2)
        assertEquals(BigDecimal("12.00"), eval.grandTotal.setScale(2))
    }

    @Test
    fun divisionByZeroIsAnError() {
        val doc = CalcFile.parse(" + 10\n / 0\n")
        val eval = TapeEvaluator.evaluate(doc.lines, 2)
        assertTrue(eval.errors.any { it.contains("division by zero") })
    }

    @Test
    fun writerEmitsExactCalcTapeLineShapes() {
        assertEquals(" +         45.00000 hdfc", CalcFile.formatEntry('+', BigDecimal("45"), false, "hdfc", meta))
        assertEquals(" -        155.00000 mohan anytime", CalcFile.formatEntry('-', BigDecimal("155"), false, "mohan anytime", meta))
        assertEquals(" +        456.00000 ", CalcFile.formatEntry('+', BigDecimal("456"), false, "", meta))
        assertEquals(" ------------------ ", CalcFile.SEPARATOR)
        assertEquals(20, " +        456.00000 ".length)
        assertEquals(20, CalcFile.SEPARATOR.length)
    }

    @Test
    fun roundTripPreservesBody() {
        val doc = CalcFile.parse(LEDGER)
        val eval = TapeEvaluator.evaluate(doc.lines, doc.meta.decimals)
        val out = CalcFile.write(doc, eval.subtotals)
        // Body (everything after the header) must be byte-identical.
        val bodyOf = { t: String -> t.substringAfter("</SFRCalculatorHeader>\n") }
        assertEquals(bodyOf(LEDGER), bodyOf(out))
    }

    @Test
    fun exportIsIdempotent() {
        val doc = CalcFile.parse(LEDGER)
        val eval = TapeEvaluator.evaluate(doc.lines, doc.meta.decimals)
        val once = CalcFile.write(doc, eval.subtotals)
        val doc2 = CalcFile.parse(once)
        val eval2 = TapeEvaluator.evaluate(doc2.lines, doc2.meta.decimals)
        assertEquals(once, CalcFile.write(doc2, eval2.subtotals))
    }

    @Test
    fun bomIsStrippedOnImport() {
        val doc = CalcFile.parse("\uFEFF<SFRCalculatorHeader>\nDECIMALS=2\n</SFRCalculatorHeader>\n + 1.00\n")
        assertEquals(2, doc.meta.decimals)
        assertEquals(1, doc.lines.filterIsInstance<TapeLine.Entry>().size)
    }

    @Test
    fun commentsAndHeadingsAreIgnoredForMath() {
        val doc = CalcFile.parse("# Trip\n + 10.00 hotel\njust a note 123\n + 5.00 taxi\n")
        val eval = TapeEvaluator.evaluate(doc.lines, 2)
        assertTrue(eval.errors.isEmpty())
        assertEquals(BigDecimal("15.00"), eval.grandTotal.setScale(2))
    }

    @Test
    fun prettyLaysOutThreeLeftAlignedColumns() {
        val pretty = TapeFormatter.pretty(
            " + 10.00 alpha\n + 2.50 b\n ------------------ \n + 12.50 \n",
            2,
        )
        assertEquals(
            "+ 10.00  alpha\n+ 2.50   b\n ------------------ \n+ 12.50\n",
            pretty,
        )
    }

    @Test
    fun prettyKeepsMathIdentical() {
        val canonical = " + 45.00000 hdfc\n + 119.00000 bob\n ------------------ \n + 164.00000 \n"
        val pretty = TapeFormatter.pretty(canonical, 5)
        val a = TapeEvaluator.evaluate(CalcFile.parse(canonical).lines, 5)
        val b = TapeEvaluator.evaluate(CalcFile.parse(pretty).lines, 5)
        assertTrue(b.errors.isEmpty())
        assertEquals(a.grandTotal, b.grandTotal)
        assertEquals(a.subtotals, b.subtotals)
    }

    @Test
    fun bareNumberHasImpliedPlus() {
        val doc = CalcFile.parse("78 lunch\n + 22\n")
        assertTrue(doc.warnings.isEmpty())
        val eval = TapeEvaluator.evaluate(doc.lines, 2)
        assertTrue(eval.errors.isEmpty())
        assertEquals(BigDecimal("100"), eval.grandTotal.setScale(0))
    }

    @Test
    fun bareSpacelessCompoundSplits() {
        // `100+5` computes like the calculator keys (CalcTape behavior).
        val eval = TapeEvaluator.evaluate(CalcFile.parse("100+5\n").lines, 2)
        assertTrue(eval.errors.isEmpty())
        assertAmount("105", eval.grandTotal)
    }

    @Test
    fun negativeExponentInline() {
        // `2^-3` is 0.125 (was silently -1 before the fix).
        val eval = TapeEvaluator.evaluate(CalcFile.parse(" + 2^-3\n").lines, 3)
        assertTrue(eval.errors.isEmpty())
        assertEquals(BigDecimal("0.125"), eval.grandTotal.stripTrailingZeros())
    }

    @Test
    fun signedFactorInline() {
        // `5 * -2` is -10 (was silently +3 before the fix).
        val eval = TapeEvaluator.evaluate(CalcFile.parse("5 * -2\n").lines, 2)
        assertTrue(eval.errors.isEmpty())
        assertAmount("-10", eval.grandTotal)
    }

    @Test
    fun doubleNegativeInline() {
        // `5 - -3` is 5 - (-3) = 8.
        val eval = TapeEvaluator.evaluate(CalcFile.parse(" + 5 - -3\n").lines, 2)
        assertTrue(eval.errors.isEmpty())
        assertAmount("8", eval.grandTotal)
    }

    @Test
    fun textLedDateStaysComment() {
        val doc = CalcFile.parse("trip 2026-09-21\n + 1\n")
        val eval = TapeEvaluator.evaluate(doc.lines, 2)
        assertAmount("1", eval.grandTotal)
    }

    @Test
    fun loneOperatorLineIsSilent() {        // Mid-typing ` + ` must not raise (or log) an error.
        val doc = CalcFile.parse(" + \n + 5\n")
        assertTrue(doc.warnings.isEmpty())
        val eval = TapeEvaluator.evaluate(doc.lines, 2)
        assertTrue(eval.errors.isEmpty())
        assertAmount("5", eval.grandTotal)
    }

    @Test
    fun balanceCommentSurvivesPrettyAndRoundTrip() {
        // Description typed on a subtotal line must never be deleted by `=`.
        val pretty = TapeFormatter.pretty(
            " + 10.00 a\n ------------------ \n + 10.00 kept\n",
            2,
        )
        assertEquals(
            "+ 10.00  a\n ------------------ \n+ 10.00  kept\n",
            pretty,
        )
        val doc = CalcFile.parse(pretty)
        val bal = doc.lines.filterIsInstance<TapeLine.Balance>().single()
        assertEquals("kept", bal.comment)
        val eval = TapeEvaluator.evaluate(doc.lines, 2)
        assertEquals(BigDecimal("10.00"), eval.grandTotal.setScale(2))
        // Canonical export keeps it too (re-parsed without a header, so the
        // default 5 decimals apply).
        val out = CalcFile.write(doc, eval.subtotals)
        assertTrue(out.contains("10.00000 kept"))
    }

    @Test
    fun plainDashSeparatorClosesBlock() {
        val doc = CalcFile.parse(" + 5\n------------------\n + 3\n")
        val eval = TapeEvaluator.evaluate(doc.lines, 2)
        assertTrue(eval.errors.isEmpty())
        assertEquals(listOf(BigDecimal("5.00")), eval.subtotals.map { it.setScale(2) })
        // `+ 3` after the separator is a restatement row, never fresh input:
        // it snaps to the running total, so grand stays 5. Stale values can
        // never inflate the total (see the hisab 45→50 regression tests).
        assertEquals(BigDecimal("5.00"), eval.grandTotal.setScale(2))
    }

    @Test
    fun matchingPostSeparatorPlusIsRestatement() {
        val doc = CalcFile.parse(" + 5\n------------------\n + 5.00 \n")
        val eval = TapeEvaluator.evaluate(doc.lines, 2)
        assertTrue(eval.errors.isEmpty())
        assertEquals(BigDecimal("5.00"), eval.grandTotal.setScale(2))
    }

    @Test
    fun patchBalancesFreshensStaleSubtotal() {
        val raw = " + 100\n------------------\n + 0\n"
        val doc = CalcFile.parse(raw)
        val eval = TapeEvaluator.evaluate(doc.lines, 2)
        val patched = TapeFormatter.patchBalances(raw, doc, eval, 2, 2, Grouping.OFF)
        assertEquals(" + 100\n------------------\n+ 100.00\n", patched)
        // And the patched tape evaluates cleanly with the subtotal as restatement.
        val eval2 = TapeEvaluator.evaluate(CalcFile.parse(patched!!).lines, 2)
        assertTrue(eval2.errors.isEmpty())
        assertEquals(BigDecimal("100.00"), eval2.grandTotal.setScale(2))
    }

    @Test
    fun patchBalancesIsNoopWhenFresh() {
        val pretty = TapeFormatter.pretty(LEDGER, 5)
        val doc = CalcFile.parse(pretty)
        val eval = TapeEvaluator.evaluate(doc.lines, 5)
        assertEquals(null, TapeFormatter.patchBalances(pretty, doc, eval, 5, 2, Grouping.OFF))
    }

    @Test
    fun negativeSubtotalShowsMinusOperand() {
        val eval = TapeEvaluator.evaluate(CalcFile.parse(" + 5\n - 12\n").lines, 2)
        assertTrue(eval.errors.isEmpty())
        assertEquals(BigDecimal("-7.00"), eval.grandTotal.setScale(2))
        // Canonical form carries the sign in the operator column, never `+ -7`.
        assertEquals(" -          7.00000 ", CalcFile.formatEntry('+', BigDecimal("-7"), false, "", meta))
        // …and it round-trips as a restatement, not fresh input.
        val tape2 = " + 5\n - 12\n ------------------ \n -          7.00000 \n"
        val eval2 = TapeEvaluator.evaluate(CalcFile.parse(tape2).lines, 2)
        assertTrue(eval2.errors.isEmpty())
        assertEquals(BigDecimal("-7.00"), eval2.grandTotal.setScale(2))
    }

    @Test
    fun prettyNegativeBalanceThreeColumns() {
        assertEquals(
            "+ 5.00\n- 12.00\n ------------------ \n- 7.00\n",
            TapeFormatter.pretty(" + 5\n - 12\n ------------------ \n - 7.00\n", 2),
        )
    }

    @Test
    fun groupingWesternAndIndian() {
        assertEquals("1,234,567.89", TapeFormatter.groupNumber("1234567.89", Grouping.COMMA))
        assertEquals("123.00", TapeFormatter.groupNumber("123.00", Grouping.COMMA))
        assertEquals("19.00%", TapeFormatter.groupNumber("19.00%", Grouping.COMMA))
        assertEquals("12,34,567.89", TapeFormatter.groupNumber("1234567.89", Grouping.INDIAN))
        assertEquals("1,00,000.00", TapeFormatter.groupNumber("100000.00", Grouping.INDIAN))
        assertEquals("999.00", TapeFormatter.groupNumber("999.00", Grouping.INDIAN))
    }

    @Test
    fun indentWidensCommentColumn() {
        assertEquals("+ 10.00    a\n", TapeFormatter.pretty(" + 10.00 a\n", 2, indent = 4))
    }

    @Test
    fun groupedDisplayStillParses() {
        val pretty = TapeFormatter.pretty(" + 1234567.89 big\n", 2, grouping = Grouping.COMMA)
        assertTrue(pretty.contains("1,234,567.89"))
        val eval = TapeEvaluator.evaluate(CalcFile.parse(pretty).lines, 2)
        assertTrue(eval.errors.isEmpty())
        assertEquals(BigDecimal("1234567.89"), eval.grandTotal)
    }

    @Test
    fun inlineOperatorStartsNewEntry() {
        // ABC-typed `+ 100 + 20` behaves like the calculator keys.
        val eval = TapeEvaluator.evaluate(CalcFile.parse(" + 100 + 20 redux\n").lines, 2)
        assertTrue(eval.errors.isEmpty())
        assertEquals(BigDecimal("120.00"), eval.grandTotal.setScale(2))
    }

    @Test
    fun inlineOperatorInCommentStaysText() {
        val eval = TapeEvaluator.evaluate(CalcFile.parse(" + 100 Rent + heating\n").lines, 2)
        assertTrue(eval.errors.isEmpty())
        assertEquals(BigDecimal("100.00"), eval.grandTotal.setScale(2))
    }

    @Test
    fun bareCompoundWithSpacesSplits() {
        val eval = TapeEvaluator.evaluate(CalcFile.parse("5 + 3\n").lines, 2)
        assertAmount("8", eval.grandTotal)
    }

    @Test
    fun leadingMultiplyChainsOntoTotal() {
        // `* 3` after a 100 subtotal triples it instead of erroring.
        val doc = CalcFile.parse(" + 100\n ------------------ \n + 100.00 \n * 3\n")
        val eval = TapeEvaluator.evaluate(doc.lines, 2)
        assertTrue(eval.errors.isEmpty())
        assertEquals(listOf(BigDecimal("100.00")), eval.subtotals.map { it.setScale(2) })
        assertEquals(BigDecimal("300.00"), eval.grandTotal.setScale(2))
    }

    @Test
    fun leadingMultiplyOnEmptyTapeIsQuietZero() {
        val eval = TapeEvaluator.evaluate(CalcFile.parse(" * 5\n").lines, 2)
        assertTrue(eval.errors.isEmpty())
        assertEquals(0, eval.grandTotal.compareTo(BigDecimal.ZERO))
    }

    @Test
    fun hasOpenEntriesGuardsEquals() {
        assertFalse(TapeEvaluator.hasOpenEntries(CalcFile.parse("").lines))
        assertFalse(TapeEvaluator.hasOpenEntries(CalcFile.parse(" + 5\n ------------------ \n + 5.00 \n").lines))
        assertTrue(TapeEvaluator.hasOpenEntries(CalcFile.parse(" + 5\n").lines))
        assertTrue(
            TapeEvaluator.hasOpenEntries(
                CalcFile.parse(" + 5\n ------------------ \n + 5.00 \n + 2\n").lines,
            ),
        )
    }

    @Test
    fun textWhereNumberBelongsBecomesComment() {
        // `+ abc`: the row survives with a neutral 0, everything is comment.
        val doc = CalcFile.parse(" + abc\n + 5\n")
        assertTrue(doc.warnings.isEmpty())
        val eval = TapeEvaluator.evaluate(doc.lines, 2)
        assertTrue(eval.errors.isEmpty())
        assertEquals(BigDecimal("5.00"), eval.grandTotal.setScale(2))
        val pretty = TapeFormatter.pretty(" + abc\n + 5\n", 2)
        assertTrue(pretty.contains("abc"))
    }

    @Test
    fun multiplicativeTextKeepsIdentity() {
        // `* xyz` must not nuke the chain: missing factor behaves as ×1.
        val eval = TapeEvaluator.evaluate(CalcFile.parse(" + 100\n * xyz\n").lines, 2)
        assertTrue(eval.errors.isEmpty())
        assertEquals(BigDecimal("100.00"), eval.grandTotal.setScale(2))
    }

    @Test
    fun signedOperandAfterOperator() {
        // `* -2` on a 30 subtotal = -60, exactly like the reference tape.
        val doc = CalcFile.parse(" + 30\n ------------------ \n + 30.00 \n * -2\n")
        val eval = TapeEvaluator.evaluate(doc.lines, 2)
        assertTrue(eval.errors.isEmpty())
        assertEquals(listOf(BigDecimal("30.00")), eval.subtotals.map { it.setScale(2) })
        assertEquals(BigDecimal("-60.00"), eval.grandTotal.setScale(2))
    }

    @Test
    fun isBareOpLineDetection() {
        assertTrue(CalcFile.isBareOpLine(" * "))
        assertTrue(CalcFile.isBareOpLine(" *- "))
        assertTrue(CalcFile.isBareOpLine(" + "))
        assertFalse(CalcFile.isBareOpLine(" + 5"))
        assertFalse(CalcFile.isBareOpLine(" + 5.00 x"))
        assertFalse(CalcFile.isBareOpLine("hello"))
        assertFalse(CalcFile.isBareOpLine(""))
    }

    @Test
    fun markLinesColorsAndBolds() {
        val doc = CalcFile.parse(" + 5\n - 12\n ------------------ \n - 7.00\n * -2\n")
        val eval = TapeEvaluator.evaluate(doc.lines, 2)
        val marks = TapeFormatter.markLines(doc, eval)
        assertEquals(TapeFormatter.LineMark(bold = false, negative = false), marks[0])
        assertEquals(TapeFormatter.LineMark(bold = false, negative = true), marks[1])
        assertEquals(TapeFormatter.LineMark(bold = false, negative = false), marks[2])
        assertEquals(TapeFormatter.LineMark(bold = true, negative = true), marks[3])
        assertEquals(TapeFormatter.LineMark(bold = false, negative = true), marks[4])
    }

    @Test
    fun multPercentUsesFraction() {
        // `* 19%` means ×0.19: 100 * 0.19 = 19 (was 1900 before the fix).
        val eval = TapeEvaluator.evaluate(CalcFile.parse(" + 100\n * 19%\n").lines, 2)
        assertTrue(eval.errors.isEmpty())
        assertEquals(BigDecimal("19.00"), eval.grandTotal.setScale(2))
    }

    @Test
    fun divPercentUsesFraction() {
        // `/ 50%` means ÷0.5: 200 / 0.5 = 400 (was 2 before the fix).
        val eval = TapeEvaluator.evaluate(CalcFile.parse(" + 200\n / 50%\n").lines, 2)
        assertTrue(eval.errors.isEmpty())
        assertEquals(BigDecimal("400.00"), eval.grandTotal.setScale(2))
    }

    @Test
    fun powPercentUsesFraction() {
        // `^ 50%` means ^0.5 (square root): 2^0.5 ≈ 1.41 (was 2 before the fix).
        val eval = TapeEvaluator.evaluate(CalcFile.parse(" + 2\n ^ 50%\n").lines, 2)
        assertTrue(eval.errors.isEmpty())
        assertEquals(BigDecimal("1.41"), eval.grandTotal.setScale(2, RoundingMode.HALF_UP))
    }

    @Test
    fun leadingStarPercentHalvesTotal() {
        // Chained `* 50%` on a 100 subtotal: 100 × 0.5 = 50.
        val doc = CalcFile.parse(" + 100\n ------------------ \n + 100\n * 50%\n")
        val eval = TapeEvaluator.evaluate(doc.lines, 2)
        assertTrue(eval.errors.isEmpty())
        assertEquals(BigDecimal("50.00"), eval.grandTotal.setScale(2))
    }

    @Test
    fun zeroPercentFactorDivErrors() {
        // `/ 0%` still divides by zero (0/100 = 0 factor).
        val eval = TapeEvaluator.evaluate(CalcFile.parse(" + 200\n / 0%\n").lines, 2)
        assertTrue(eval.errors.any { it.contains("division by zero") })
    }

    @Test
    fun exponentCapRejectsHuge() {
        // `^ 100000` would hang/OOM materializing the exact power: error, keep chain.
        val eval = TapeEvaluator.evaluate(CalcFile.parse(" + 2\n ^ 100000\n").lines, 2)
        assertTrue(eval.errors.any { it.contains("exponent too large") })
        assertAmount("2", eval.grandTotal)
    }

    @Test
    fun exponentCapBoundaryOk() {
        // 2^10 = 1024 is comfortably under the cap.
        val eval = TapeEvaluator.evaluate(CalcFile.parse(" + 2\n ^ 10\n").lines, 2)
        assertTrue(eval.errors.isEmpty())
        assertAmount("1024", eval.grandTotal)
    }

    @Test
    fun exponentCapFirstEntry() {
        // Chained `^ 100000` on a 100 subtotal errors and keeps 100.
        val doc = CalcFile.parse(" + 100\n ------------------ \n + 100\n ^ 100000\n")
        val eval = TapeEvaluator.evaluate(doc.lines, 2)
        assertTrue(eval.errors.any { it.contains("exponent too large") })
        assertAmount("100", eval.grandTotal)
    }

    @Test
    fun zeroToNegativeOneIsErrorNotCrash() {
        // `0 ^ -1` is undefined: error chip, total stays 0 (used to throw).
        val eval = TapeEvaluator.evaluate(CalcFile.parse(" + 0\n ^ -1\n").lines, 2)
        assertTrue(eval.errors.any { it.contains("invalid power") })
        assertAmount("0", eval.grandTotal)
    }

    @Test
    fun negativeFractionalPowerIsError() {
        // `(-8) ^ 0.333` is NaN in doubles: error chip, total stays -8.
        val eval = TapeEvaluator.evaluate(CalcFile.parse(" + -8\n ^ 0.333\n").lines, 2)
        assertTrue(eval.errors.any { it.contains("invalid power") })
        assertAmount("-8", eval.grandTotal)
    }

    @Test
    fun bareEqualsClosesBlock() {
        // A lone `=` typed in ABC works like Enter/=: closes the block.
        val doc = CalcFile.parse(" + 5\n=\n")
        assertTrue(doc.warnings.isEmpty())
        val eval = TapeEvaluator.evaluate(doc.lines, 2)
        assertEquals(listOf(BigDecimal("5.00")), eval.subtotals.map { it.setScale(2) })
        assertEquals(BigDecimal("5.00"), eval.grandTotal.setScale(2))
    }

    @Test
    fun formulaParensWarnButPreserved() {
        // `(5+3)` can't be calculated yet: kept verbatim, with a hint.
        val doc = CalcFile.parse("(5+3) q2 report\n")
        assertTrue(doc.warnings.any { it.contains("brackets") })
        assertTrue(doc.lines.first() is TapeLine.Comment)
    }

    @Test
    fun proseParensStaySilent() {
        val doc = CalcFile.parse("(see receipt)\n(3 nights)\n")
        assertTrue(doc.warnings.isEmpty())
    }

    @Test
    fun variableAssignWarnsButPreserved() {
        // `x = 5` isn't a variable yet: kept verbatim, with a hint.
        val doc = CalcFile.parse("x = 5\n")
        assertTrue(doc.warnings.any { it.contains("variables") })
        assertTrue(doc.lines.first() is TapeLine.Comment)
    }

    @Test
    fun variableUseIsQuietZero() {
        // `+ x` reads as +0 "x" (no warning, no math) until variables exist.
        val doc = CalcFile.parse(" + x\n")
        assertTrue(doc.warnings.isEmpty())
        val eval = TapeEvaluator.evaluate(doc.lines, 2)
        assertAmount("0", eval.grandTotal)
    }

    // ---------- header handling ----------

    @Test
    fun headerMissingCloseTreatedAsBody() {
        // No closing tag: the header-looking line is just a comment.
        val doc = CalcFile.parse("<SFRCalculatorHeader>\n + 5\n")
        assertTrue(doc.warnings.isEmpty())
        assertAmount("5", TapeEvaluator.evaluate(doc.lines, 2).grandTotal)
    }

    @Test
    fun headerBadDecimalsDefaults5() {
        val text = "<SFRCalculatorHeader>\nDECIMALS=abc\n</SFRCalculatorHeader>\n + 1\n"
        assertEquals(5, CalcFile.parse(text).meta.decimals)
    }

    @Test
    fun headerUnknownKeysIgnored() {
        val text = "<SFRCalculatorHeader>\nFOO=bar\nDECIMALS=2\n</SFRCalculatorHeader>\n + 2\n"
        val doc = CalcFile.parse(text)
        assertEquals(2, doc.meta.decimals)
        assertAmount("2", TapeEvaluator.evaluate(doc.lines, 2).grandTotal)
    }

    @Test
    fun headerCaretValuesKept() {
        val text = "<SFRCalculatorHeader>\nCARETLINE=7\nCARETLINEOFFSET=3\n</SFRCalculatorHeader>\n"
        val meta = CalcFile.parse(text).meta
        assertEquals(7, meta.caretLine)
        assertEquals(3, meta.caretOffset)
    }

    @Test
    fun hasHeaderDetection() {
        assertTrue(CalcFile.hasHeader("<SFRCalculatorHeader>\nDECIMALS=2\n</SFRCalculatorHeader>\n + 1\n"))
        assertTrue(CalcFile.hasHeader("\uFEFF<SFRCalculatorHeader>\n</SFRCalculatorHeader>\n"))
        assertFalse(CalcFile.hasHeader(" + 1\njust text\n"))
        assertFalse(CalcFile.hasHeader(""))
    }

    @Test
    fun crlfHandled() {
        val eval = TapeEvaluator.evaluate(CalcFile.parse("+ 5\r\n+ 6\r\n").lines, 2)
        assertAmount("11", eval.grandTotal)
    }

    // ---------- writer ----------

    @Test
    fun headerOrderExact() {
        val m = CalcMeta(decimals = 5, decSep = '.', thouSep = ',', uuid = "U", caretLine = 3, caretOffset = 4)
        val out = CalcFile.write(TapeDoc(m, emptyList()), emptyList())
        assertEquals(
            listOf(
                "<SFRCalculatorHeader>", "CARETLINE=3", "CARETLINEOFFSET=4", "CFGVER=1",
                "DECIMALS=5", "DECSEP=.", "EXTSYN=0", "THOUSEP=,", "TXTMODE=0",
                "TXTSTYLE=0", "UUID=U", "VARINFO=", "</SFRCalculatorHeader>",
            ),
            out.split("\n").take(13),
        )
    }

    @Test
    fun balanceFallbackUsesStored() {
        // Fewer evaluated subtotals than balance lines: stored value kept.
        val doc = TapeDoc(
            CalcMeta(decimals = 5),
            listOf(
                TapeLine.Entry('+', BigDecimal("5"), false, ""),
                TapeLine.Separator,
                TapeLine.Balance(BigDecimal("9"), ""),
            ),
        )
        assertTrue(CalcFile.write(doc, emptyList()).contains("9.00000"))
    }

    @Test
    fun blankMiddlePreserved() {
        val out = CalcFile.write(CalcFile.parse(" + 5\n\n + 7\n").copy(), listOf())
        assertTrue(out.split("\n").contains(""))
    }

    @Test
    fun germanMetaRoundTrip() {
        val m = CalcMeta(decimals = 2, decSep = ',', thouSep = '.', uuid = "G")
        val out = CalcFile.write(
            TapeDoc(m, listOf(TapeLine.Entry('+', BigDecimal("1234.56"), false, ""))),
            emptyList(),
        )
        assertTrue(out.contains("1234,56"))
        val back = CalcFile.parse(out)
        assertEquals(',', back.meta.decSep)
        assertAmount("1234.56", TapeEvaluator.evaluate(back.lines, 2).grandTotal)
    }

    // ---------- evaluator math ----------

    @Test
    fun divChainTriple() {
        assertAmount("5", TapeEvaluator.evaluate(CalcFile.parse(" + 100\n / 4\n / 5\n").lines, 2).grandTotal)
    }

    @Test
    fun multChainTriple() {
        assertAmount("60", TapeEvaluator.evaluate(CalcFile.parse(" + 10\n * 2\n * 3\n").lines, 2).grandTotal)
    }

    @Test
    fun longPrecedenceChain() {
        // 1 + (2*3) + (4*5) = 27: mults bind across lines.
        assertAmount("27", TapeEvaluator.evaluate(CalcFile.parse(" + 1\n + 2\n * 3\n + 4\n * 5\n").lines, 2).grandTotal)
    }

    @Test
    fun zeroBasePercent() {
        assertAmount("0", TapeEvaluator.evaluate(CalcFile.parse(" + 0\n + 10%\n").lines, 2).grandTotal)
    }

    @Test
    fun hundredPercent() {
        assertAmount("200", TapeEvaluator.evaluate(CalcFile.parse(" + 100\n + 100%\n").lines, 2).grandTotal)
    }

    @Test
    fun negativePercentAmount() {
        // "+ -10%" deducts 10%: 100 - 10 = 90.
        assertAmount("90", TapeEvaluator.evaluate(CalcFile.parse(" + 100\n + -10%\n").lines, 2).grandTotal)
    }

    @Test
    fun divByZeroKeepsChain() {
        // Error recorded, chain value kept: 10 + 5 = 15.
        val eval = TapeEvaluator.evaluate(CalcFile.parse(" + 10\n / 0\n + 5\n").lines, 2)
        assertTrue(eval.errors.any { it.contains("division by zero") })
        assertAmount("15", eval.grandTotal)
    }

    @Test
    fun multipleErrorsAccumulate() {
        val eval = TapeEvaluator.evaluate(CalcFile.parse(" + 10\n / 0\n ^ 100000\n").lines, 2)
        assertEquals(2, eval.errors.size)
        assertAmount("10", eval.grandTotal)
    }

    @Test
    fun openTotalVsGrand() {
        // openTotal = current section (7); grand sums sections (12).
        val eval = TapeEvaluator.evaluate(CalcFile.parse(" + 5\n\n + 7").lines, 2)
        assertAmount("7", eval.openTotal)
        assertAmount("12", eval.grandTotal)
    }

    @Test
    fun balanceTotalsMap() {
        val eval = TapeEvaluator.evaluate(CalcFile.parse(" + 5\n------------------\n + 5\n").lines, 2)
        assertEquals(0, BigDecimal("5").compareTo(eval.balanceTotals[2]))
    }

    @Test
    fun percentAfterSeparatorUsesRunning() {
        // `+ 10%` after a 100 subtotal adds 10 → 110.
        assertAmount("110", TapeEvaluator.evaluate(CalcFile.parse(" + 100\n------------------\n + 10%\n").lines, 2).grandTotal)
    }

    // ---------- tokenizer misc ----------

    @Test
    fun tabAfterOp() {
        assertAmount("5", TapeEvaluator.evaluate(CalcFile.parse("+ \t5\n").lines, 2).grandTotal)
    }

    @Test
    fun headingLeadingSpaces() {
        val doc = CalcFile.parse("  # Title\n")
        val h = doc.lines.first() as TapeLine.Heading
        assertEquals("Title", h.text)
    }

    @Test
    fun hashAloneIsHeading() {
        assertTrue(CalcFile.parse("#\n").lines.first() is TapeLine.Heading)
    }

    @Test
    fun whitespaceOnlyLineIsComment() {
        // Not empty (has spaces) so not Blank — but harmless either way.
        assertTrue(CalcFile.parse("   \n + 5\n").lines.first() is TapeLine.Comment)
    }

    @Test
    fun noTrailingNewline() {
        assertAmount("5", TapeEvaluator.evaluate(CalcFile.parse(" + 5").lines, 2).grandTotal)
    }

    @Test
    fun badNumberWarns() {
        assertTrue(CalcFile.parse("+ 10.5.2\n").warnings.any { it.contains("bad number") })
    }

    @Test
    fun endashMinus() {
        assertAmount("-5", TapeEvaluator.evaluate(CalcFile.parse("– 5\n").lines, 2).grandTotal)
    }

    @Test
    fun trailingCurrencyKept() {
        val doc = CalcFile.parse("+ 100₹\n")
        val entry = doc.lines.filterIsInstance<TapeLine.Entry>().single()
        assertAmount("100", entry.amount)
        assertEquals("₹", entry.comment)
    }

    // ---------- formatter ----------

    @Test
    fun prettyIdempotent() {
        val once = TapeFormatter.pretty(LEDGER, 5)
        assertEquals(once, TapeFormatter.pretty(once, 5))
    }

    @Test
    fun prettyEmpty() {
        assertEquals("\n", TapeFormatter.pretty("", 2))
    }

    @Test
    fun prettyOnlySeparator() {
        // trimEnd() takes the canonical trailing space; re-parses identically.
        assertEquals(
            " ------------------\n",
            TapeFormatter.pretty("------------------", 2),
        )
    }

    @Test
    fun prettyMixedPassthrough() {
        assertEquals(
            "# T\nhello\n\n+ 1.00\n",
            TapeFormatter.pretty("# T\nhello\n\n+ 1\n", 2),
        )
    }

    @Test
    fun displayPartsDoubleNegation() {
        assertEquals(Pair('+', BigDecimal("5")), TapeFormatter.displayParts('-', BigDecimal("-5")))
    }

    @Test
    fun displayPartsStarUntouched() {
        assertEquals(Pair('*', BigDecimal("-2")), TapeFormatter.displayParts('*', BigDecimal("-2")))
    }

    @Test
    fun groupNumberNoDecimal() {
        assertEquals("1,234,567", TapeFormatter.groupNumber("1234567", Grouping.COMMA))
        assertEquals("12,34,567", TapeFormatter.groupNumber("1234567", Grouping.INDIAN))
    }

    // ---------- cursor-aware editing (TapeEdit) ----------

    @Test
    fun insertDigitAtEnd() {
        assertEquals(" + 57" to 5, TapeEdit.insertToken(" + 5", 4, 4, "7"))
    }

    @Test
    fun insertOpAtEndKeepsLegacy() {
        assertEquals(" + 5\n + " to 8, TapeEdit.insertToken(" + 5", 4, 4, "\n + "))
    }

    @Test
    fun insertOpTrimsTrailingBlank() {
        // No accidental blank section when appending after trailing newline.
        assertEquals(" + 5\n + " to 8, TapeEdit.insertToken(" + 5\n", 5, 5, "\n + "))
    }

    @Test
    fun insertDigitMidTape() {
        assertEquals(" + 15234" to 5, TapeEdit.insertToken(" + 1234", 4, 4, "5"))
    }

    @Test
    fun insertReplacesRange() {
        assertEquals(" + 934" to 4, TapeEdit.insertToken(" + 1234", 3, 5, "9"))
    }

    @Test
    fun insertReversedRangeNormalizes() {
        assertEquals(" + 934" to 4, TapeEdit.insertToken(" + 1234", 5, 3, "9"))
    }

    @Test
    fun insertOpMidLineSplits() {
        // Operator mid-line starts a new entry line, like ABC typing.
        assertEquals(" + 1\n + 234" to 8, TapeEdit.insertToken(" + 1234", 4, 4, "\n + "))
    }

    @Test
    fun extendBareOpWithSign() {
        // `*` then `-` on the open line becomes `*-`, not a new line.
        assertEquals(" + 5\n * -" to 9, TapeEdit.insertToken(" + 5\n * ", 8, 8, "\n - "))
    }

    @Test
    fun replaceBareOp() {
        assertEquals(" / " to 3, TapeEdit.insertToken(" * ", 3, 3, "\n / "))
    }

    @Test
    fun swapSignOnBareOp() {
        assertEquals(" * +" to 4, TapeEdit.insertToken(" * -", 4, 4, "\n + "))
    }

    @Test
    fun insertIntoEmptyTape() {
        assertEquals("5" to 1, TapeEdit.insertToken("", 0, 0, "5"))
    }

    @Test
    fun outOfBoundsClampedToEnd() {
        assertEquals("abx" to 3, TapeEdit.insertToken("ab", 99, 99, "x"))
    }

    @Test
    fun percentAppendsAtEnd() {
        assertEquals(" + 100% " to 8, TapeEdit.insertToken(" + 100", 6, 6, "% "))
    }

    @Test
    fun insertDigitAtZero() {
        assertEquals("9 + 5" to 1, TapeEdit.insertToken(" + 5", 0, 0, "9"))
    }

    @Test
    fun keypadFlowBuildsSignedMult() {
        // Full `* -2` flow through the pure core, then evaluated.
        val (t1, c1) = TapeEdit.insertToken(" + 5\n * ", 8, 8, "\n - ")
        assertEquals(" + 5\n * -" to 9, t1 to c1)
        val (t2, c2) = TapeEdit.insertToken(t1, c1, c1, "2")
        assertEquals(" + 5\n * -2" to 10, t2 to c2)
        assertAmount("-10", TapeEvaluator.evaluate(CalcFile.parse(t2).lines, 2).grandTotal)
    }

    @Test
    fun insertMultilineTokenOverRange() {
        assertEquals("\n + \nc" to 4, TapeEdit.insertToken("a\nb\nc", 0, 3, "\n + "))
    }

    @Test
    fun deleteRange() {
        assertEquals(" + 34" to 3, TapeEdit.deleteAt(" + 1234", 3, 5))
    }

    @Test
    fun deleteBeforeCursor() {
        // Cursor after '2' (offset 5) deletes '2'.
        assertEquals(" + 134" to 4, TapeEdit.deleteAt(" + 1234", 5, 5))
    }

    @Test
    fun deleteAtZeroFallsBackToEnd() {
        // Legacy calculator behavior: ⌫ at document start deletes last char.
        assertEquals(" + " to 3, TapeEdit.deleteAt(" + 5", 0, 0))
    }

    @Test
    fun deleteEmptyIsNoop() {
        assertEquals("" to 0, TapeEdit.deleteAt("", 0, 0))
    }

    @Test
    fun deleteReversedRange() {
        assertEquals(" + 34" to 3, TapeEdit.deleteAt(" + 1234", 5, 3))
    }

    @Test
    fun deleteTrailingNewlineJoins() {
        // Cursor after trailing newline: removes the newline (standard).
        assertEquals(" + 5" to 4, TapeEdit.deleteAt(" + 5\n", 6, 6))
    }

    @Test
    fun deleteWholeContent() {
        assertEquals("" to 0, TapeEdit.deleteAt("ab", 0, 2))
    }

    @Test
    fun deleteClampsNegative() {
        assertEquals("a" to 1, TapeEdit.deleteAt("ab", -5, -1))
    }

    @Test
    fun commaDecimalReadsAsDecimal() {
        // "3,50" is three-fifty, not 350 (was 350 before the fix).
        val eval = TapeEvaluator.evaluate(CalcFile.parse("3,50\n").lines, 2)
        assertTrue(eval.errors.isEmpty())
        assertEquals(BigDecimal("3.50"), eval.grandTotal.setScale(2))
    }

    @Test
    fun germanFullFormatReadsCorrectly() {
        // "1.234,56" via header separators: 1234.56 (was 1.23456: the old
        // order stripped the decimal comma before converting it).
        val text = "<SFRCalculatorHeader>\nDECIMALS=2\nDECSEP=,\nTHOUSEP=.\n</SFRCalculatorHeader>\n1.234,56\n"
        val doc = CalcFile.parse(text)
        assertEquals(',', doc.meta.decSep)
        assertEquals('.', doc.meta.thouSep)
        val eval = TapeEvaluator.evaluate(doc.lines, 2)
        assertTrue(eval.errors.isEmpty())
        assertEquals(BigDecimal("1234.56"), eval.grandTotal.setScale(2))
    }

    @Test
    fun germanGroupingOnlyReadsCorrectly() {
        val text = "<SFRCalculatorHeader>\nDECIMALS=2\nDECSEP=,\nTHOUSEP=.\n</SFRCalculatorHeader>\n1.234\n"
        val eval = TapeEvaluator.evaluate(CalcFile.parse(text).lines, 2)
        assertAmount("1234", eval.grandTotal)
    }

    @Test
    fun thousandGroupingStaysGrouping() {
        // "1,000" (3 trailing digits) is grouping, not decimal.
        val eval = TapeEvaluator.evaluate(CalcFile.parse("1,000\n").lines, 2)
        assertEquals(0, BigDecimal("1000").compareTo(eval.grandTotal))
    }

    @Test
    fun indianGroupingStaysGrouping() {
        val eval = TapeEvaluator.evaluate(CalcFile.parse("10,00,000\n").lines, 2)
        assertEquals(0, BigDecimal("1000000").compareTo(eval.grandTotal))
    }

    @Test
    fun ambiguousShortCommaReadsDecimal() {
        // Documented trade-off: "1,00" reads as 1.00 (European decimal),
        // not 100 (Indian shorthand).
        val eval = TapeEvaluator.evaluate(CalcFile.parse("1,00\n").lines, 2)
        assertEquals(BigDecimal("1.00"), eval.grandTotal.setScale(2))
    }

    @Test
    fun unicodeOperatorsParse() {
        // × ÷ − from other keyboards behave like * / -.
        val eval = TapeEvaluator.evaluate(CalcFile.parse(" + 100\n × 2\n").lines, 2)
        assertTrue(eval.errors.isEmpty())
        assertAmount("200", eval.grandTotal)
        val eval2 = TapeEvaluator.evaluate(CalcFile.parse(" − 5\n").lines, 2)
        assertAmount("-5", eval2.grandTotal)
        val eval3 = TapeEvaluator.evaluate(CalcFile.parse(" + 100\n ÷ 4\n").lines, 2)
        assertAmount("25", eval3.grandTotal)
    }

    @Test
    fun currencySymbolsAreDroppedForMath() {
        // Symbols don't count; the number does. Symbol itself is not kept.
        val doc = CalcFile.parse(" + ₹500 lunch\n")
        assertTrue(doc.warnings.isEmpty())
        val entry = doc.lines.filterIsInstance<TapeLine.Entry>().single()
        assertAmount("500", entry.amount)
        assertEquals("lunch", entry.comment)
        val bare = TapeEvaluator.evaluate(CalcFile.parse("₹500\n").lines, 2)
        assertAmount("500", bare.grandTotal)
    }

    @Test
    fun devanagariDigitsCompute() {
        // १२ = 12.
        val eval = TapeEvaluator.evaluate(CalcFile.parse("१२\n").lines, 2)
        assertTrue(eval.errors.isEmpty())
        assertAmount("12", eval.grandTotal)
    }

    @Test
    fun arabicIndicDigitsCompute() {
        // ١٢٣ = 123.
        val eval = TapeEvaluator.evaluate(CalcFile.parse("١٢٣\n").lines, 2)
        assertAmount("123", eval.grandTotal)
    }

    @Test
    fun indicDigitsInCommentsStayVerbatim() {
        // Comment script is preserved byte-identically; only the number folds.
        val doc = CalcFile.parse(" + 100 meeting १२\n")
        val entry = doc.lines.filterIsInstance<TapeLine.Entry>().single()
        assertAmount("100", entry.amount)
        assertEquals("meeting १२", entry.comment)
    }

    @Test
    fun leadingDotDecimal() {
        val eval = TapeEvaluator.evaluate(CalcFile.parse(" + .5\n").lines, 2)
        assertTrue(eval.errors.isEmpty())
        assertAmount("0.5", eval.grandTotal)
        val bare = TapeEvaluator.evaluate(CalcFile.parse(".5 lunch\n").lines, 2)
        assertAmount("0.5", bare.grandTotal)
    }

    @Test
    fun scientificNotation() {
        val eval = TapeEvaluator.evaluate(CalcFile.parse("1e5\n").lines, 2)
        assertEquals(0, BigDecimal("100000").compareTo(eval.grandTotal))
        val eval2 = TapeEvaluator.evaluate(CalcFile.parse(" + 2E+3\n").lines, 2)
        assertEquals(0, BigDecimal("2000").compareTo(eval2.grandTotal))
    }

    @Test
    fun scientificLookalikeStaysComment() {
        // "5 eggs": the 'e' needs digits after it to count as an exponent.
        val doc = CalcFile.parse("5 eggs\n")
        val entry = doc.lines.filterIsInstance<TapeLine.Entry>().single()
        assertAmount("5", entry.amount)
        assertEquals("eggs", entry.comment)
    }

    @Test
    fun spacedSignNumber() {
        // "+ - 2" (sign separated by space) still means -2.
        val eval = TapeEvaluator.evaluate(CalcFile.parse("+ - 2\n").lines, 2)
        assertAmount("-2", eval.grandTotal)
    }

    @Test
    fun sectionTotalsListedInOrder() {
        // Two blank-separated calculations: per-section totals + grand sum.
        // (No trailing newline here so no trailing empty section is banked;
        // real tapes end with one, which the strip mapping skips over.)
        val eval = TapeEvaluator.evaluate(CalcFile.parse(" + 5\n\n + 7").lines, 2)
        assertEquals(2, eval.sectionTotals.size)
        assertAmount("5", eval.sectionTotals[0])
        assertAmount("7", eval.sectionTotals[1])
        assertAmount("12", eval.grandTotal)
    }

    @Test
    fun threeSections() {
        val eval = TapeEvaluator.evaluate(CalcFile.parse(" + 1\n\n + 2\n\n + 3").lines, 2)
        assertEquals(listOf("1", "2", "3"),
            eval.sectionTotals.map { it.stripTrailingZeros().toPlainString() })
        assertAmount("6", eval.grandTotal)
    }

    @Test
    fun sectionIndexForLine() {
        // Lines: 0 Entry, 1 Blank, 2 Entry(+trailing blank line 3).
        val lines = CalcFile.parse(" + 5\n\n + 7\n").lines
        assertEquals(0, TapeEvaluator.sectionIndexForLine(lines, 0))
        assertEquals(0, TapeEvaluator.sectionIndexForLine(lines, 1))
        assertEquals(1, TapeEvaluator.sectionIndexForLine(lines, 2))
        assertEquals(1, TapeEvaluator.sectionIndexForLine(lines, 3))
        assertEquals(0, TapeEvaluator.sectionIndexForLine(emptyList(), 99))
        assertEquals(1, TapeEvaluator.sectionIndexForLine(lines, 999))
    }

    @Test
    fun singleSectionUnchanged() {
        // No blanks: one section equal to the grand total.
        val eval = TapeEvaluator.evaluate(CalcFile.parse(" + 5\n + 7").lines, 2)
        assertEquals(1, eval.sectionTotals.size)
        assertAmount("12", eval.sectionTotals[0])
    }

    @Test
    fun freshSectionAppendsBlankLine() {
        assertEquals(" + 1\n\n", TapeEdit.openFreshSection(" + 1"))
        assertEquals(" + 1\n\n", TapeEdit.openFreshSection(" + 1\n"))
    }

    @Test
    fun freshSectionIsIdempotent() {
        // Already on a fresh section: returned unchanged (repeat `=` = no-op).
        assertEquals(" + 1\n\n", TapeEdit.openFreshSection(" + 1\n\n"))
    }

    @Test
    fun freshSectionCollapsesExtraBlanks() {
        assertEquals(" + 1\n\n", TapeEdit.openFreshSection(" + 1\n\n\n"))
    }

    @Test
    fun digitAfterSectionDividerStaysInFreshSection() {
        // `=` leaves "…bal\n\n"; the next digit must NOT glue onto the balance.
        assertEquals(" + 150\n\n3" to 9, TapeEdit.insertToken(" + 150\n\n", 8, 8, "3"))
    }

    @Test
    fun operatorAfterSectionDividerKeepsBlank() {
        assertEquals(" + 150\n\n + " to 11, TapeEdit.insertToken(" + 150\n\n", 8, 8, "\n + "))
    }

    @Test
    fun legacyTrailingSpaceStillTrims() {
        // No divider: trailing whitespace is stripped, digit joins the line.
        assertEquals(" + 53" to 5, TapeEdit.insertToken(" + 5\n ", 6, 6, "3"))
    }

    @Test
    fun singleTrailingNewlineStillTrims() {
        assertEquals(" + 53" to 5, TapeEdit.insertToken(" + 5\n", 5, 5, "3"))
    }

    @Test
    fun endToEndTwoSectionsViaEqualsShape() {
        // Tape exactly as `=` leaves it, plus the next typed entry:
        // sections split 150 | 3, grand 153.
        val text = " + 100\n + 50\n ------------------ \n + 150\n\n3"
        val eval = TapeEvaluator.evaluate(CalcFile.parse(text).lines, 2)
        assertEquals(2, eval.sectionTotals.size)
        assertAmount("150", eval.sectionTotals[0])
        assertAmount("3", eval.sectionTotals[1])
        assertAmount("153", eval.grandTotal)
    }

    @Test
    fun digitAfterClosedBlockStartsNewSection() {
        // Classic chaining: a fresh number after `=` starts a new section.
        val closed = " + 100\n + 50\n ------------------ \n + 150"
        val (next, cursor) = TapeEdit.insertToken(closed, closed.length, closed.length, "3")
        assertEquals("$closed\n\n3", next)
        assertEquals(next.length, cursor)
        val eval = TapeEvaluator.evaluate(CalcFile.parse(next).lines, 2)
        assertEquals(2, eval.sectionTotals.size)
        assertAmount("150", eval.sectionTotals[0])
        assertAmount("3", eval.sectionTotals[1])
    }

    @Test
    fun digitAfterBareSeparatorStartsNewSection() {
        // A lone `=` (ABC) with no balance yet: numbers still start fresh.
        val closed = " + 100\n="
        val (next, _) = TapeEdit.insertToken(closed, closed.length, closed.length, "3")
        assertEquals("$closed\n\n3", next)
    }

    @Test
    fun operatorAfterClosedBlockChainsSameSection() {
        // `100 + 50 = * 2` stays one calculation: 300, single section.
        val closed = " + 100\n + 50\n ------------------ \n + 150"
        val (t1, c1) = TapeEdit.insertToken(closed, closed.length, closed.length, "\n * ")
        assertEquals("$closed\n * ", t1)
        val (t2, _) = TapeEdit.insertToken(t1, c1, c1, "2")
        val eval = TapeEvaluator.evaluate(CalcFile.parse(t2).lines, 2)
        assertEquals(1, eval.sectionTotals.size)
        assertAmount("300", eval.grandTotal)
    }

    @Test
    fun digitAfterOpenEntryStillAppends() {
        // Mid-calculation typing is untouched: glues onto the open line.
        assertEquals(" + 53" to 5, TapeEdit.insertToken(" + 5", 4, 4, "3"))
    }

    // ---------- BODMAS: precedence across tape lines ----------
    //
    // A tape line holds ONE operator + amount, so precedence plays out across
    // lines: `* / ^` lines fold into the open multiplicative chain (binding
    // tighter than `+ -`), applied left-to-right onto the running value —
    // tape semantics, verified line by line below.

    private fun bodmasTotal(tape: String): BigDecimal =
        TapeEvaluator.evaluate(CalcFile.parse(tape).lines, 2).grandTotal

    @Test
    fun bodmasMultBindsBeforeAdd() {
        // 10 + (2*3) = 16, NOT (10+2)*3 = 36.
        assertAmount("16", bodmasTotal(" + 10\n + 2\n * 3\n"))
    }

    @Test
    fun bodmasDivBindsBeforeSubtract() {
        // (100/4) - 5 = 25 - 5 = 20.
        assertAmount("20", bodmasTotal(" + 100\n / 4\n - 5\n"))
    }

    @Test
    fun bodmasSubtractThenMultiply() {
        // 100 - (10*3) = 70, NOT (100-10)*3 = 270.
        assertAmount("70", bodmasTotal(" + 100\n - 10\n * 3\n"))
    }

    @Test
    fun bodmasMultiplyThenSubtract() {
        // (100*3) - 10 = 290.
        assertAmount("290", bodmasTotal(" + 100\n * 3\n - 10\n"))
    }

    @Test
    fun bodmasTwoMultRuns() {
        // (2*3) + (4*5) = 6 + 20 = 26.
        assertAmount("26", bodmasTotal(" + 2\n * 3\n + 4\n * 5\n"))
    }

    @Test
    fun bodmasAddSubLeftToRight() {
        // Same level goes top-to-bottom: 100-30+5-2 = 73.
        assertAmount("73", bodmasTotal(" + 100\n - 30\n + 5\n - 2\n"))
    }

    @Test
    fun bodmasDivChainLeftAssoc() {
        // ((120/2)/3)/4 = 5, left-associative.
        assertAmount("5", bodmasTotal(" + 120\n / 2\n / 3\n / 4\n"))
    }

    @Test
    fun bodmasPowBindsBeforeAdd() {
        // (3^2) + 1 = 10.
        assertAmount("10", bodmasTotal(" + 3\n ^ 2\n + 1\n"))
    }

    @Test
    fun bodmasPowAppliesToOpenChain() {
        // Tape semantics: ^ applies to the running chain, so (2*3)^2 = 36
        // (textbook 2*3^2 = 18 does NOT apply across tape lines).
        assertAmount("36", bodmasTotal(" + 2\n * 3\n ^ 2\n"))
    }

    @Test
    fun bodmasPowChainLeftAssoc() {
        // (2^3)^2 = 64, NOT 2^(3^2) = 512.
        assertAmount("64", bodmasTotal(" + 2\n ^ 3\n ^ 2\n"))
    }

    @Test
    fun bodmasPowZeroResetsChainToOne() {
        // x^0 = 1: the open chain becomes 1.
        assertAmount("1", bodmasTotal(" + 5\n ^ 0\n"))
    }

    @Test
    fun bodmasPowOneKeepsValue() {
        assertAmount("7", bodmasTotal(" + 7\n ^ 1\n"))
    }

    @Test
    fun bodmasNegativeBaseEvenPow() {
        assertAmount("4", bodmasTotal(" - 2\n ^ 2\n"))
    }

    @Test
    fun bodmasNegativeBaseOddPow() {
        assertAmount("-8", bodmasTotal(" - 2\n ^ 3\n"))
    }

    @Test
    fun bodmasMultiplyByNegative() {
        assertAmount("-10", bodmasTotal(" + 5\n * -2\n"))
    }

    @Test
    fun bodmasDivideByNegative() {
        assertAmount("-25", bodmasTotal(" + 100\n / -4\n"))
    }

    @Test
    fun bodmasLeadingNegativeLine() {
        assertAmount("-15", bodmasTotal(" - 25\n + 10\n"))
    }

    @Test
    fun bodmasZeroKillsChainButNotTape() {
        // (5*0) + 3 = 3: zeroed chain, later lines still count.
        assertAmount("3", bodmasTotal(" + 5\n * 0\n + 3\n"))
    }

    @Test
    fun bodmasTenthsAreExact() {
        // BigDecimal: 0.1 + 0.2 = 0.3 exactly (no binary-float drift).
        assertAmount("0.3", bodmasTotal(" + 0.1\n + 0.2\n"))
    }

    @Test
    fun bodmasMoneyCentsExact() {
        assertAmount("59.97", bodmasTotal(" + 19.99\n * 3\n"))
    }

    @Test
    fun bodmasThirdStaysPrecise() {
        // 1/3 kept at 34-digit precision: *3 drifts less than 1e-30.
        val eval = TapeEvaluator.evaluate(CalcFile.parse(" + 1\n / 3\n").lines, 2)
        assertTrue(eval.errors.isEmpty())
        val drift = BigDecimal.ONE.subtract(eval.grandTotal.multiply(BigDecimal(3))).abs()
        assertTrue(drift.compareTo(BigDecimal("0.000000000000000000000000000001")) < 0)
    }

    @Test
    fun bodmasPercentAfterMultRun() {
        // (200*2) + 10% of 400 = 440.
        assertAmount("440", bodmasTotal(" + 200\n * 2\n + 10%\n"))
    }

    @Test
    fun bodmasDiscountThenTax() {
        // 100 - 10% (=90) + 5% of 90 (=4.5) = 94.5.
        assertAmount("94.5", bodmasTotal(" + 100\n - 10%\n + 5%\n"))
    }

    @Test
    fun bodmasPrecedenceOnChainedBase() {
        // After a 150 subtotal: 150 + (10*2) = 170, NOT (150+10)*2 = 320.
        assertAmount("170", bodmasTotal(" + 150\n------------------\n + 150\n + 10\n * 2\n"))
    }

    @Test
    fun bodmasLeadingMultOnChainedBase() {
        // After a 150 subtotal, `* 2` chains: 150 * 2 = 300.
        assertAmount("300", bodmasTotal(" + 150\n------------------\n + 150\n * 2\n"))
    }

    @Test
    fun bodmasEqualsCapturesMultSubtotal() {
        // Genuine post-`=` shape: dashes, restated 6, then a fresh +4 line
        // AFTER the total row (fresh input never sits directly under the
        // dashes — the keypad always creates its own line): grand 10.
        val eval = TapeEvaluator.evaluate(CalcFile.parse(" + 2\n * 3\n------------------\n + 6\n + 4\n").lines, 2)
        assertEquals(1, eval.subtotals.size)
        assertAmount("6", eval.subtotals[0])
        assertAmount("10", eval.grandTotal)
    }

    @Test
    fun bodmasSectionsAreIndependent() {
        // Blank resets the chain: 2*3=6 and 10*5=50 stay separate.
        val eval = TapeEvaluator.evaluate(CalcFile.parse(" + 2\n * 3\n\n + 10\n * 5").lines, 2)
        assertEquals(2, eval.sectionTotals.size)
        assertAmount("6", eval.sectionTotals[0])
        assertAmount("50", eval.sectionTotals[1])
        assertAmount("56", eval.grandTotal)
    }

    @Test
    fun bodmasDivZeroFirstLineIsError() {
        val eval = TapeEvaluator.evaluate(CalcFile.parse(" / 0\n").lines, 2)
        assertTrue(eval.errors.any { it.contains("division by zero") })
        assertAmount("0", eval.grandTotal)
    }

    @Test
    fun bodmasHugeExponentKeepsTotal() {
        // Rejected power records an error; the open chain (2) is kept.
        val eval = TapeEvaluator.evaluate(CalcFile.parse(" + 2\n ^ 1001\n").lines, 2)
        assertTrue(eval.errors.any { it.contains("exponent too large") })
        assertAmount("2", eval.grandTotal)
    }

    @Test
    fun bodmasProseAfterOpIsSilentZero() {
        // `+ abc`: no number to take — neutral 0 with the text as comment.
        val doc = CalcFile.parse(" + 10\n + abc\n + 5\n")
        val eval = TapeEvaluator.evaluate(doc.lines, 2)
        assertTrue(eval.errors.isEmpty())
        assertAmount("15", eval.grandTotal)
    }

    @Test
    fun bodmasBracketsStayOutOfMath() {
        // Brackets are NOT calculated: hint shown, total untouched.
        val doc = CalcFile.parse("(2 + 3) * 4\n")
        assertTrue(doc.warnings.any { it.contains("brackets") })
        val eval = TapeEvaluator.evaluate(doc.lines, 2)
        assertTrue(eval.errors.isEmpty())
        assertAmount("0", eval.grandTotal)
    }

    // ---------- stale balances heal (hisab 45→50 regression) ----------
    //
    // Real-world failure: editing 45→50 mid-note exploded the total past
    // 150,000 on every keystroke (log: 555 → 14520 → … → 155370), because
    // stale subtotal rows were re-added as fresh input — and `=` cemented
    // whichever inflated value was live (a 256−8 block saved as 919).
    // Subtotal rows are now always recomputed restatements, never added.

    @Test
    fun staleBalanceAfterEditNeverAdds() {
        // 45-world subtotal left behind after the entry became 50: ignored.
        val eval = TapeEvaluator.evaluate(CalcFile.parse(" + 50\n + 10\n------------------\n + 55\n").lines, 2)
        assertTrue(eval.errors.isEmpty())
        assertAmount("60", eval.grandTotal)
    }

    @Test
    fun editedNoteKeepsTrueTotals() {
        // hisab (2).calc shape: 0-world entries under committed balances.
        // True chain: 0+119+292+0=411, −155→256, −8→248 (file said 919).
        val tape = " + 0\n + 119\n + 292\n + 0\n------------------\n + 411\n" +
            " - 155\n------------------\n + 256\n - 8\n------------------\n + 919\n"
        val eval = TapeEvaluator.evaluate(CalcFile.parse(tape).lines, 2)
        assertEquals(
            listOf("411", "256", "248"),
            eval.subtotals.map { it.setScale(0).toPlainString() },
        )
        assertAmount("248", eval.grandTotal)
        assertAmount("248", eval.balanceTotals[11] ?: BigDecimal(-1))
    }

    @Test
    fun midEditTotalsGlideDontSpike() {
        // Same calculation at three edit states (45 → 0 → 50) with the
        // originally committed balances: true values throughout, no spike.
        val balances = "\n------------------\n + 456\n - 155\n------------------\n + 301\n - 8\n------------------\n + 293\n"
        assertAmount("293", bodmasTotal(" + 45\n + 119\n + 292\n + 0$balances"))
        assertAmount("248", bodmasTotal(" + 0\n + 119\n + 292\n + 0$balances"))
        assertAmount("298", bodmasTotal(" + 50\n + 119\n + 292\n + 0$balances"))
    }

    @Test
    fun prettyRecomputesStaleBalances() {
        // `=` (pretty) writes the recomputed total, never cements stale text.
        val out = TapeFormatter.pretty(" + 50\n + 10\n------------------\n + 55\n", 2)
        assertTrue("out=$out", out.contains("+ 60.00"))
        assertFalse("out=$out", out.contains("55"))
        assertAmount("60", bodmasTotal(out))
    }

    @Test
    fun typedEntryAfterBalanceRowStillAdds() {
        // A genuine new line after the total row counts normally.
        assertAmount("120", bodmasTotal(" + 100\n------------------\n + 100\n + 20\n"))
    }

    // ---------- scroll gate: mid-tape edits keep their place ----------

    @Test
    fun caretOnLastLineEnd() {
        assertTrue(TapeEdit.caretOnLastLine("aaa\nbbb\nccc", 11))
    }

    @Test
    fun caretOnLastLineEmptyFinalLine() {
        // Caret parked on the fresh line after a trailing newline.
        assertTrue(TapeEdit.caretOnLastLine("aaa\nbbb\n", 8))
    }

    @Test
    fun caretMidTapeNoFollow() {
        // Editing line 2 of 3: the view must stay put.
        assertFalse(TapeEdit.caretOnLastLine("aaa\nbbb\nccc", 5))
        assertFalse(TapeEdit.caretOnLastLine("aaa\nbbb\nccc", 0))
    }

    @Test
    fun caretSingleLineAlwaysFollows() {
        assertTrue(TapeEdit.caretOnLastLine("abc", 1))
    }

    @Test
    fun caretClampedInside() {
        assertTrue(TapeEdit.caretOnLastLine("aaa\nbbb", 99))
        assertFalse(TapeEdit.caretOnLastLine("aaa\nbbb", -5))
    }
}
