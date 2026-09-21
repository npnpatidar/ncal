package com.npnpatidar.ncal

import com.npnpatidar.ncal.tape.CalcFile
import com.npnpatidar.ncal.tape.CalcMeta
import com.npnpatidar.ncal.tape.TapeEvaluator
import com.npnpatidar.ncal.tape.TapeLine
import org.junit.Assert.assertEquals
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
}
