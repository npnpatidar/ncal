package com.npnpatidar.ncal.tape

/**
 * Cursor-aware keypad editing, pure and unit-tested. The ViewModel delegates
 * here so the exact caret behavior is pinned by tests, not by device trial.
 *
 * Rules:
 * - Digits/text insert at the cursor (a ranged selection is replaced).
 * - An operator key (`"\n + "` shape) on an open operator line (` * `)
 *   extends it (`-`/`+` become the operand sign: `*-`) or replaces it
 *   (`*`/`/`/`^`), instead of stranding a second line.
 * - Appending at the very end trims trailing whitespace first (legacy tidy
 *   behavior: operator tokens must never create accidental blank sections).
 * - Backspace deletes the selection, else the char before the cursor; a
 *   collapsed cursor at 0 falls back to legacy end-deletion so ⌫ always
 *   does something.
 */
object TapeEdit {

    /** Returns (newText, newCursorOffset). */
    fun insertToken(text: String, from: Int, to: Int, token: String): Pair<String, Int> {
        val a = from.coerceIn(0, text.length)
        val b = to.coerceIn(0, text.length)
        val (s, e) = if (a <= b) a to b else b to a
        val opChar = if (token.startsWith("\n")) {
            token.trim().firstOrNull()?.takeIf {
                it == '+' || it == '-' || it == '*' || it == '/' || it == '^'
            }
        } else {
            null
        }
        if (opChar != null && s == e) {
            val ls = if (s <= 0) -1 else text.lastIndexOf('\n', s - 1)
            val lineStart = if (ls < 0) 0 else ls + 1
            val le = text.indexOf('\n', s).let { if (it < 0) text.length else it }
            val curLine = text.substring(lineStart, le)
            if (CalcFile.isBareOpLine(curLine)) {
                val firstOp = curLine.trim()[0]
                val newLast = if (opChar == '+' || opChar == '-') " $firstOp $opChar" else " $opChar "
                val next = text.substring(0, lineStart) + newLast + text.substring(le)
                return next to (lineStart + newLast.length)
            }
        }
        if (s == e && text.substring(s).isBlank()) {
            val trimmed = text.trimEnd()
            val next = trimmed + token
            return next to next.length
        }
        val next = text.substring(0, s) + token + text.substring(e)
        return next to (s + token.length)
    }

    /** Returns (newText, newCursorOffset); callers skip work when text is unchanged. */
    fun deleteAt(text: String, start: Int, end: Int): Pair<String, Int> {
        if (text.isEmpty()) return text to 0
        val s = start.coerceIn(0, text.length)
        val e = end.coerceIn(0, text.length)
        val (a, b) = if (s <= e) s to e else e to s
        if (a != b) {
            return text.removeRange(a, b) to a
        }
        if (a > 0) {
            return text.removeRange(a - 1, a) to (a - 1)
        }
        // Collapsed at 0: legacy end-deletion (calculator ⌫ always acts).
        val next = text.trimEnd().dropLast(1)
        return next to next.length
    }
}
