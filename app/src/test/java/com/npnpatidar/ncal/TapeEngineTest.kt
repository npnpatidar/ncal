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
        // `+ 3` after the separator mismatches the running total, so it is
        // fresh input (a normal entry), not a restatement: grand becomes 8.
        assertEquals(BigDecimal("8.00"), eval.grandTotal.setScale(2))
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
        assertEquals(" * +" to 5, TapeEdit.insertToken(" * -", 4, 4, "\n + "))
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
        assertEquals(" + 134" to 3, TapeEdit.deleteAt(" + 1234", 4, 4))
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
        assertEquals(" + 5" to 5, TapeEdit.deleteAt(" + 5\n", 6, 6))
    }

    @Test
    fun deleteWholeContent() {
        assertEquals("" to 0, TapeEdit.deleteAt("ab", 0, 2))
    }

    @Test
    fun deleteClampsNegative() {
        assertEquals("a" to 1, TapeEdit.deleteAt("ab", -5, -1))
    }
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
}
