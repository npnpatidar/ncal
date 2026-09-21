# ncal

Notepad-tape calculator for Android (CalcTape-style), built with Kotlin + Jetpack Compose.
Type lines like `+ 119.00 bob`, close a block with `=`, edit anything — everything recalculates.

## Build (GitHub only)

No local Gradle/SDK needed. Push and GitHub Actions builds the debug APK:

- Workflow: `.github/workflows/build-apk.yml` (JDK 17, Gradle 8.7, `assembleDebug` + unit tests)
- Artifacts: `ncal-debug-apk` (`app-debug.apk`), `ncal-unit-test-report`

## Logs (debugging)

Exhaustive logging → on-device `Download/ncal/ncal-YYYY-MM-DD.log` (same folder as exports),
plus logcat tag `NCAL/*`:

- Browse on-device: Files app → Downloads → ncal
- Pull: `adb pull /sdcard/Download/ncal`
- Filter: `adb logcat -s NCAL:*`

Logged: lifecycle, every keypress/edit with tape snapshot size, per-block evaluation
(running total, `%` resolutions, rounding), all errors with line numbers,
export/import (UUID, decimals, URIs), permission/storage outcomes.

## `.calc` compatibility

Byte-compatible with CalcTape plain-text exports (verified against a real export:
9/9 chained subtotals recompute exactly):

- Entry: `" " + op + amount.padStart(17) + " " + comment` (e.g. ` +        456.00000 `)
- Separator: ` ------------------ ` (18 dashes)
- `+X` right after a separator is a **balance restatement** (display-only, never re-added)
- Blank line = independent calculation (grand total = sum of sections)
- Header keys in fixed order (`DECIMALS/DECSEP/THOUSEP/UUID/...`); BOM stripped on import,
  never written; writer omits thousands grouping (always parseable)
- `* / ^` bind tighter than `+ -` across lines (`+10, +2, *3` = 16); `%` = % of running
  subtotal; exact BigDecimal math, commercial HALF_UP rounding for display only

## Project layout

```
app/src/main/java/com/npnpatidar/ncal/
  MainActivity.kt          launcher, logger init
  tape/TapeModel.kt        Entry/Separator/Balance/Blank/Heading/Comment + CalcMeta
  tape/TapeEvaluator.kt    chain evaluator (BigDecimal, precedence, %)
  tape/CalcFile.kt         .calc reader/writer (exact line shapes)
  storage/MediaStoreHelper.kt  Download/ncal via MediaStore (scoped-storage safe)
  logging/NcalLogger.kt    logcat + file + ring buffer
  export/CalcExport.kt     .calc/.txt export, header-optional import
  ui/TapeViewModel.kt      state, keypad ops, memory, undo/redo, import/export
  ui/TapeScreen.kt         tape editor + result panel + keypad
app/src/test/.../TapeEngineTest.kt  11 unit tests (run in CI)
```

## Status

v1 = basic tape clone: `+ - * / ^ %`, comments, `#` headings, subtotals, grand total,
memory keys, undo/redo, day/night, `.calc`/`.txt` export. Not yet: variables, brackets,
custom VAT keys, PDF.
