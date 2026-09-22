package com.npnpatidar.ncal

import com.npnpatidar.ncal.tape.CalcFile
import com.npnpatidar.ncal.tape.CalcMeta
import com.npnpatidar.ncal.tape.Grouping
import com.npnpatidar.ncal.tape.TapeEvaluator
import com.npnpatidar.ncal.tape.TapeFormatter
import com.npnpatidar.ncal.tape.TapeLine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigDecimal

/**
 * Engine tests. The anonymized [LEDGER] fixture mirrors the structure of a real
 * CalcTape export (chained blocks, separator + balance restatement) without
 * copying personal data into this public repo.
 */
class TapeEngineTest {

    private val meta = CalcMeta(decimals = 5)

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
        assertEquals(BigDecimal("16"), eval.grandTotal.stripTrailingZeros())
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
        assertEquals(BigDecimal("105"), eval.grandTotal.stripTrailingZeros())
    }

    @Test
    fun textLedDateStaysComment() {
        val doc = CalcFile.parse("trip 2026-09-21\n + 1\n")
        val eval = TapeEvaluator.evaluate(doc.lines, 2)
        assertEquals(BigDecimal("1"), eval.grandTotal.stripTrailingZeros())
    }

    @Test
    fun loneOperatorLineIsSilent() {        // Mid-typing ` + ` must not raise (or log) an error.
        val doc = CalcFile.parse(" + \n + 5\n")
        assertTrue(doc.warnings.isEmpty())
        val eval = TapeEvaluator.evaluate(doc.lines, 2)
        assertTrue(eval.errors.isEmpty())
        assertEquals(BigDecimal("5"), eval.grandTotal.stripTrailingZeros())
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
        assertEquals(BigDecimal("8"), eval.grandTotal.stripTrailingZeros())
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
}
