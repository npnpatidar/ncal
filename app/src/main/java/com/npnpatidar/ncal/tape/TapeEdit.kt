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
 *   behavior: operator tokens must never create accidental blank sections) —
 *   except a trailing blank-line divider, which is preserved so typing after
 *   a section break starts a fresh section; and a fresh number typed right
 *   after a closed block (separator/balance) opens a new section, while an
 *   operator continues the chain.
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
            val gap = if (trimmed.length < text.length) text.substring(trimmed.length) else ""
            if (gap.contains("\n\n")) {
                // A trailing blank line is a section divider (`=` opens one):
                // typing the next key must land INSIDE the fresh section, not
                // glue onto the closed block above it.
                val next = "$trimmed\n\n" + token.dropWhile { it == '\n' }
                return next to next.length
            }
            if (opChar == null && token.isNotBlank()) {
                // Fresh number right after a closed block starts a new section
                // (classic chaining: operators continue the chain, numbers
                // start fresh). Entries mid-block keep the legacy behavior.
                val last = CalcFile.parse(trimmed).lines.lastOrNull { it !is TapeLine.Blank }
                if (last is TapeLine.Separator || last is TapeLine.Balance) {
                    val next = "$trimmed\n\n$token"
                    return next to next.length
                }
            }
            // Plain trailing space or a single newline keeps the legacy tidy
            // behavior (operator tokens must never create blank sections).
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

    /** `=` as Enter: ensure the note ends with exactly one blank line, so the
     *  next entry starts a fresh calculation section. Idempotent. */
    fun openFreshSection(text: String): String {
        val t = text.trimEnd()
        if (t.endsWith("\n\n")) return text
        return "$t\n\n"
    }

    /** Auto-scroll gate: the tape follows typing only while the caret sits
     *  on the last line — edits anywhere else must never yank the view down. */
    fun caretOnLastLine(text: String, caret: Int): Boolean {
        val c = caret.coerceIn(0, text.length)
        return c > text.lastIndexOf('\n')
    }
}
