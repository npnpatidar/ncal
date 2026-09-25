# ncal Code Audit

**Audit date:** 2026-09-24  
**Revision:** `e22a9a579b69d180ba3939029f00852f4bf7a429`  
**Scope:** functionality-first review of tracked Kotlin source, tests, manifest, Gradle configuration, CI workflow, repository hygiene, and README claims. Release/deployment controls are tracked only in the deferred appendix.
**Remediation verification:** current uncommitted working tree based on `e22a9a579b69d180ba3939029f00852f4bf7a429`, reviewed after the latest `audit_response.md` implementation. The ninth-round section is the current functional source of truth.

## Ninth-round functional verification

### Verdict

**Functionality is implementation-complete; no High code defect remains.** Release/deployment and device-only acceptance remain deferred. Among the 34 in-scope functional findings, the working tree has **24 fixed, 10 partially fixed, and 0 regressed**.

Device-only acceptance is now classified as **deferred-blocked**, not as an open defect against the code: this environment has no adb, emulator binary, AVDs, or instrumentation runner, so device replay cannot be produced here. Journal unit coverage plus the documented durability guarantee closes the H-05 implementation; only device replay acceptance is deferred.

The ninth implementation centralizes journal replay decisions, makes export precedence explicit and tested, restarts the daily log cap from on-disk size, and expands the suite to 243 JVM tests. Fresh debug validation passes. Remaining functional work is writer-metadata and transaction precedence details, logger aggregate retention, and deferred device-level UI/storage verification.

### Independent functional validation

- Source test inventory: **227 engine, 5 tombstone, and 11 journal tests, totaling 243**.
- Forced fresh JVM execution with `--rerun-tasks`: **227 engine, 5 tombstone, and 11 journal tests, with 0 skipped, 0 failures, and 0 errors**.
- `:app:lintDebug :app:assembleDebug`: **BUILD SUCCESSFUL** with the documented AAPT2 override; debug lint reports **0 errors, 12 warnings**.
- Fresh debug APK hash: `14925d7d927c008a9e0fb1995f0b8808e9cbed25c9d9f072f253dce2b4c3664d`.
- No release tasks were exercised in this round because release/deployment remains deferred.
- No Android instrumentation, API-level device-matrix, process-death, or minified-runtime test exists.

### Current functional status

| ID | Current severity | Status | Ninth-round result |
|---|---|---|---|
| H-01 | High | **Fixed** | Centralized, UTF-8, aggregate, direct-value, TXT, and derived-total budgets remain enforced. |
| H-02 | High | **Fixed** | Atomic replacement, generations, failure surfacing, deletion stabilization, and tombstone checks remain intact. |
| H-03 | High | **Fixed** | Startup, select, create, and active-delete replacement commits use generation guards. |
| H-04 | High | **Fixed** | Diagnostic gating, redaction, bounds, immutable gates, and unique crash names remain intact. |
| H-05 | High | **Fixed (implementation); device acceptance deferred-blocked** | Replay planning, generation/tombstone handling, startup reconcile, and journal unit coverage are implemented. Process-death replay on a device cannot be produced in this environment and is deferred acceptance, not an open code defect. |
| H-06 | High | **Fixed** | Pending-row cleanup is reachable and current MediaStore operations fail closed; device fault-injection remains under functional test gaps. |
| H-07 | High | **Fixed** | Separator authority, fallback/header/caret bounds, and negative grouping round trips pass. |
| H-08 | High | **Fixed** | Leading chains remain corrected. |
| M-01 | Medium | **Fixed** | The legacy list overload is removed; production callers/tests use document-index totals. |
| M-02 | Medium | **Fixed** | Date suffixes, Unicode formulas, malformed exponents/grouping/headers, and unsafe separators are rejected while preserved as comments. |
| M-03 | Medium | **Fixed** | Complete/incomplete/one-separator/UUID/caret header validation is implemented. |
| M-04 | Medium | **Fixed** | Exact/reduced rational powers and verified roots remain correct. |
| M-05 | Medium | **Fixed** | `.calc` and `.txt` block evaluator errors and blocking parser warnings. |
| M-06 | Medium | **Partial** | Normal UUID/active-separator/header/caret round trips pass; direct unsafe writer metadata and remaining identity precedence remain incomplete. |
| M-07 | Medium | **Fixed** | Crash-handler installation/formatting/bounds remain safe. |
| M-08 | Medium | **Partial** | Restart-safe daily caps, queue/ring/per-file bounds remain; current-day aggregate, crash counts, and pre-Q coverage remain unbounded. |
| M-09 | Medium | **Partial** | Streams close and malformed UTF-8 is rejected; valid-UTF-8 binary/control payloads can still import through permissive paths. |
| M-10 | Medium | **Fixed** | Durable file tombstones, fail-closed locking, mark-first deletion, startup reconciliation, and inactive/active preservation are implemented. |
| M-11 | Medium | **Fixed** | Preference corruption/range validation and slider commit behavior remain fixed. |
| M-12 | Medium | **Partial** | Merged settings callbacks and ordered persistence exist; settings-only undo and atomic import rollback remain incomplete. |
| M-13 | Medium | **Fixed (static)** | Saveable drafts, Back, IME, and Settings snackbar paths remain implemented. |
| M-14 | Medium | **Partial** | Actionable-row selection is fixed; viewport/large-font behavior still needs device evidence. |
| M-15 | Medium | **Fixed** | Backup remains disabled with explicit rules. |
| M-16 | Medium | **Fixed (static)** | Pre-Q FileProvider sharing is implemented; API 26–28 device behavior remains unverified. |
| L-01 | Low | **Fixed** | Whitespace-only lines remain independent sections. |
| L-02 | Low | **Fixed** | Results remain document ordered. |
| L-03 | Low | **Partial** | Normal/import mapping improved; picker/export/cursor-only caret paths remain incomplete. |
| L-04 | Low | **Fixed** | Signed zero remains normalized. |
| L-05 | Low | **Fixed for original scope** | Parser/patcher endings remain correct; canonical output normalizes to LF. |
| L-06 | Low | **Partial** | Trailing comment content survives; leading whitespace is still removed. |
| L-07 | Low | **Partial** | Normal logs clean controls; raw crash fields/stacks and mutable paths remain. |
| L-08 | Low | **Fixed** | Totals, memory, redo, and marks remain exposed. |
| L-09 | Low | **Partial** | Core docs improved; retention/crash/stale comments remain overstated. |
| L-10 | Low | **Partial** | Adaptive resources build with monochrome layers but remain untracked. |

### Functional priorities still open

#### 1. Lifecycle/process-death replay acceptance (deferred-blocked)

Replay decisions are centralized in `NoteJournal.planReplay()` at `app/src/main/java/com/npnpatidar/ncal/storage/NoteJournal.kt:91`: malformed, deleted, and stale entries are discarded while live entries apply in filename order. The drain path executes that plan at `app/src/main/java/com/npnpatidar/ncal/ui/TapeViewModel.kt:866`. Startup reconciles tombstones, clears temporary journal files, and replays before loading notes.

Ordering, deleted/stale/malformed handling, round trips, capacity, and generation behavior are covered by `NoteJournalTest`. Kill/power behavior during debounce, asynchronous drain, filesystem sync, or generation turnover has not been demonstrated on a device. This is deferred-blocked acceptance: no further H-05 code round is required until adb/emulator/device access is available.

#### 2. Writer metadata and transaction precedence

Normal active-header round trips pass, and export precedence is now an explicit tested rule. Direct calls can still emit unsafe separators, caret identity is reset on several export/duplicate/import paths, settings/import commits remain separate, and multi-file rollback is intentionally partial.

Validate direct writer metadata, complete malformed-metadata round-trip tests, unify atomic transaction semantics where feasible, and document intentional new-document resets.

#### 3. Logger retention and device-level verification

Restart-safe daily caps, queue/ring bounds, per-file caps, file-count limits, immutable gates, and unique crash names are implemented. Current-day aggregates, crash-file counts, pre-Q coverage, TalkBack, rotation/recreation, widths/heights/font scales, API 26–28 sharing, backup restore, and MediaStore collisions still need runtime evidence.

Add retention accounting plus device/instrumentation coverage before treating these paths as complete.

## Eighth-round functional verification (superseded)

### Verdict

**No High functional code defect remains.** Release/deployment and device-only acceptance remain deferred. Among the 34 in-scope functional findings, the working tree has **24 fixed, 10 partially fixed, and 0 regressed**. H-05 implementation is fixed; only device replay acceptance is deferred-blocked.

The eighth implementation centralizes journal replay decisions, makes export precedence explicit and tested, restarts the daily log cap from on-disk size, and expands the suite to 239 JVM tests. Fresh debug validation passes. Remaining functional work is process-death/device replay evidence, writer-metadata and transaction precedence details, logger aggregate retention, and device-level UI/storage verification.

### Independent functional validation

- Source test inventory: **224 engine, 5 tombstone, and 10 journal tests, totaling 239**.
- Forced fresh JVM execution with `--rerun-tasks`: **224 engine, 5 tombstone, and 10 journal tests, with 0 skipped, 0 failures, and 0 errors**.
- `:app:lintDebug :app:assembleDebug`: **BUILD SUCCESSFUL** with the documented AAPT2 override; debug lint reports **0 errors, 12 warnings**.
- Fresh debug APK hash: `eb2592c609dd0e6982816906d68533491da3d689fc8382bfe8500fb26a536f41`.
- No release tasks were exercised in this round because release/deployment remains deferred.
- No Android instrumentation, API-level device-matrix, process-death, or minified-runtime test exists.

### Current functional status

| ID | Current severity | Status | Ninth-round result |
|---|---|---|---|
| H-01 | High | **Fixed** | Centralized, UTF-8, aggregate, direct-value, TXT, and derived-total budgets remain enforced. |
| H-02 | High | **Fixed** | Atomic replacement, generations, failure surfacing, deletion stabilization, and tombstone checks remain intact. |
| H-03 | High | **Fixed** | Startup, select, create, and active-delete replacement commits use generation guards. |
| H-04 | High | **Fixed** | Diagnostic gating, redaction, bounds, immutable gates, and unique crash names remain intact. |
| H-05 | High | **Partial** | Replay planning, generation/tombstone handling, startup reconcile, and journal unit coverage are implemented; process-death replay remains unverified on a device. |
| H-06 | High | **Fixed** | Pending-row cleanup is reachable and current MediaStore operations fail closed; device fault-injection remains under functional test gaps. |
| H-07 | High | **Fixed** | Separator authority, fallback/header/caret bounds, and negative grouping round trips pass. |
| H-08 | High | **Fixed** | Leading chains remain corrected. |
| M-01 | Medium | **Fixed** | The legacy list overload is removed; production callers/tests use document-index totals. |
| M-02 | Medium | **Fixed** | Date suffixes, Unicode formulas, malformed exponents/grouping/headers, and unsafe separators are rejected while preserved as comments. |
| M-03 | Medium | **Fixed** | Complete/incomplete/one-separator/UUID/caret header validation is implemented. |
| M-04 | Medium | **Fixed** | Exact/reduced rational powers and verified roots remain correct. |
| M-05 | Medium | **Fixed** | `.calc` and `.txt` block evaluator errors and blocking parser warnings. |
| M-06 | Medium | **Partial** | Normal UUID/active-separator/header/caret round trips pass; direct unsafe writer metadata and remaining identity precedence remain incomplete. |
| M-07 | Medium | **Fixed** | Crash-handler installation/formatting/bounds remain safe. |
| M-08 | Medium | **Partial** | Restart-safe daily caps, queue/ring/per-file bounds remain; current-day aggregate, crash counts, and pre-Q coverage remain unbounded. |
| M-09 | Medium | **Partial** | Streams close and malformed UTF-8 is rejected; valid-UTF-8 binary/control payloads can still import through permissive paths. |
| M-10 | Medium | **Fixed** | Durable file tombstones, fail-closed locking, mark-first deletion, startup reconciliation, and inactive/active preservation are implemented. |
| M-11 | Medium | **Fixed** | Preference corruption/range validation and slider commit behavior remain fixed. |
| M-12 | Medium | **Partial** | Merged settings callbacks and ordered persistence exist; settings-only undo and atomic import rollback remain incomplete. |
| M-13 | Medium | **Fixed (static)** | Saveable drafts, Back, IME, and Settings snackbar paths remain implemented. |
| M-14 | Medium | **Partial** | Actionable-row selection is fixed; viewport/large-font behavior still needs device evidence. |
| M-15 | Medium | **Fixed** | Backup remains disabled with explicit rules. |
| M-16 | Medium | **Fixed (static)** | Pre-Q FileProvider sharing is implemented; API 26–28 device behavior remains unverified. |
| L-01 | Low | **Fixed** | Whitespace-only lines remain independent sections. |
| L-02 | Low | **Fixed** | Results remain document ordered. |
| L-03 | Low | **Partial** | Normal/import mapping improved; picker/export/cursor-only caret paths remain incomplete. |
| L-04 | Low | **Fixed** | Signed zero remains normalized. |
| L-05 | Low | **Fixed for original scope** | Parser/patcher endings remain correct; canonical output normalizes to LF. |
| L-06 | Low | **Partial** | Trailing comment content survives; leading whitespace is still removed. |
| L-07 | Low | **Partial** | Normal logs clean controls; raw crash fields/stacks and mutable paths remain. |
| L-08 | Low | **Fixed** | Totals, memory, redo, and marks remain exposed. |
| L-09 | Low | **Partial** | Core docs improved; retention/crash/stale comments remain overstated. |
| L-10 | Low | **Partial** | Adaptive resources build with monochrome layers but remain untracked. |

### Functional priorities still open

#### 1. Lifecycle/process-death replay evidence

Replay decisions are centralized in `NoteJournal.planReplay()` at `app/src/main/java/com/npnpatidar/ncal/storage/NoteJournal.kt:91`: malformed, deleted, and stale entries are discarded while live entries apply in filename order. The drain path executes that plan at `app/src/main/java/com/npnpatidar/ncal/ui/TapeViewModel.kt:866`. Startup reconciles tombstones, clears temporary journal files, and replays before loading notes.

Ordering, deleted/stale/malformed handling, round trips, capacity, and generation behavior are covered by `NoteJournalTest`. Kill/power behavior during debounce, asynchronous drain, filesystem sync, or generation turnover has not been demonstrated on a device. Add process-death replay tests and document the exact durability guarantee.

#### 2. Writer metadata and transaction precedence

Normal active-header round trips pass, and export precedence is now an explicit tested rule. Direct calls can still emit unsafe separators, caret identity is reset on several export/duplicate/import paths, settings/import commits remain separate, and multi-file rollback is intentionally partial.

Validate direct writer metadata, complete malformed-metadata round-trip tests, unify atomic transaction semantics where feasible, and document intentional new-document resets.

#### 3. Logger retention and device-level verification

Restart-safe daily caps, queue/ring bounds, per-file caps, file-count limits, immutable gates, and unique crash names are implemented. Current-day aggregates, crash-file counts, pre-Q coverage, TalkBack, rotation/recreation, widths/heights/font scales, API 26–28 sharing, backup restore, and MediaStore collisions still need runtime evidence.

Add retention accounting plus device/instrumentation coverage before treating these paths as complete.

## Eighth-round functional verification (superseded)

### Verdict

**Functionality is now complete at the implementation level except for one High durability-evidence gap.** Release and deployment remain deferred. Among the 34 in-scope functional findings, the working tree has **23 fixed, 11 partially fixed, and 0 regressed**. The remaining High issue is lifecycle/process-death durability evidence.

The seventh implementation adds a write-ahead save journal with startup replay and generation-checked drains, mark-first deletion with tombstone reconciliation, fail-closed file locking, non-evicting synced tombstones, stricter metadata/caret/export paths, logger file-count caps and immutable gates, monochrome launcher layers, and 234 passing JVM tests. Fresh debug validation passes. Remaining functional work is process-death replay evidence, writer-metadata and transaction precedence details, logger aggregate retention, and device-level UI/storage verification.

### Independent functional validation

- Source test inventory: **222 engine, 5 tombstone, and 7 journal tests, totaling 234**.
- Forced fresh JVM execution with `--rerun-tasks`: **222 engine, 5 tombstone, and 7 journal tests, with 0 skipped, 0 failures, and 0 errors**.
- `:app:lintDebug :app:assembleDebug`: **BUILD SUCCESSFUL** with the documented AAPT2 override; debug lint reports **0 errors, 12 warnings**.
- Fresh debug APK hash: `d622a250afc3d0f82777599fbda3c6bd9fa1155a85717ad4899dae1d2ef457df`.
- No release tasks were exercised in this round because release/deployment remains deferred.
- No Android instrumentation, API-level device-matrix, process-death, or minified-runtime test exists.

### Current functional status

| ID | Current severity | Status | Eighth-round result |
|---|---|---|---|
| H-01 | High | **Fixed** | Centralized, UTF-8, aggregate, direct-value, TXT, and derived-total budgets remain enforced. |
| H-02 | High | **Fixed** | Atomic replacement, generations, failure surfacing, deletion stabilization, and tombstone checks remain intact. |
| H-03 | High | **Fixed** | Startup, select, create, and active-delete replacement commits use generation guards. |
| H-04 | High | **Fixed** | Diagnostic gating, redaction, bounds, immutable gates, and unique crash names remain intact. |
| H-05 | High | **Partial** | Write-ahead staging, fsynced flush entries, generation-checked drains, and startup replay are implemented; process-death replay remains unverified on a device. |
| H-06 | High | **Fixed** | Pending-row cleanup is reachable and current MediaStore operations fail closed; device fault-injection remains under functional test gaps. |
| H-07 | High | **Fixed** | Separator authority, fallback/header/caret bounds, and negative grouping round trips pass. |
| H-08 | High | **Fixed** | Leading chains remain corrected. |
| M-01 | Medium | **Fixed** | The legacy list overload is removed; production callers/tests use document-index totals. |
| M-02 | Medium | **Fixed** | Date suffixes, Unicode formulas, malformed exponents/grouping/headers, and unsafe separators are rejected while preserved as comments. |
| M-03 | Medium | **Fixed** | Complete/incomplete/one-separator/UUID/caret header validation is implemented. |
| M-04 | Medium | **Fixed** | Exact/reduced rational powers and verified roots remain correct. |
| M-05 | Medium | **Fixed** | `.calc` and `.txt` block evaluator errors and blocking parser warnings. |
| M-06 | Medium | **Partial** | Normal UUID/active-separator/header/caret round trips pass; direct unsafe writer metadata and remaining identity precedence remain incomplete. |
| M-07 | Medium | **Fixed** | Crash-handler installation/formatting/bounds remain safe. |
| M-08 | Medium | **Partial** | Queue/ring/daily/per-file caps remain; current-day aggregate, crash counts, and pre-Q coverage remain unbounded. |
| M-09 | Medium | **Partial** | Streams close and malformed UTF-8 is rejected; valid-UTF-8 binary/control payloads can still import through permissive paths. |
| M-10 | Medium | **Fixed** | Durable file tombstones, fail-closed locking, mark-first deletion, startup reconciliation, and inactive/active preservation are implemented. |
| M-11 | Medium | **Fixed** | Preference corruption/range validation and slider commit behavior remain fixed. |
| M-12 | Medium | **Partial** | Merged settings callbacks and ordered persistence exist; settings-only undo and atomic import rollback remain incomplete. |
| M-13 | Medium | **Fixed (static)** | Saveable drafts, Back, IME, and Settings snackbar paths remain implemented. |
| M-14 | Medium | **Partial** | Actionable-row selection is fixed; viewport/large-font behavior still needs device evidence. |
| M-15 | Medium | **Fixed** | Backup remains disabled with explicit rules. |
| M-16 | Medium | **Fixed (static)** | Pre-Q FileProvider sharing is implemented; API 26–28 device behavior remains unverified. |
| L-01 | Low | **Fixed** | Whitespace-only lines remain independent sections. |
| L-02 | Low | **Fixed** | Results remain document ordered. |
| L-03 | Low | **Partial** | Normal/import mapping improved; picker/export/cursor-only caret paths remain incomplete. |
| L-04 | Low | **Fixed** | Signed zero remains normalized. |
| L-05 | Low | **Fixed for original scope** | Parser/patcher endings remain correct; canonical output normalizes to LF. |
| L-06 | Low | **Partial** | Trailing comment content survives; leading whitespace is still removed. |
| L-07 | Low | **Partial** | Normal logs clean controls; raw crash fields/stacks and mutable paths remain. |
| L-08 | Low | **Fixed** | Totals, memory, redo, and marks remain exposed. |
| L-09 | Low | **Partial** | Core docs improved; retention/crash/stale comments remain overstated. |
| L-10 | Low | **Partial** | Adaptive resources build with monochrome layers but remain untracked. |

### Functional priorities still open

#### 1. Lifecycle/process-death replay evidence

Debounced saves stage journal entries, `onPause()` stages an fsynced flush entry, startup clears temporary files and replays pending entries, drains use generation checks, and tombstone checks discard deleted-note entries. Journal round-trip, ordering, malformed-entry, generation-discard, and capacity tests pass.

Kill/power behavior during the 800 ms debounce, asynchronous drain, filesystem sync, and generation turnover has not been demonstrated on a device. Add process-death replay tests and document the exact durability guarantee.

#### 2. Writer metadata and transaction precedence

Normal active-header round trips pass, but direct calls can supply unsafe separators, export caller/header precedence, caret resets, settings/import separate commits, and multi-file rollback remain incomplete.

Validate direct writer metadata, define header/caller/caret precedence explicitly, and test malformed-metadata round trips and concurrent settings/import operations.

#### 3. Logger retention and device-level verification

Queue/ring/daily/per-file bounds are implemented, but current-day aggregates, crash counts, pre-Q coverage, TalkBack, rotation/recreation, widths/heights/font scales, API 26–28 sharing, backup restore, and MediaStore collisions still need runtime evidence.

Add retention accounting plus device/instrumentation coverage before treating these paths as complete.

## Seventh-round functional verification (superseded)

### Verdict

**Functionality is now substantially complete at the implementation level, with one High issue remaining.** Release and deployment remain deferred. Among the 34 in-scope functional findings, the working tree has **23 fixed, 11 partially fixed, and 0 regressed**. The remaining High issue is lifecycle durability when the process dies before or during a lifecycle save.

The seventh implementation adds size-budgeted lifecycle flushing, mark-first deletion with startup reconciliation, fail-closed file locking, non-evicting synced tombstones, stricter fallback/header/caret metadata, per-file logger caps, immutable logger gates, monochrome launcher layers, and 227 passing JVM tests. Fresh debug validation passes. Remaining functional work is durability/process-death evidence, writer-metadata and transaction precedence details, logger aggregate retention, and device-level UI/storage verification.

### Independent functional validation

- Source test inventory: **222 engine plus 5 tombstone tests, totaling 227**.
- Forced fresh JVM execution with `--rerun-tasks`: **222 engine tests plus 5 tombstone tests, with 0 skipped, 0 failures, and 0 errors**.
- `:app:lintDebug :app:assembleDebug`: **BUILD SUCCESSFUL** with the documented AAPT2 override; debug lint reports **0 errors, 12 warnings**.
- Fresh debug APK hash: `a579d6f475b5bec9408d60132880ecc892ccd8445fdcfe07f917bb97caec3310`.
- No release tasks were exercised in this round because release/deployment remains deferred.
- No Android instrumentation, API-level device-matrix, process-death, or minified-runtime test exists.

### Current functional status

| ID | Current severity | Status | Seventh-round result |
|---|---|---|---|
| H-01 | High | **Fixed** | Centralized, UTF-8, aggregate, direct-value, TXT, and derived-total budgets remain enforced. |
| H-02 | High | **Fixed** | Atomic replacement, generations, failure surfacing, deletion stabilization, and tombstone checks remain intact. |
| H-03 | High | **Fixed** | Startup, select, create, and active-delete replacement commits use generation guards. |
| H-04 | High | **Fixed** | Diagnostic gating, redaction, bounds, immutable gates, and unique crash names remain intact. |
| H-05 | High | **Partial** | Small-note synchronous flushing is safe and bounded; large-note executor waits and process-death replay remain unverified. |
| H-06 | High | **Fixed** | Pending-row cleanup is reachable and current MediaStore operations fail closed; device fault-injection remains under functional test gaps. |
| H-07 | High | **Fixed** | Separator authority, fallback/header/caret bounds, and negative grouping round trips pass. |
| H-08 | High | **Fixed** | Leading chains remain corrected. |
| M-01 | Medium | **Fixed** | The legacy list overload is removed; production callers/tests use document-index totals. |
| M-02 | Medium | **Fixed** | Date suffixes, Unicode formulas, malformed exponents/grouping/headers, and unsafe separators are rejected while preserved as comments. |
| M-03 | Medium | **Fixed** | Complete/incomplete/one-separator/UUID/caret header validation is implemented. |
| M-04 | Medium | **Fixed** | Exact/reduced rational powers and verified roots remain correct. |
| M-05 | Medium | **Fixed** | `.calc` and `.txt` block evaluator errors and blocking parser warnings. |
| M-06 | Medium | **Partial** | Normal UUID/active-separator/header/caret round trips pass; direct unsafe writer metadata and remaining identity precedence remain incomplete. |
| M-07 | Medium | **Fixed** | Crash-handler installation/formatting/bounds remain safe. |
| M-08 | Medium | **Partial** | Queue/ring/daily/per-file caps remain; current-day aggregate, crash counts, and pre-Q coverage remain unbounded. |
| M-09 | Medium | **Partial** | Streams close and malformed UTF-8 is rejected; valid-UTF-8 binary/control payloads can still import through permissive paths. |
| M-10 | Medium | **Fixed** | Durable file tombstones, fail-closed locking, mark-first deletion, startup reconciliation, and inactive/active preservation are implemented. |
| M-11 | Medium | **Fixed** | Preference corruption/range validation and slider commit behavior remain fixed. |
| M-12 | Medium | **Partial** | Merged settings callbacks and ordered persistence exist; settings-only undo and atomic import rollback remain incomplete. |
| M-13 | Medium | **Fixed (static)** | Saveable drafts, Back, IME, and Settings snackbar paths remain implemented. |
| M-14 | Medium | **Partial** | Actionable-row selection is fixed; viewport/large-font behavior still needs device evidence. |
| M-15 | Medium | **Fixed** | Backup remains disabled with explicit rules. |
| M-16 | Medium | **Fixed (static)** | Pre-Q FileProvider sharing is implemented; API 26–28 device behavior remains unverified. |
| L-01 | Low | **Fixed** | Whitespace-only lines remain independent sections. |
| L-02 | Low | **Fixed** | Results remain document ordered. |
| L-03 | Low | **Partial** | Normal/import mapping improved; picker/export/cursor-only caret paths remain incomplete. |
| L-04 | Low | **Fixed** | Signed zero remains normalized. |
| L-05 | Low | **Fixed for original scope** | Parser/patcher endings remain correct; canonical output normalizes to LF. |
| L-06 | Low | **Partial** | Trailing comment content survives; leading whitespace is still removed. |
| L-07 | Low | **Partial** | Normal logs clean controls; raw crash fields/stacks and mutable paths remain. |
| L-08 | Low | **Fixed** | Totals, memory, redo, and marks remain exposed. |
| L-09 | Low | **Partial** | Core docs improved; retention/crash/stale comments remain overstated. |
| L-10 | Low | **Partial** | Adaptive resources build with monochrome layers but remain untracked. |

### Functional priorities still open

#### 1. Lifecycle/process-death durability

Small notes flush synchronously with dispatcher-independent locks; oversized notes use a bounded executor wait with visible timeout/failure messages. A kill before `onPause()`, during the debounce, or during filesystem work can still lose the latest burst.

Add explicit durability semantics and process-death replay tests: synchronous small-note journaling, bounded large-note behavior, and visible failure for every unsuccessful flush.

#### 2. Writer metadata and transaction precedence

Normal active-header round trips pass, but direct unsafe separators, export caller/header precedence, caret resets, settings/import separate commits, and multi-file rollback remain incomplete.

Validate direct writer metadata, define header/caller/caret precedence, complete settings/import transaction tests, and document intentional new-document resets.

#### 3. Logger retention and device-level verification

Queue/ring/daily/per-file bounds are implemented, but current-day aggregates, crash counts, pre-Q coverage, TalkBack, rotation/recreation, widths/heights/font scales, API 26–28 sharing, backup restore, and MediaStore collisions still need runtime evidence.

Add retention accounting plus device/instrumentation coverage before treating these paths as complete.

## Sixth-round functional verification (superseded)

### Verdict

**Functionality is substantially improved, but two High issues remain.** Release and deployment remain deferred. Among the 34 in-scope functional findings, the working tree has **22 fixed, 12 partially fixed, and 0 regressed**. The remaining High issues are lifecycle durability on a contended Main thread and deletion/replacement plus cross-process preservation edge cases.

The sixth implementation replaces timeout-based flushing with synchronous Main-safe persistence, uses durable non-evicting tombstones and a fail-closed file lock, hardens fallback/header/caret metadata paths, preserves stranded edits for the next commit, and expands the suite to 225 JVM tests. Fresh debug validation passes. Remaining functional work is Main-thread save budgeting, cross-process deletion races, writer-metadata/transaction precedence, and device-level UI/storage verification.

### Independent functional validation

- Source test inventory: **220 engine plus 5 tombstone tests, totaling 225**.
- Forced fresh JVM execution with `--rerun-tasks`: **220 engine tests plus 5 tombstone tests, with 0 skipped, 0 failures, and 0 errors**.
- `:app:lintDebug :app:assembleDebug`: **BUILD SUCCESSFUL** with the documented AAPT2 override; debug lint reports **0 errors, 14 warnings**.
- Fresh debug APK hash: `25d8c4810d73f51cea9d132e3c27375c886f955e642e6cc6168ba981a659110a`.
- No release tasks were exercised in this round because release/deployment remains deferred.
- No Android instrumentation, API-level device-matrix, process-death, or minified-runtime test exists.

### Current functional status

| ID | Current severity | Status | Sixth-round result |
|---|---|---|---|
| H-01 | High | **Fixed** | Parsed, aggregate, UTF-8, direct-value, TXT, and derived-total budgets remain enforced. |
| H-02 | High | **Fixed** | Atomic replacement, save generations, failure surfacing, and stabilization remain intact. |
| H-03 | High | **Fixed** | Startup, select, create, and active-delete replacement commits use generation guards. |
| H-04 | High | **Fixed** | Diagnostic gating, redaction, bounds, and unique crash names remain intact. |
| H-05 | High | **Partial** | `runBlocking`/timeout deadlock paths are gone, but a full one-megabyte flush still runs synchronously on Main with no time/size budget. |
| H-06 | High | **Fixed** | Pending-row cleanup is reachable and current MediaStore operations fail closed; device fault-injection remains under functional test gaps. |
| H-07 | High | **Fixed** | Header-discovered separators, unsafe-separator rejection, and negative grouping round trips pass. |
| H-08 | High | **Fixed** | Leading chains remain corrected. |
| M-01 | Medium | **Fixed** | The legacy list overload is removed; production callers/tests use document-index totals. |
| M-02 | Medium | **Fixed** | Date suffixes, Unicode formulas, malformed exponents/grouping/headers, and unsafe separators are rejected while preserved as comments. |
| M-03 | Medium | **Fixed** | Complete/incomplete/one-separator/UUID/caret header validation is implemented. |
| M-04 | Medium | **Fixed** | Exact/reduced rational powers and verified roots remain correct. |
| M-05 | Medium | **Fixed** | `.calc` and `.txt` block evaluator errors and blocking parser warnings. |
| M-06 | Medium | **Partial** | Normal UUID/active-separator/header/caret round trips pass; direct unsafe writer metadata and remaining identity precedence remain incomplete. |
| M-07 | Medium | **Fixed** | Crash-handler installation/formatting/bounds remain safe. |
| M-08 | Medium | **Partial** | Queue/ring/daily bounds remain; current-day, aggregate, and pre-Q crash retention remain unbounded. |
| M-09 | Medium | **Partial** | Streams close and malformed UTF-8 is rejected; valid-UTF-8 binary/control payloads can still import through permissive paths. |
| M-10 | **High** | **Partial** | Durable file tombstones, fail-closed locking, and inactive-operation preservation are implemented; concurrent active-delete and cross-process preservation remain. |
| M-11 | Medium | **Fixed** | Preference corruption/range validation and slider commit behavior remain fixed. |
| M-12 | Medium | **Partial** | Merged settings callbacks and ordered persistence exist; settings-only undo and atomic import rollback remain incomplete. |
| M-13 | Medium | **Fixed (static)** | Saveable drafts, Back, IME, and Settings snackbar paths remain implemented. |
| M-14 | Medium | **Partial** | Actionable-row selection is fixed; viewport/large-font behavior still needs device evidence. |
| M-15 | Medium | **Fixed** | Backup remains disabled with explicit rules. |
| M-16 | Medium | **Fixed (static)** | Pre-Q FileProvider sharing is implemented; API 26–28 device behavior remains unverified. |
| L-01 | Low | **Fixed** | Whitespace-only lines remain independent sections. |
| L-02 | Low | **Fixed** | Results remain document ordered. |
| L-03 | Low | **Partial** | Normal/import mapping improved; picker/export/cursor-only caret paths remain incomplete. |
| L-04 | Low | **Fixed** | Signed zero remains normalized. |
| L-05 | Low | **Fixed for original scope** | Parser/patcher endings remain correct; canonical output normalizes to LF. |
| L-06 | Low | **Partial** | Trailing comment content survives; leading whitespace is still removed. |
| L-07 | Low | **Partial** | Normal logs clean controls; raw crash fields/stacks and mutable gate remain. |
| L-08 | Low | **Fixed** | Totals, memory, redo, and marks remain exposed. |
| L-09 | Low | **Partial** | Core docs improved; retention/crash/stale comments remain overstated. |
| L-10 | Low | **Partial** | Adaptive resources build but remain untracked and lack monochrome layers. |

### Functional priorities still open

#### 1. Lifecycle durability on a contended Main thread

`flushNow()` now snapshots on Main and calls blocking `repo.saveIfCurrent()` directly from `onPause()` at `app/src/main/java/com/npnpatidar/ncal/ui/TapeViewModel.kt:838` and `app/src/main/java/com/npnpatidar/ncal/MainActivity.kt:38`. There is no indefinite `runBlocking` deadlock, but a maximum-size save still performs full parsing, evaluation, syncing, and directory/file work on Main.

Bound the synchronous path by document size or elapsed time, move noncritical work off the lifecycle thread without losing fail-closed semantics, and add lifecycle/process-death persistence tests.

#### 2. Deletion/replacement and cross-process preservation

Durable markers are non-evicting, file locking fails closed, and inactive operations preserve unrelated saves. Active deletion can still lose edits made after bounded retries, suppressed save windows retain pending work only in memory, tombstone reads and sync helpers fail open on I/O errors, and some repository/metadata operations do not share one cross-process transaction.

Use a durable deletion marker with explicit success/failure propagation, define cross-process save/delete ordering, and add save-during-delete plus post-restart integration tests.

#### 3. Writer metadata and transaction ordering

Normal active-header round trips pass, but direct calls can still emit unsafe separators, export precedence remains caller-wins versus parse header-wins, and caret identity is reset on several export/duplicate/import paths. Settings and imported content commits are also separate operations rather than one atomic transaction.

Validate direct writer metadata, define header/caller/caret precedence, and test malformed-metadata round trips and concurrent settings/import operations.

#### 4. Device-level UI and storage verification

Saveable drafts, actionable selection, adaptive keypad sizing, backup rules, and pre-Q sharing are implemented in source. TalkBack, rotation/recreation, widths/heights/font scales, API 26–28 sharing, backup restore, and MediaStore collisions still need device evidence.

## Fifth-round functional verification (superseded)

### Verdict

**Functionality is substantially improved, but two High issues remain.** Release and deployment remain deferred. Among the 34 in-scope functional findings, the working tree has **22 fixed, 12 partially fixed, and 0 regressed**. The remaining High issues are lifecycle durability around process termination and deletion/replacement plus cross-instance save preservation.

The fifth implementation closes the reported Main-thread deadlock, startup/active-delete guards, inactive-operation save loss, header separator authority, public-writer budgets, pending-row cleanup, settings/import transactions, and several accessibility/resource gaps. Independent debug validation passes all 223 JVM tests. The remaining work is bounded flush/durability semantics, concurrent deletion edge cases, writer-metadata/transaction ordering, and device-level UI/storage verification.

### Independent functional validation

- Source test inventory: **218 engine plus 5 tombstone tests, totaling 223**.
- Forced fresh JVM execution with `--rerun-tasks`: **218 engine tests plus 5 tombstone tests, with 0 skipped, 0 failures, and 0 errors**.
- `:app:lintDebug :app:assembleDebug`: **BUILD SUCCESSFUL** with the documented AAPT2 override; debug lint reports **0 errors, 14 warnings**.
- Fresh debug APK hash: `e2f1391461fe71a333ac900974c2bf1b25724d98ef64f507210bc0fefbb5d6da`.
- No release tasks were exercised in this round because release/deployment remains deferred.
- No Android instrumentation, API-level device-matrix, process-death, or minified-runtime test exists.

### Current functional status

| ID | Current severity | Status | Fifth-round result |
|---|---|---|---|
| H-01 | High | **Fixed** | Parsed, aggregate, UTF-8, direct-value, TXT, and derived-total budgets are enforced on the claimed paths. |
| H-02 | High | **Fixed** | Atomic replacement, save generations, failure surfacing, and delete/create stabilization remain intact. |
| H-03 | High | **Fixed** | Startup, select, create, and active-delete replacement commits use generation guards. |
| H-04 | High | **Fixed** | Diagnostic gating, redaction, bounds, and unique crash names remain intact. |
| H-05 | High | **Partial** | The indefinite deadlock is fixed, but `onPause()` can block Main for up to 2.5 seconds and timeouts can silently lose data. |
| H-06 | High | **Fixed** | Pending-row cleanup is reachable and current MediaStore operations fail closed; device fault-injection remains under functional test gaps. |
| H-07 | High | **Fixed** | Header-discovered separators and negative Indian grouping round trips pass. |
| H-08 | High | **Fixed** | Leading chains remain corrected. |
| M-01 | Medium | **Fixed** | The legacy list overload is removed; production callers and tests use document-index totals. |
| M-02 | Medium | **Fixed** | Date suffixes, Unicode formulas, malformed exponents/grouping, unsafe separators, and unsafe headers are rejected while preserved as comments. |
| M-03 | Medium | **Fixed** | Complete/incomplete/one-separator/UUID/caret header validation is implemented. |
| M-04 | Medium | **Fixed** | Exact/reduced rational powers and verified roots remain correct. |
| M-05 | Medium | **Fixed** | `.calc` and `.txt` block evaluator errors and blocking parser warnings. |
| M-06 | Medium | **Partial** | Normal UUID/active-separator/header round trips pass; direct unsafe writer metadata, header caret bounds, and caret identity remain incomplete. |
| M-07 | Medium | **Fixed** | Crash-handler installation/formatting/bounds remain safe. |
| M-08 | Medium | **Partial** | Queue/ring/daily bounds remain; current-day, aggregate, and pre-Q crash retention remain unbounded. |
| M-09 | Medium | **Partial** | Streams close and malformed UTF-8 is rejected; valid-UTF-8 binary/control payloads can still import through permissive paths. |
| M-10 | **High** | **Partial** | Inactive-operation loss and same-instance resurrection are fixed; concurrent active-delete and cross-instance preservation remain. |
| M-11 | Medium | **Fixed** | Preference corruption/range validation and slider commit behavior remain fixed. |
| M-12 | Medium | **Partial** | Merged settings callbacks and ordered persistence exist; settings-only undo and atomic import rollback remain incomplete. |
| M-13 | Medium | **Fixed (static)** | Saveable drafts, Back, IME, and Settings snackbar paths remain implemented. |
| M-14 | Medium | **Partial** | Actionable-row selection is fixed; viewport/large-font behavior still needs device evidence. |
| M-15 | Medium | **Fixed** | Backup remains disabled with explicit rules. |
| M-16 | Medium | **Fixed (static)** | Pre-Q FileProvider sharing is implemented; API 26–28 device behavior remains unverified. |
| L-01 | Low | **Fixed** | Whitespace-only lines remain independent sections. |
| L-02 | Low | **Fixed** | Results remain document ordered. |
| L-03 | Low | **Partial** | Normal/import mapping improved; picker/export/cursor-only caret paths remain incomplete. |
| L-04 | Low | **Fixed** | Signed zero remains normalized. |
| L-05 | Low | **Fixed for original scope** | Parser/patcher endings remain correct; canonical output normalizes to LF. |
| L-06 | Low | **Partial** | Trailing comment content survives; leading whitespace is still removed. |
| L-07 | Low | **Partial** | Normal logs clean controls; raw crash fields/stacks and mutable gate remain. |
| L-08 | Low | **Fixed** | Totals, memory, redo, and marks remain exposed. |
| L-09 | Low | **Partial** | Core docs improved; retention/crash/stale comments remain overstated. |
| L-10 | Low | **Partial** | Adaptive resources build but remain untracked and lack monochrome layers. |

### Functional priorities still open

#### 1. Lifecycle durability after backgrounding

`flushNow()` avoids indefinite blocking with `runBlocking(Dispatchers.IO)` plus a 2.5-second timeout at `app/src/main/java/com/npnpatidar/ncal/ui/TapeViewModel.kt:844`. Nevertheless, `onPause()` still executes that blocking wait on Main at `app/src/main/java/com/npnpatidar/ncal/MainActivity.kt:38`. A contended save path can therefore stall pause handling, and a timeout returns without a user-visible failure or retry.

Define durability explicitly: either accept fire-and-forget background persistence, shorten and surface the timeout failure, or use a synchronous journal that can complete safely on the lifecycle thread. Add a process-death/persistence regression test.

#### 2. Deletion/replacement and cross-instance preservation

Inactive delete/duplicate preservation and durable marker files are implemented, including cross-instance visibility tests. Active deletion may still lose concurrent edits after bounded retries, suppressed save windows can discard newer snapshots, file locking has a fail-open fallback, pruning can evict old tombstones, and tombstones lack directory/file sync.

Use durable non-evicting deletion markers or a transactional note store, make deletion fail closed when exclusion cannot be acquired, and add integration tests for save-during-delete and post-restart resurrection.

#### 3. Writer metadata and transaction ordering

Normal active-header round trips pass, but direct calls can supply unsafe separators, header caret values are not fully bounded before use, and caret metadata is overwritten by several export/duplicate/import paths. Settings and imported content commits are also separate operations rather than one atomic transaction.

Validate unsafe metadata before writing, define header/caller/caret precedence explicitly, and test malformed-metadata round trips and concurrent settings/import operations.

#### 4. Device-level UI and storage verification

Saveable drafts, actionable selection, adaptive keypad sizing, backup rules, and pre-Q sharing are implemented in source. TalkBack, rotation/recreation, widths/heights/font scales, API 26–28 sharing, backup restore, and MediaStore collisions still need device evidence.

## Fourth-round functional verification (superseded)

### Verdict

**Functionality is close, but not yet accepted.** Release and deployment remain deferred. Among the 34 in-scope functional findings, the working tree has **22 fixed, 12 partially fixed, and 0 regressed**. Two functional issues remain at High severity: lifecycle durability after backgrounding and active-delete/cross-instance save preservation.

The fourth implementation fixes the `runBlocking` deadlock, guards startup/active-delete commits, preserves inactive-operation saves, moves saves to IO with generations, corrects header-discovered separators and negative Indian grouping, closes TXT export warning gaps, makes pending-row cleanup reachable, and adds 217 passing JVM tests. Fresh debug validation passes. Remaining functional work is durability under process death, bounded deletion/replacement races, writer-metadata/parser edge hardening, settings/import transaction ordering, and device-level UI/storage verification.

### Independent functional validation

- Forced fresh JVM execution with `--rerun-tasks`: **217 tests, 0 skipped, 0 failures, 0 errors** (`app/build/test-results/testDebugUnitTest/TEST-com.npnpatidar.ncal.TapeEngineTest.xml:2`).
- `:app:lintDebug :app:assembleDebug`: **BUILD SUCCESSFUL** with the documented AAPT2 override; debug lint reports **0 errors, 15 warnings**.
- The freshly built debug APK is `app/build/outputs/apk/debug/app-debug.apk` with SHA-256 `c13955afcb12ec7c26a90249299b755b750ccaad9b7b6cde536be3c14d70998f`.
- No release tasks were exercised in this round because release/deployment is deferred.
- No Android instrumentation, API-level device matrix, process-death test, or minified runtime test exists.

### Current functional status

| ID | Current severity | Status | Fourth-round result |
|---|---|---|---|
| H-01 | High | **Fixed** | Parser, aggregate, UTF-8, direct-value, TXT, and derived-total budgets are enforced on normal paths. |
| H-02 | High | **Fixed** | Atomic replacement and typed normal-path failures remain, with save generations and failure messages. |
| H-03 | High | **Fixed** | Startup, select, create, and active-delete replacement commits use `LoadGuard`. |
| H-04 | High | **Fixed** | Release diagnostic gating, redaction, bounds, and unique crash names remain intact. |
| H-05 | High | **Partial** | `runBlocking` is gone, but backgrounding does not await persistence, so the final 800 ms burst can be lost. |
| H-06 | High | **Fixed** | Pending-row cleanup is reachable and current operations fail closed; device fault-injection remains under functional test gaps. |
| H-07 | High | **Fixed** | Parsed-header separator authority, explicit active separators, and negative grouping round trips pass. |
| H-08 | High | **Fixed** | Leading chains remain corrected. |
| M-01 | Medium | **Fixed** | Only the document-index writer overload remains; production callers/tests are migrated. |
| M-02 | Medium | **Fixed** | Date suffixes, Unicode formulas, malformed exponents/grouping/headers, and unsafe separators are rejected as preserved comments. |
| M-03 | Medium | **Fixed** | Complete/incomplete/one-separator/UUID/caret header validation is implemented. |
| M-04 | Medium | **Fixed** | Exact/reduced rational powers and verified roots remain correct. |
| M-05 | Medium | **Fixed** | Both `.calc` and `.txt` block evaluator errors and blocking parser warnings. |
| M-06 | Medium | **Partial** | Normal UUID/active-separator/header round trips pass; direct writer metadata and caret identity remain incomplete. |
| M-07 | Medium | **Fixed** | Crash-handler installation/formatting/bounds remain safe. |
| M-08 | Medium | **Partial** | Queue/ring/daily size are bounded; current-day, aggregate, and pre-Q crash retention remain unbounded. |
| M-09 | Medium | **Partial** | Streams close and malformed UTF-8 is rejected; valid-UTF-8 binary/control payloads can still import through permissive paths. |
| M-10 | **High** | **Partial** | Inactive-operation loss and same-instance resurrection are fixed; active-delete edits and cross-instance resurrection remain. |
| M-11 | Medium | **Fixed** | Preference corruption/range validation and slider commit behavior remain fixed. |
| M-12 | Medium | **Partial** | History counters, merged settings callbacks, and ordered persistence exist; settings-only undo/import rollback remain incomplete. |
| M-13 | Medium | **Fixed (static)** | Saveable drafts, Back, IME, and Settings snackbar paths remain implemented. |
| M-14 | Medium | **Partial** | Actionable-row selection is fixed; viewport/large-font behavior still needs device evidence. |
| M-15 | Medium | **Fixed** | Backup remains disabled with explicit rules. |
| M-16 | Medium | **Fixed (static)** | Pre-Q FileProvider sharing is implemented; API 26–28 device behavior remains unverified. |
| L-01 | Low | **Fixed** | Whitespace-only lines remain independent sections. |
| L-02 | Low | **Fixed** | Results remain document ordered. |
| L-03 | Low | **Partial** | Normal/import mapping improved; picker/export/cursor-only caret paths remain incomplete. |
| L-04 | Low | **Fixed** | Signed zero remains normalized. |
| L-05 | Low | **Fixed for original scope** | Parser/patcher endings remain correct; canonical output normalizes to LF. |
| L-06 | Low | **Partial** | Trailing comment content survives; leading whitespace is still removed. |
| L-07 | Low | **Partial** | Normal logs clean controls; raw crash fields/stacks and mutable gate remain. |
| L-08 | Low | **Fixed** | Totals, memory, redo, and marks remain exposed. |
| L-09 | Low | **Partial** | Core docs improved; retention/crash/stale comments remain overstated. |
| L-10 | Low | **Partial** | Adaptive resources build but remain untracked and lack monochrome layers. |

### Functional priorities still open

#### 1. Lifecycle durability after backgrounding

`flushNow()` snapshots and invalidates the debounce generation at `app/src/main/java/com/npnpatidar/ncal/ui/TapeViewModel.kt:839`, then persists asynchronously on `Dispatchers.IO + NonCancellable` at `:845`. `onPause()` does not await completion at `app/src/main/java/com/npnpatidar/ncal/MainActivity.kt:37`. A process kill inside the 800 ms debounce or during the launched flush can lose the latest burst.

Use a lifecycle-safe wait that cannot block Main indefinitely, or combine prompt critical saves with the debounced in-memory journal. Add a process-death/persistence test.

#### 2. Deletion/replacement and cross-process save preservation

Inactive delete/duplicate no longer cancels unrelated saves, and active deletion retries bounded stability before replacement. Edits during repository deletion, replacement loading, or after tombstoning can still be lost; repository locks/tombstones are instance-local at `app/src/main/java/com/npnpatidar/ncal/storage/NotesRepository.kt:26`. A second ViewModel or process can recreate a deleted file.

Add a durable deletion marker/transaction, final guarded recommit for replacement operations, and a regression test for edits issued during delete/replacement.

#### 3. Writer metadata and transaction ordering hardening

Normal active-header round trips pass, but direct `CalcMeta` values can still emit unsafe separators, `meta` is replaced after parsing, and caret metadata is overwritten in several export/duplicate/import paths. Settings merge/ordering and multi-file import side effects can diverge.

Validate writer metadata against safe separators, define header/caller/caret precedence explicitly, complete settings/import transaction testing, and document any intentional non-round-trip behavior.

#### 4. Device-level UI and storage verification

Saveable drafts, actionable selection, adaptive keypad sizing, backup rules, and pre-Q sharing are implemented in source. TalkBack, rotation/recreation, widths/heights/font scales, API 26–28 sharing, backup restore, and MediaStore collisions still need device evidence.

## Third-round post-implementation verification (superseded)

### Verdict

**Not yet ready for everyday functional use.** Release and deployment work is explicitly deferred. Among the 34 in-scope functional findings, the working tree has **13 fixed, 20 partially fixed, and 1 regressed**. Eight functional issues remain at High severity.

The five deferred release/deployment findings are **H-09, H-10, M-17, M-18, and M-19**. They are tracked below but are excluded from this functionality verdict.

The implementation closes several functional defects, including the originally reported non-default separator path, inactive-note delete/duplicate save loss, guarded `selectNote()`/`createNote()` second-phase race, fractional-power errors, strict UTF-8 decoding, and sharing paths. Fresh JVM/debug builds also pass. The awaited lifecycle barrier introduces a Main-thread deadlock risk, while startup/active-delete loads, active-delete edits, cross-instance tombstones, metadata authority, and parser hardening remain incomplete.

### Independent validation

- Forced fresh JVM execution with `--rerun-tasks`: **210 tests, 0 skipped, 0 failures, 0 errors** (`app/build/test-results/testDebugUnitTest/TEST-com.npnpatidar.ncal.TapeEngineTest.xml:2`).
- `:app:lintDebug :app:assembleDebug`: **BUILD SUCCESSFUL** with the documented AAPT2 override; debug lint reports **0 errors, 14 warnings**.
- Forced `:app:lintRelease :app:bundleRelease --rerun-tasks` with a temporary test key: **BUILD SUCCESSFUL**; R8, shrinking, signing, and mapping output observed; release deployment remains deferred.
- `jarsigner -verify` returned success for the test-signed AAB. Release-path details are retained for later; they are not part of the functional readiness verdict.
- The debug APK copied to `/sdcard/Download/ncal/app-debug.apk` has SHA-256 `3d006e4583c80d7718266ce7d3e7224ddf7794f55041f14a160c7ddf7fb5baec`, matching the response.
- GitHub still reports **zero environments**, and remote CI still reflects the pre-remediation Gradle 8.7 workflow. The new workflow/wrapper/resources remain untracked locally; deployment is therefore deferred rather than evaluated as functional.
- No Android instrumentation or API-level device-matrix test exists. Minified-runtime and protected-release validation are separately deferred.

### Current finding status

| ID | Current severity | Status | Third-round result |
|---|---|---|---|
| H-01 | High | **Partial** | Normal parsed/writer paths are bounded before and after rendering; public single-line formatters and some exact UTF-8 preflight paths remain outside the full budget. |
| H-02 | High | **Partial** | Atomic replacement and typed normal-path failures remain, but race-correction replacement saves can discard failure. |
| H-03 | High | **Partial** | `LoadGuard` fixes select/create; startup and active-delete replacement commits remain unguarded. |
| H-04 | High | **Fixed** | Release persistence remains disabled and sensitive diagnostics remain bounded/redacted. |
| H-05 | High | **Regressed** | `onPause()` now calls `runBlocking`/`cancelAndJoin` on Main and can deadlock the paused dispatcher. |
| H-06 | High | **Partial** | Fail-closed current-operation MediaStore handling and sharing work; interrupted pending cleanup is unreachable. |
| H-07 | High | **Partial** | Explicit separator-aware pretty output is fixed, but input-header precedence and negative Indian grouping can still corrupt values. |
| H-08 | High | **Fixed** | Leading chains remain corrected. |
| H-09 | High | **Fixed in working tree** | Verified Gradle wrapper/checksum/dependency metadata work locally; remediation is not yet delivered remotely. |
| H-10 | High | **Partial** | Signed test-key release works; protected execution, final AAB identity, and production release are unproven. |
| M-01 | Medium | **Partial** | Production map callers are fixed; the public list overload retains ordinal mismatch behavior. |
| M-02 | Medium | **Partial** | Claimed examples are rejected, but date suffixes, Unicode formulas, malformed exponents/grouping, and over-limit paste paths remain. |
| M-03 | Medium | **Partial** | One-separator headers are rejected; malformed metadata lines and unsafe operator-like separators remain accepted. |
| M-04 | Medium | **Fixed** | Exact/reduced rational powers and verified roots remain correct without binary fallback. |
| M-05 | Medium | **Partial** | `.calc` blocks selected parser warnings, but TXT ignores warnings and the warning filter omits “too long.” |
| M-06 | **High** | **Partial** | Normal active-separator round trips pass; input-header authority, writer metadata validation, and caret identity remain incomplete. |
| M-07 | Medium | **Fixed** | Crash-handler installation, formatting, uniqueness, and output bounds remain safe. |
| M-08 | Medium | **Partial** | Queue/ring are bounded; current-day, aggregate, and pre-Q crash retention remain unbounded. |
| M-09 | Medium | **Partial** | Streams close and malformed UTF-8 is rejected; valid-UTF-8 binary/control payloads can still import. |
| M-10 | **High** | **Partial** | Inactive delete/duplicate save loss and same-instance resurrection are fixed; active-delete edits and cross-instance resurrection remain. |
| M-11 | Medium | **Fixed** | Preference corruption/range validation and slider commit behavior remain fixed. |
| M-12 | Medium | **Partial** | History counters and setting persistence attempts exist; settings-only undo and atomic UI/persistence ordering remain incomplete. |
| M-13 | Medium | **Fixed (static)** | Saveable slider/dialog state, Back, IME, and Settings snackbar paths are implemented; device recreation remains under M-19. |
| M-14 | Medium | **Partial** | Actionable-row selection is fixed; viewport cap, visual selection, and settings-row large-font behavior remain. |
| M-15 | Medium | **Fixed** | Backup remains disabled with explicit rules. |
| M-16 | Medium | **Fixed (static)** | Pre-Q FileProvider sharing is implemented; API 26–28 device behavior remains unverified. |
| M-17 | Medium | **Partial** | Version code/name are explicit and capped; protected monotonic ledger/concurrency governance remains external. |
| M-18 | Medium | **Partial** | Injection and source controls are fixed; pinned Node 20 actions and untracked/unrun remote workflows remain. |
| M-19 | Medium | **Partial** | Debug/release checks exist; instrumentation, API matrix, minified runtime, and protected release remain absent. |
| L-01 | Low | **Fixed** | Whitespace-only lines remain independent sections. |
| L-02 | Low | **Fixed** | Results remain document ordered. |
| L-03 | Low | **Partial** | Normal/import mapping improved; picker import, export, and cursor-only metadata remain incomplete. |
| L-04 | Low | **Fixed** | Signed zero remains normalized. |
| L-05 | Low | **Fixed for original scope** | Parser/patcher line endings remain correct; pretty/canonical output still normalizes to LF. |
| L-06 | Low | **Partial** | Trailing comment content survives; leading whitespace is still removed. |
| L-07 | Low | **Partial** | Normal logs clean controls; raw crash fields/stacks and mutable crash gate remain. |
| L-08 | Low | **Fixed** | Totals, memory, redo, and marks remain exposed. |
| L-09 | Low | **Partial** | Core docs improved; retention, protection, crash, and stale comments remain overstated. |
| L-10 | Low | **Partial** | Adaptive resources build but remain untracked and lack monochrome layers. |

Release/deployment scope is limited to **H-09, H-10, M-17, M-18, and M-19**. Those entries retain their statuses for tracking, but they are deferred and excluded from the functionality verdict and priority list below.

### Functional priority issues still open

#### 1. Awaited lifecycle persistence can deadlock Main

`MainActivity.onPause()` calls `flushNow()` synchronously at `app/src/main/java/com/npnpatidar/ncal/MainActivity.kt:37`. `flushNow()` blocks with `runBlocking(Dispatchers.IO)` and then calls `cancelPendingSave()` at `app/src/main/java/com/npnpatidar/ncal/ui/TapeViewModel.kt:766`; `cancelAndJoin()` waits for a save coroutine dispatched on Main at `TapeViewModel.kt:970`.

If Main is blocked while a lifecycle mutex owner is suspended in `withContext(IO)`, or while a Main-dispatched save is being canceled, completion may require Main to resume. The lifecycle thread then waits for a dispatcher that cannot run. Use a lifecycle-aware asynchronous durability mechanism or perform the entire serialized operation on an independent dispatcher; do not block the paused Main thread.

#### 2. Startup and active-delete replacement loads still lack final guards

`LoadGuard` correctly protects `selectNote()` and `createNote()` at `TapeViewModel.kt:167` and `:210`, with checks around formatting at `:887`. Startup calls unguarded `commitLoaded()` at `TapeViewModel.kt:145`, and active deletion does the same at `TapeViewModel.kt:284`.

An early edit while startup is loading can be overwritten. Pass a guard to every commit path, including startup and delete replacement, or keep the editor non-editable/loading until identity is committed.

#### 3. Active deletion can lose edits made after the initial save

Active deletion suppresses and cancels saves at `TapeViewModel.kt:257`, persists an initial snapshot, then suspends during repository deletion. New edits are refused by `scheduleSave()` because the ID is suppressed (`TapeViewModel.kt:1019`) and the unguarded replacement commit overwrites them.

Either block editing for the whole destructive transition or detect an edit-generation change, preserve/retry it, and refuse deletion until the current state is stable. Repository tombstones and locks are instance-local (`app/src/main/java/com/npnpatidar/ncal/storage/NotesRepository.kt:26`), so a second ViewModel can still recreate a file deleted by the first.

#### 4. Metadata and grouping still have corruption paths

`TapeFormatter.pretty()` captures fallback separators before parsing a valid input header at `app/src/main/java/com/npnpatidar/ncal/tape/TapeFormatter.kt:17`; header-discovered German metadata can therefore render with default separators. Negative multiplicative values also reach an Indian grouper that assumes a nonnegative integer at `TapeFormatter.kt:303`.

Use parsed document metadata after parsing, preserve sign outside grouping, and add header-discovered plus negative-factor round-trip tests.

#### 5. Budgets and metadata writers are not universal

Public `CalcFile.formatEntry()`/`formatBalance()` and `TapeFormatter.groupNumber()` can build strings without the aggregate final check. Numeric preflight estimates do not account for multibyte active separators before allocation, though normal final checks still reject the result. `CalcFile.write()` does not fully validate UUID/control metadata before emitting a header.

Apply the same limits and metadata validation to every public writer, and estimate with actual separator UTF-8 width before rendering.

#### 6. Interrupted MediaStore cleanup is unreachable

`discardInterruptedPendingRows()` returns from inside `cursor.use` at `app/src/main/java/com/npnpatidar/ncal/storage/MediaStoreHelper.kt:328`, making the deletion loop at `:351` unreachable. A pending row interrupted by process death can remain indefinitely and collide with later exports.

Collect and close the cursor first, then perform verified deletion. Add a provider fault-injection test for the process-death path.

#### 7. Settings/undo state and persistence can still diverge

Settings-only changes are not inserted into undo history, and undo launches persistence without the lifecycle/version serialization used by updates. Concurrent update/undo persistence can finish in the opposite order from UI publication (`TapeViewModel.kt:469` and `:642`). Multi-file import generation checks prevent stale list publication but do not roll back already imported files.

Use one settings transaction/version for UI publication and persistence, define whether settings are undoable, and make multi-file import atomic or explicitly partial-success.

#### 8. Accessibility and documentation need device-level closure

Actionable-row selection semantics and saveable Settings drafts are fixed. Static gaps remain around the computed keypad viewport cap, non-color note selection, and settings option rows at large font scales. Raw crash diagnostics can still contain control bytes, and README/MainActivity comments still overstate retention/protection/crash guarantees.

Add TalkBack, rotation, API 26, font-scale, MediaStore, and minified-runtime functional tests before treating the source-level fixes as complete.

### Deferred release/deployment appendix

Release and deployment are out of scope until the app is functionally stable. The deferred items are **H-09, H-10, M-17, M-18, and M-19**.

The command-injection issue remains fixed, checksums are artifact-relative, and explicit version codes are capped at 2,100,000,000. Deferred controls are:

- all wrapper/workflow/resource/proguard files remain untracked, so a clean checkout lacks the fix;
- GitHub has no `release` environment and the new workflow has never run remotely;
- several pinned actions still declare Node 20 runtimes;
- manual dispatch is not bound to an immutable release tag;
- no monotonic protected version-code ledger or concurrency policy exists; and
- the workflow checks the keystore certificate before building but does not verify the final AAB signer/application ID/version after packaging.

Revisit this appendix only after the eight functional High issues are resolved: create and protect the environment, commit the complete remediation, update action runtimes, allocate versions from a protected ledger, and verify the final signed AAB before publication.

## Second-round remediation verification (superseded)

The second-round status below is preserved for audit history. The third-round table above is the current source of truth.

### Verdict

**Still not ready for merge or release.** Of the 39 baseline findings, the current working tree has **16 fixed, 22 partially fixed, and 1 regressed**. Seven issues remain at High severity.

The second response materially improves the result: the shell-injection vulnerability is closed, fractional powers are corrected, sharing is implemented, and the release path now builds a signed R8 bundle in local test conditions. However, the current code can corrupt non-default decimal-separator notes, lose a pending active-note save when deleting/duplicating another note, and still overwrite edits during the second asynchronous phase of note loading.

### Second-round response disposition

The `audit_response.md` snapshot reviewed in this round explicitly stated that **no implementation changes were made** while preparing it. Therefore, that response did not alter the independently verified second-round status. The coder accepted the audit's central merge/release conclusion and all eight priority issue groups.

**Accepted without material dispute:**

- non-default separator corruption is High and must be corrected first;
- deleting/duplicating an inactive note can lose the active note's pending save;
- `commitLoaded()` has a second suspension without final generation revalidation;
- aggregate budgets remain incomplete outside the primary parser/formatter path;
- snapshot/history work and lifecycle flushing do not provide a complete durability barrier;
- settings/import transactions can still race or lose state; and
- protected release execution and final artifact governance remain unverified.

**Accepted qualifications:**

- H-01 remains Partial because direct-model, UTF-8 sizing, TXT export, and derived-total gaps exist, but the original parsed-user-input amplification scenario is substantially closed. The coder correctly declines to claim every bypass is remotely or user reachable.
- M-10 is labeled **Regressed** because remediation introduced a new inactive-note save-loss race; the original active-note resurrection path is fixed.
- The `release` environment and its reviewers/secrets are external GitHub configuration, not facts provable from repository source. The audit records the observed remote state separately from source declarations.
- TalkBack node behavior, actual large-font clipping, keypad geometry, and the Settings snackbar requirement are treated as static risks or verification items where device evidence is still needed.
- Missing instrumentation/API-level/minified-runtime tests keep M-19 Partial; they are verification gaps, not proof of a new runtime defect.
- The release workflow now contains fingerprint verification, provenance, and attestation logic, so audit wording must not describe those controls as absent. What remains unproven is their protected execution and end-to-end effectiveness.

No High finding was disputed in that second-round response. Its agreement/qualification wording did not change the historical **16 fixed / 22 partial / 1 regressed** disposition or seven remaining High-severity issues.

### Independent validation

- Forced fresh JVM execution with `--rerun-tasks`: **204 tests, 0 skipped, 0 failures, 0 errors** (`app/build/test-results/testDebugUnitTest/TEST-com.npnpatidar.ncal.TapeEngineTest.xml:2`).
- `:app:lintDebug :app:assembleDebug`: **BUILD SUCCESSFUL** with the documented AAPT2 override; lint reports **0 errors, 14 warnings**.
- Forced `:app:lintRelease :app:bundleRelease --rerun-tasks` with a temporary local test key covered only in the deferred release appendix: **BUILD SUCCESSFUL**; R8, resource shrinking, signing, and mapping completed.
- `jarsigner -verify` accepted the AAB, and `keytool -printcert -jarfile` showed the expected test-key SHA-256 fingerprint. The test certificate is intentionally self-signed and short-lived, so its trust/expiry warnings are expected and do not validate a production key.
- A release task without signing properties failed during configuration as intended: `app/build.gradle.kts:38`.
- GitHub currently reports **zero configured environments**; `release` is not yet a protected environment. The remediation is uncommitted, and no post-remediation GitHub Actions run exists.
- No Android instrumentation, connected tests, API-level matrix, or minified runtime test exists.

### Current finding status

| ID | Current severity | Status | Second-round result |
|---|---|---|---|
| H-01 | High | **Partial** | Parsed aggregate expansion is bounded, but direct-model values, UTF-8 sizing, derived sums, and TXT export bypass parts of the budget. |
| H-02 | High | **Fixed** | Atomic note-body replacement and typed UI-visible failures remain intact. |
| H-03 | High | **Partial** | Null-load safety is fixed, but `commitLoaded()` has a second suspension without rechecking the edit generation. |
| H-04 | High | **Fixed** | Release diagnostics remain debug-gated, bounded, and free of tape bodies. |
| H-05 | High | **Partial** | Bulk I/O moved off Main, but large-document snapshot/history work and lifecycle durability remain incomplete. |
| H-06 | High | **Partial** | Q/pre-Q serialization and sharing exist; pending-row recovery and provider-error fail-closed behavior remain. |
| H-07 | High | **Fixed** | Source-scale operands are preserved. |
| H-08 | High | **Fixed** | Leading chains remain corrected. |
| H-09 | High | **Fixed** | Verified Gradle wrapper/checksum and dependency verification remain effective. |
| H-10 | High | **Partial** | Signed test-key release works; protected production execution and final artifact identity are unproven. |
| M-01 | Medium | **Partial** | Production callers use the map overload; the legacy list overload remains unsafe. |
| M-02 | Medium | **Partial** | Exact audited examples are fixed; date-times, unmatched formulas, malformed grouping, and weak token boundaries remain. |
| M-03 | Medium | **Partial** | Complete headers validate, but one-separator headers can still be accepted and then self-invalidated. |
| M-04 | Medium | **Fixed** | Exact powers, reduced rational exponents, scale-relative convergence, and powered-result verification replace the unsafe fallback. |
| M-05 | Medium | **Partial** | Evaluator errors block export; parser warnings and neutralized invalid text still export successfully. |
| M-06 | **High** | **Partial** | Active metadata reaches parsing, but `TapeFormatter.pretty()` renders dots under comma-decimal metadata and can change values by 100× on reparse. |
| M-07 | Medium | **Fixed** | Crash-handler installation and date formatting remain safe/idempotent. |
| M-08 | Medium | **Partial** | Queue is bounded; aggregate/current-day/pre-Q crash retention remains unbounded. |
| M-09 | Medium | **Partial** | Streams close and reads are bounded, but binary content with a supported name is still accepted. |
| M-10 | **High** | **Regressed** | Active-note resurrection is fixed, but deleting or duplicating an inactive note cancels and does not replace the active note's pending save. |
| M-11 | Medium | **Fixed** | Original preference corruption/range defects remain fixed. |
| M-12 | Medium | **Partial** | Rich snapshots exist; settings undo is not persisted and settings updates can overwrite one another. |
| M-13 | Medium | **Partial** | Main lifecycle fixes remain; slider draft and Settings snackbar state are incomplete. |
| M-14 | Medium | **Partial** | Semantics improved; selected-node placement and adaptive keypad/large-font behavior remain. |
| M-15 | Medium | **Fixed** | Backup is globally disabled with explicit rules. |
| M-16 | Medium | **Fixed** | Pre-Q FileProvider URIs are now sent with `ClipData` and a read grant. |
| M-17 | Medium | **Partial** | Explicit validated properties replace commit count, but run-number/version-code governance is incomplete. |
| M-18 | Medium | **Partial** | The command-injection regression is fixed; action-runtime, deployment, signing-job, and artifact controls remain incomplete. |
| M-19 | Medium | **Partial** | Unit/debug/release build checks exist; device, API-level, minified-runtime, and protected-release verification do not. |
| L-01 | Low | **Fixed** | Whitespace-only section behavior remains correct. |
| L-02 | Low | **Fixed** | Line results remain ordered. |
| L-03 | Low | **Partial** | Normal note loads restore caret; export/import and CR-only cursor paths remain incomplete. |
| L-04 | Low | **Fixed** | Signed zero remains normalized. |
| L-05 | Low | **Fixed** | Parser/patcher line-ending handling remains correct. |
| L-06 | Low | **Partial** | Trailing comment whitespace survives; leading whitespace is still removed. |
| L-07 | Low | **Partial** | Note names are sanitized, but logger cleaning still misses several control characters. |
| L-08 | Low | **Fixed** | Totals, memory, redo, and marks remain exposed. |
| L-09 | Low | **Partial** | Core docs improved; protection/crash/caret claims remain overstated or environment-dependent. |
| L-10 | Low | **Fixed in working tree** | Adaptive icons build; untracked resources and missing monochrome layers remain delivery/polish gaps. |

### Priority issues still open

#### 1. Non-default separators are corrupted by pretty-printing

`TapeFormatter.pretty()` parses using active `CalcMeta` at `app/src/main/java/com/npnpatidar/ncal/tape/TapeFormatter.kt:17`, but renders every amount with a dot at `TapeFormatter.kt:25` and `TapeFormatter.kt:117`. With `DECSEP=,` and `THOUSEP=.`, input `1.234,50` parses as `1234.50`, is rendered as `1234.50`, and reparsing under the same metadata removes the dot as grouping, producing `123450`.

This affects real import and note-load paths, which format canonical separator-aware text and then evaluate the result. Render using the active decimal/grouping separators and add a full pretty → parse → evaluate round-trip test for German metadata.

#### 2. Deleting or duplicating an inactive note can lose active-note edits

`deleteNote()` always cancels the pending save at `app/src/main/java/com/npnpatidar/ncal/ui/TapeViewModel.kt:237`, but persists the active snapshot only when the deleted ID is active (`:239`). `duplicateNote()` has the same cancel-without-replacement pattern at `TapeViewModel.kt:305`.

Reproduction: edit active note A, then within 800 ms delete or duplicate inactive note B. A's pending save is canceled; if the process dies before another edit/flush, A loses the latest change. Cancel pending work only for destructive active-note operations, or immediately replace it with a direct snapshot save.

#### 3. Note loading can still overwrite edits after its final generation check

`selectNote()` checks `editVersion` before calling `commitLoaded()` at `app/src/main/java/com/npnpatidar/ncal/ui/TapeViewModel.kt:153`. `commitLoaded()` then performs another asynchronous format operation and commits without rechecking the captured generation, ID, or text at `TapeViewModel.kt:835`. An edit during that suspension can be displaced. `createNote()` also detects a generation mismatch but fails to return before committing the new note at `TapeViewModel.kt:207`.

Pass an operation token into `commitLoaded()` and revalidate after every suspension and immediately before state assignment.

#### 4. Aggregate output budgets remain bypassable

- Direct public `TapeDoc` values can pass the estimate as “96 characters,” then `setScale()` expands before scientific fallback: `app/src/main/java/com/npnpatidar/ncal/tape/TapeModel.kt:87` and `TapeFormatter.kt:117`.
- The nominal byte budget counts UTF-16 code units, not UTF-8 bytes: `TapeFormatter.kt:63` and `app/src/main/java/com/npnpatidar/ncal/tape/CalcFile.kt:233`.
- TXT export appends input without the aggregate budget: `app/src/main/java/com/npnpatidar/ncal/export/CalcExport.kt:53`.
- Additive derived totals are not checked against `TapeLimits`: `app/src/main/java/com/npnpatidar/ncal/tape/TapeEvaluator.kt:38` and `:181`.

Apply limits to every public writer entry point, check before `setScale`, account using encoded byte length, and reject unsupported derived totals.

#### 5. Main-thread and lifecycle durability gaps remain

Every edit still performs snapshot/history byte scans on Main: `app/src/main/java/com/npnpatidar/ncal/ui/TapeViewModel.kt:501`, `:905`, and `:1022`. `flushNow()` launches a coroutine without making `onPause()` wait: `TapeViewModel.kt:723`. Rapid process termination can still lose the debounce window.

Use incremental history byte counters, move full scans off Main where possible, and define a lifecycle/durability mechanism that cannot be canceled before persistence.

#### 6. Provider recovery and release governance remain incomplete

- Interrupted pending MediaStore rows are deleted rather than recovered, and cleanup can miss collision-suffixed names: `app/src/main/java/com/npnpatidar/ncal/storage/MediaStoreHelper.kt:156` and `:230`.
- `findOwn()` converts provider query errors to “not found” rather than failing closed: `MediaStoreHelper.kt:85`.
- The release checksum embeds its build path and is not portable after artifact flattening: `.github/workflows/release.yml:165` and `:179`.
- The workflow accepts Android's maximum version code rather than Google Play's documented lower maximum and uses rerunnable `github.run_number`: `.github/workflows/release.yml:28` and `:70`.
- The `release` environment currently does not exist remotely; source alone cannot establish reviewers, branch/tag restrictions, or environment-scoped secrets.

Treat provider errors distinctly from absence, either recover or quarantine pending rows, generate checksums relative to the artifact root, and govern releases from a protected tag/version ledger.

#### 7. Settings and import transactions can still lose state

`updateSettings()` captures full settings objects before serializing at `TapeViewModel.kt:437`; rapid callbacks can overwrite each other. Undo/redo restores settings in memory but does not persist them (`TapeViewModel.kt:936`). Multi-file import updates the notes list outside the lifecycle mutex and can finish after a newer delete/rename.

Use per-field/versioned settings mutations, persist restored settings transactionally, and serialize or generation-check all operations that publish `notes`.

#### 8. Accessibility and documentation still need device-level validation

TalkBack note selection remains on a parent rather than the actionable row, adaptive keypad row budgets do not match actual column counts, and large-font rows can clip. Rotation can still lose an uncommitted slider draft, and Settings has no snackbar host. These remain Medium without instrumentation evidence, while documentation still overstates protection, crash storage, and caret round-tripping.

## First-round remediation verification (superseded)

The first-round status below is preserved for audit history. The second-round table above is the current source of truth.

### Verdict

**Do not accept the coder response as a complete remediation.** Of the 39 original findings, **16 are fixed, 20 are partially fixed, and 3 have regressed**. Seven issues remain or have reopened at High severity.

The remediation materially improves the project: note writes are atomic, release logging is gated, failed loads are transactional, the calculation-chain bug is fixed, operand precision is preserved, Gradle is upgraded and verified, and debug/release compilation succeeds. The remaining release workflow, aggregate rendering, power, persistence-race, and state-isolation issues should be resolved before merge or publication.

### Independent validation

- Forced a fresh JVM test run with `--rerun-tasks`: **201 tests, 0 skipped, 0 failures, 0 errors** (`app/build/test-results/testDebugUnitTest/TEST-com.npnpatidar.ncal.TapeEngineTest.xml:2`).
- `:app:testDebugUnitTest :app:lintDebug :app:assembleDebug`: **BUILD SUCCESSFUL** using the documented AAPT2/QEMU override.
- Debug lint: **0 errors, 16 warnings** (`app/build/reports/lint-results-debug.txt:172`).
- `:app:lintRelease :app:bundleRelease`: **BUILD SUCCESSFUL**; R8/resource shrinking, release lint, an unsigned AAB, and mapping output were produced.
- No Android instrumentation tests or connected/device tests exist. No post-remediation GitHub Actions run exists because the changes are uncommitted. A signed release and certificate fingerprint could not be verified without the protected signing key.

### Finding status

| ID | Current severity | Status | Verification summary |
|---|---|---|---|
| H-01 | High | **Partial** | Extreme single values are rejected, but many individually legal values can amplify rendered output by orders of magnitude. |
| H-02 | High | **Fixed** | Same-directory temporary write, file sync, atomic replacement, typed failure result, and UI reporting. |
| H-03 | High | **Fixed** | Target load/validation completes before active identity and content are committed. |
| H-04 | High | **Fixed** | Release file/crash persistence is disabled and sensitive values are redacted. |
| H-05 | High | **Partial** | Most I/O moved to IO, but lifecycle flush can be cancelled and meaningful per-edit work remains on Main. |
| H-06 | High | **Partial** | Q+ pending publication and path scoping exist; pre-Q races, owner fallback, and interrupted pending rows remain. |
| H-07 | High | **Fixed** | Source-scale operands are preserved through pretty-printing and canonical writing. |
| H-08 | High | **Fixed** | Leading chains now track the actual target; the original `100 * 2 * 3` defect is corrected. |
| H-09 | High | **Fixed** | Gradle 9.6.1 wrapper/checksum, strict verification metadata, and filtered repositories are present and exercised. |
| H-10 | High | **Partial** | Target 36, R8, lint, and bundle work; signed/protected release governance is unverified and the release workflow is unsafe. |
| M-01 | Medium | **Partial** | New map overload is correct, but the legacy public list overload retains the original mismatch. |
| M-02 | Medium | **Partial** | Audited examples are fixed, but date-times, trailing separators, and unmatched formulas still bypass the guards. |
| M-03 | Medium | **Partial** | Complete-header validation is improved, but a header supplying only one separator can be accepted and then self-invalidated by the writer. |
| M-04 | **High** | **Regressed** | Removing `Double.pow` is good, but the new root convergence can silently return grossly inaccurate values and rejects valid forms. |
| M-05 | Medium | **Partial** | Evaluator errors block export; parser warnings and neutralized malformed input can still export as successful. |
| M-06 | Medium | **Partial** | UUID preservation improved, but editor text is parsed with default separators and async imports can mutate stale state. |
| M-07 | Medium | **Fixed** | Handler installation is idempotent and date formatters are immutable. |
| M-08 | Medium | **Partial** | Queue is bounded, but current-day size, total file count, aggregate bytes, and crash retention are not bounded. |
| M-09 | Medium | **Partial** | Streams close and picker MIME is narrowed, but supported-name binary content is still accepted and decoded permissively. |
| M-10 | **High** | **Regressed** | Direct delete failure is handled, but a pending autosave can recreate the deleted file after deletion. |
| M-11 | Medium | **Fixed** | Central sanitization, type-safe preference reads, and slider commit-on-release are present. |
| M-12 | Medium | **Partial** | Rich snapshots and selection restoration exist; settings undo is not persisted and large/async operations can invalidate history. |
| M-13 | Medium | **Partial** | Saveable dialogs, Back handling, and IME control exist; drawer/slider transient state and Settings snackbar behavior remain. |
| M-14 | Medium | **Partial** | Major semantics and descriptions were added, but selected-state placement and adaptive-keypad/large-font behavior remain incomplete. |
| M-15 | Medium | **Fixed** | Backup is globally disabled and exclusion rules are declared. |
| M-16 | Medium | **Partial** | Pre-Q paths/provider are corrected, but returned FileProvider URIs are never actually shared. |
| M-17 | Medium | **Partial** | Commit-count versioning is gone; release version validation remains fail-open and the run number is not a protected ledger. |
| M-18 | **High** | **Regressed** | Pinning and permissions improved, but the new signing workflow interpolates an attacker-controllable ref into shell source. |
| M-19 | Medium | **Partial** | JVM tests, debug lint/assembly, release lint/bundle, and mapping exist; instrumentation, API matrix, signature verification, and release upgrade tests do not. |
| L-01 | Low | **Fixed** | Whitespace-only lines now begin independent sections. |
| L-02 | Low | **Fixed** | Line results are returned in document order. |
| L-03 | Low | **Partial** | Normal note loads restore caret metadata; import/export and CR-only/selection-save paths remain incomplete. |
| L-04 | Low | **Fixed** | Signed zero is normalized. |
| L-05 | Low | **Fixed** | Parsing/patching support LF, CRLF, and CR; editor operations remain LF-oriented. |
| L-06 | Low | **Partial** | Trailing comment whitespace is preserved, but leading comment whitespace is still lost. |
| L-07 | Low | **Fixed** | Diagnostic control characters are sanitized and fields bounded/redacted. |
| L-08 | Low | **Fixed** | Grand total, memory, redo, and line-mark styling are now exposed. |
| L-09 | Low | **Partial** | Core documentation improved, but release protection and pre-Q sharing claims remain overstated. |
| L-10 | Low | **Fixed in working tree** | Adaptive resources exist and build; ensure the untracked resource tree is included when changes are committed. |

### Priority issues still open

#### 1. Aggregate rendering can still exhaust memory

Individual `1e9999` values are accepted and each renders to roughly 10,000 characters. A file of about 140 KB containing approximately 20,000 such lines can drive `TapeFormatter.pretty()` and `CalcFile.write()` toward roughly 200 MB of output. The relevant per-value and estimated-width limits are in `app/src/main/java/com/npnpatidar/ncal/tape/TapeModel.kt:64`, `app/src/main/java/com/npnpatidar/ncal/tape/TapeFormatter.kt:23`, and `app/src/main/java/com/npnpatidar/ncal/tape/CalcFile.kt:204`. Enforce aggregate rendered-byte and allocation budgets, not only per-value limits.

#### 2. The release workflow permits command injection with signing secrets

Release signing secrets are present in the release step at `.github/workflows/release.yml:39`, while `${{ github.ref_name }}` is directly inserted into shell source at `.github/workflows/release.yml:49`. A crafted tag can break out of the quoted argument and execute commands with access to signing credentials. Pass contexts through `env`, validate tags, and use a protected release environment before any signing secret is available.

#### 3. Fractional powers can silently return inaccurate results

The new `integerRoot()` convergence threshold is absolute (`app/src/main/java/com/npnpatidar/ncal/tape/TapeEvaluator.kt:367`). For a tiny valid base such as `2e-10000`, exponent `0.5`, it can converge after one inaccurate Newton step and silently return approximately `1.61051e-5000` instead of `1.41421e-5000`. `0^0.5` is also rejected, and fractions are not reduced, causing valid roots such as `4^0.25` and `(-32)^0.2` to fail. Convergence must be relative to the result scale and independently verified by exponentiation.

#### 4. Autosave can resurrect a deleted note

Deleting the active note does not cancel or join its pending debounced save (`app/src/main/java/com/npnpatidar/ncal/ui/TapeViewModel.kt:178` and `:702`). If the delayed save acquires the save mutex after deletion, it recreates the file; orphan adoption then lists the note again. Serialize delete and save under one operation lock, invalidate the note generation, and verify absence after all pending work is joined.

#### 5. Note switching can overwrite edits made while loading

`selectNote()` snapshots the old text before an asynchronous load, but commits the loaded state without checking that the current text/ID is still the snapshot (`app/src/main/java/com/npnpatidar/ncal/ui/TapeViewModel.kt:134`). A user can type during a slow load and have the edit displaced. Use a generation token or serialized editor operation and surface any unsaved displaced change.

#### 6. Stale imports can mutate the active note's metadata/settings

`importText()` updates global decimals, persisted settings, and `meta` before checking whether the original text is still current (`app/src/main/java/com/npnpatidar/ncal/ui/TapeViewModel.kt:503`). A discarded or superseded import can therefore change state. Also parse headerless editor text with the active decimal/thousands separators rather than default metadata; otherwise current `.calc` export can attach a German header to a US-parsed amount.

#### 7. Export durability is incomplete across supported API levels

API 29+ pending publication is improved, but an owner-query failure drops owner scoping (`app/src/main/java/com/npnpatidar/ncal/storage/MediaStoreHelper.kt:84`), interrupted pending rows are not recovered, and API 26–28 same-name selection occurs outside the write lock (`MediaStoreHelper.kt:38`). FileProvider URIs are created but no share/open intent is launched.

#### 8. Release remains unproven and unprotected

The unsigned R8 bundle and release lint pass locally, but no signed AAB, certificate fingerprint verification, protected environment, source-to-artifact provenance, or post-change CI run exists. Version properties are not strictly validated (`app/build.gradle.kts:6`), and release mapping upload is warning-only (`.github/workflows/release.yml:61`).

## Original audit assessment (baseline)

The original finding text below is preserved as the baseline audit. The remediation status table above supersedes it where outcomes differ. Baseline source line numbers refer to revision `e22a9a5` and may not match the remediated working tree.

The project has a strong, testable calculation core and a sensible minimal-permission design. Its 189 JVM unit tests provide unusually broad coverage of the parser, evaluator, formatter, and editor. The ordinary tape flows—comments, precedence, percentages, stale balance healing, and canonical `.calc` writing—are thoughtfully implemented.

The application is nevertheless **high risk for storing financial notes or distributing through Google Play in its current state**. The most important issues are:

1. user-selected numeric/header values can trigger extreme memory allocation;
2. primary note writes are non-atomic and save failures are hidden;
3. a failed note load can associate the previous note's text with a different note ID;
4. production logging persists note content in user-visible storage;
5. import, export, autosave, and lifecycle flushing run synchronously on the UI thread;
6. display formatting and serialization can change later arithmetic;
7. a valid chained-multiplication scenario calculates incorrectly;
8. export replacement is destructive and can collide with other names;
9. CI uses a Gradle version affected by 2026 supply-chain advisories; and
10. the project is not ready for a current Play release.

There is no evident remote-code-execution path, broad storage permission, network permission, or custom exported provider. The principal attack surface is untrusted user-picked content, local storage failure, exported/imported documents, and sensitive diagnostics.

### Severity model

- **Critical:** broad compromise or catastrophic, remotely triggerable impact.
- **High:** credible data loss/corruption, core calculation failure, denial of service, privacy exposure, or release/supply-chain blocker.
- **Medium:** substantial reliability, accessibility, compatibility, or maintainability defect.
- **Low:** limited edge case, incomplete feature, documentation drift, or code-quality concern.

This audit records **0 Critical, 10 High, 19 Medium, and 10 Low** findings.

## System map

| Layer | Main files | Responsibility |
|---|---|---|
| Tape domain | `tape/TapeModel.kt`, `TapeEvaluator.kt`, `CalcFile.kt`, `TapeFormatter.kt`, `TapeEdit.kt` | Parse/edit/evaluate/format canonical and display-oriented tape text |
| State | `ui/TapeViewModel.kt` | Single `StateFlow`, editing, undo/redo, memory, imports/exports, autosave |
| UI | `ui/TapeScreen.kt`, `ui/SettingsScreen.kt` | Compose editor, drawer, dialogs, keypad, gestures, settings |
| Primary storage | `storage/NotesRepository.kt` | App-private canonical `.calc` files and note metadata |
| Shared storage | `storage/MediaStoreHelper.kt` | API 29+ MediaStore and API 26–28 external app-specific fallback |
| Import/export | `export/CalcExport.kt` | Header optional import and `.calc`/`.txt` export |
| Diagnostics | `logging/NcalLogger.kt` | Logcat, daily files, ring buffer, crash handler |
| Delivery | Gradle files and `.github/workflows/build-apk.yml` | Debug APK build and JVM unit tests |

The tracked Kotlin footprint is 4,991 lines: 3,457 production lines and 1,534 test lines. `TapeScreen.kt` (876 lines) and `TapeViewModel.kt` (659 lines) are the largest maintenance centers.

## Risk summary

| ID | Severity | Area | Finding |
|---|---|---|---|
| H-01 | High | Availability | Unbounded numeric and header scale can trigger huge allocation |
| H-02 | High | Persistence | Note writes are non-atomic and failures are hidden |
| H-03 | High | State integrity | Failed note load can save old text under a new note ID |
| H-04 | High | Privacy | Release logging exposes note content to shared storage |
| H-05 | High | Concurrency | Import/export/save and full-tape work run on the UI thread |
| H-06 | High | Files | Export replacement is destructive, name-based, and weakly scoped |
| H-07 | High | Correctness | Formatting and canonical writes change entry values and future math |
| H-08 | High | Correctness | Consecutive leading multiplicative operators use the wrong value |
| H-09 | High | Supply chain | CI uses Gradle 8.7, affected by 2026 advisories |
| H-10 | High | Release | Target SDK and release workflow block a current Play release |
| M-01 | Medium | Serialization | Writer associates balances with subtotals by unrelated list position |
| M-02 | Medium | Parsing | Permissive/ambiguous input can silently change values |
| M-03 | Medium | Metadata | Malformed header-like text can reset global decimals |
| M-04 | Medium | Numerics | Power fallback converts through `Double` and can return wrong results |
| M-05 | Medium | Export | Invalid calculations are exported and reported as successful |
| M-06 | Medium | Identity | Current-note import/export loses active metadata or note UUID |
| M-07 | Medium | Diagnostics | Crash handler stacks and uses non-thread-safe formatters |
| M-08 | Medium | Availability | Logger queue and on-device log retention are unbounded |
| M-09 | Medium | Resources | Import readers are unclosed and the picker accepts any MIME type |
| M-10 | Medium | Persistence | Failed deletion removes metadata and allows resurrection |
| M-11 | Medium | Settings | Preferences can crash startup; updates are not centrally validated |
| M-12 | Medium | Editing | Undo/redo state is incomplete and can lose redo history |
| M-13 | Medium | Lifecycle | Dialogs/settings are lost on rotation; Back and IME behavior are inconsistent |
| M-14 | Medium | Accessibility | Controls lack reliable labels, state semantics, and touch-target support |
| M-15 | Medium | Privacy | Backup is enabled without an explicit data-extraction policy |
| M-16 | Medium | Compatibility | API 26–28 storage paths contradict user documentation |
| M-17 | Medium | Reproducibility | Commit count is used as a non-monotonic application version |
| M-18 | Medium | CI security | Actions, permissions, wrapper, and dependency integrity are not pinned |
| M-19 | Medium | Verification | CI omits lint, instrumentation, API-level, and release checks |
| L-01…L-10 | Low | Miscellaneous | Edge semantics, incomplete state, and documentation drift; see below |

## Detailed high-severity findings

### H-01 — Unbounded numeric and metadata scale can cause memory exhaustion

**Evidence**

- Scientific notation accepts an unrestricted exponent: `app/src/main/java/com/npnpatidar/ncal/tape/CalcFile.kt:27`.
- Imported `DECIMALS` is not bounded: `app/src/main/java/com/npnpatidar/ncal/tape/CalcFile.kt:76`.
- Formatting expands values with `setScale(...).toPlainString()`: `app/src/main/java/com/npnpatidar/ncal/tape/TapeFormatter.kt:76`, `app/src/main/java/com/npnpatidar/ncal/tape/CalcFile.kt:204`, and `app/src/main/java/com/npnpatidar/ncal/ui/TapeViewModel.kt:631`.
- Import reaches formatting through `app/src/main/java/com/npnpatidar/ncal/storage/NotesRepository.kt:97`.

**Reproduction scenario**

Import a small `.txt` or `.calc` containing either:

```text
 + 1e100000000
```

or:

```text
<SFRCalculatorHeader>
DECIMALS=100000000
</SFRCalculatorHeader>
 + 1
```

`BigDecimal` stores the scale compactly, but later plain-string rendering attempts to allocate a string with roughly 100 million characters. A larger exponent or scale can terminate the process. The `^` exponent cap in `TapeEvaluator.kt:51` does not bound ordinary scientific literals or imported display precision.

**Expected:** reject excessive magnitude, scale, token length, or output width before materializing text.  
**Actual:** a short user-selected file can cause a long stall, heap exhaustion, or process death.

**Recommendation:** validate all metadata at parse time; cap decimal places, adjusted exponent, significant digits, and rendered width; return explicit parse/import errors; use bounded or scientific presentation where appropriate. Add boundary tests for ordinary literals, headers, and writer output.

### H-02 — Primary note writes are non-atomic and failures are hidden

**Evidence**

Direct `File.writeText()` calls overwrite the final note path during create, duplicate, import, and save:

- `app/src/main/java/com/npnpatidar/ncal/storage/NotesRepository.kt:58`
- `app/src/main/java/com/npnpatidar/ncal/storage/NotesRepository.kt:80`
- `app/src/main/java/com/npnpatidar/ncal/storage/NotesRepository.kt:97`
- `app/src/main/java/com/npnpatidar/ncal/storage/NotesRepository.kt:143`

Duplicate/import failures are returned inconsistently, while normal save catches every `Throwable` and returns no status: `app/src/main/java/com/npnpatidar/ncal/storage/NotesRepository.kt:90`, `:108`, and `:150`.

**Reproduction scenario**

Fill storage, make the directory unwritable, or terminate the process while an autosave is writing. `writeText` can truncate the previous valid note before the replacement is durable. The failure is logged, but the UI continues to present the in-memory note as saved. A truncated orphan may later be adopted from disk: `NotesRepository.kt:33`.

**Expected:** preserve the last valid version and report unsuccessful persistence to the user.  
**Actual:** partial files can replace valid notes and failures are not surfaced.

**Recommendation:** write to a temporary file in the same directory, flush it, atomically rename it into place, and return a typed save result. Keep metadata and file state recoverable, and fault-inject write failures in tests.

### H-03 — A failed note load can attach the previous tape to a new note ID

**Evidence**

- `selectNote()` saves the old note, calls `loadNote()`, and reevaluates: `app/src/main/java/com/npnpatidar/ncal/ui/TapeViewModel.kt:107`.
- On load failure, only `notes`, `noteId`, and `noteName` change: `app/src/main/java/com/npnpatidar/ncal/ui/TapeViewModel.kt:223` and `:242`.
- Later autosave uses the new ID with the unchanged old text: `app/src/main/java/com/npnpatidar/ncal/ui/TapeViewModel.kt:625`.

**Reproduction scenario**

Open note A. Corrupt, remove, or make note B unreadable after B appears in the drawer, then select B. A's `tapeText` remains under B's name. The next edit can write A's content into B or recreate B with unrelated data.

**Expected:** do not switch identity unless a valid note is loaded; otherwise retain A intact and show a recoverable error.  
**Actual:** identity and content can diverge, causing data misassociation or loss.

**Recommendation:** load into a temporary state and commit the switch only on success. On failure, leave the current editor state unchanged or clear it into an explicit safe state; never schedule a save under an unvalidated ID.

### H-04 — Production diagnostics persist sensitive note content

**Evidence**

- File logging and debug level are enabled unconditionally: `app/src/main/java/com/npnpatidar/ncal/logging/NcalLogger.kt:30`.
- Every enabled event is queued for file persistence: `NcalLogger.kt:97`.
- Closing a block logs the tape, including up to 1,500 characters: `app/src/main/java/com/npnpatidar/ncal/ui/TapeViewModel.kt:390`, `:406`, and `:634`.
- Names, totals, memory values, settings, URIs, and save events are also logged throughout the repository/export paths.
- On API 29+, files are placed under user-visible `Download/ncal`: `app/src/main/java/com/npnpatidar/ncal/storage/MediaStoreHelper.kt:23` and `:118`.

**Reproduction scenario**

Enter a note containing a client name, account, or transaction comment, press `=`, then inspect `Download/ncal/ncal-YYYY-MM-DD.log`. The note excerpt is present even in a release build.

**Expected:** release builds should not persist note bodies, amounts, identifiers, or URIs by default.  
**Actual:** exhaustive diagnostics are retained in shared storage with no redaction policy or user opt-in.

**Recommendation:** gate verbose/file logging behind `BuildConfig.DEBUG`; make production logging minimal and redacted; require explicit opt-in for user-visible diagnostics; use app-private storage by default; document any data that may be collected.

### H-05 — Import, export, save, and startup perform blocking work on the main thread

**Evidence**

- ViewModel initialization enumerates notes, creates a note, loads content, and evaluates synchronously: `app/src/main/java/com/npnpatidar/ncal/ui/TapeViewModel.kt:71`.
- File-picker results are read and imported inline: `TapeViewModel.kt:187`.
- Exports parse, evaluate, query, delete, insert, and write inline: `TapeViewModel.kt:169` and `:477`.
- Every edit reparses and evaluates the complete tape, then may parse/evaluate it a second time after patching balances: `TapeViewModel.kt:296` and `:540`.
- Debounced save uses `viewModelScope` without an I/O dispatcher, so it resumes on Main: `TapeViewModel.kt:617`.
- Lifecycle pause explicitly calls synchronous save: `app/src/main/java/com/npnpatidar/ncal/MainActivity.kt:37`.

**Reproduction scenario**

Import or paste a large document, then edit it. The callback can freeze the UI, consume substantial memory, and perform repeated whole-document passes. Undo retains up to 50 complete strings, not a byte budget: `TapeViewModel.kt:63` and `:654`.

**Expected:** bounded reads, I/O on a serialized background dispatcher, explicit progress/error state, and scalable evaluation.  
**Actual:** a user-selected file can block the main thread; each keystroke is O(total tape size), with potentially O(50 × tape size) history memory.

**Recommendation:** reject oversized content before full reading; parse/write on `Dispatchers.IO`; serialize saves to avoid races; make note switching transactional; cap undo by bytes; debounce evaluation/layout separately; profile and introduce incremental evaluation where needed.

### H-06 — Export replacement is destructive, collision-prone, and weakly scoped

**Evidence**

- Export names are reduced to ASCII and truncated: `app/src/main/java/com/npnpatidar/ncal/export/CalcExport.kt:79`.
- MediaStore lookup filters by display name only, not `RELATIVE_PATH`, owner, or a stable app identifier: `app/src/main/java/com/npnpatidar/ncal/storage/MediaStoreHelper.kt:78`.
- Replacement deletes the matching row before inserting/writing the new row: `MediaStoreHelper.kt:135`.
- API 26–28 directly truncates the existing external file: `MediaStoreHelper.kt:45`.

**Reproduction scenario**

Two notes named in scripts that sanitize to the same value, or long names with the same 64-character prefix, export to one filename. The second export replaces the first. A same-named accessible row elsewhere in Downloads is also queried; provider permissions may prevent access, but the code does not scope the operation defensively. If the new write fails, the old export is already gone and a partial new row may remain visible.

**Expected:** unique names, exact folder/owner scoping, and atomic/pending publication.  
**Actual:** prior exports can be silently destroyed or replaced by partial data.

**Recommendation:** scope queries to the exact app directory; use `IS_PENDING` and publish only after a complete write; avoid delete-before-success; use a stable ownership record or collision-resistant suffix; provide an explicit overwrite policy.

### H-07 — Display formatting and persistence change entry values and future math

**Evidence**

The evaluator explicitly promises display-only rounding at `app/src/main/java/com/npnpatidar/ncal/tape/TapeEvaluator.kt:44`. However:

- `TapeFormatter.pretty()` rounds every entry to display precision: `app/src/main/java/com/npnpatidar/ncal/tape/TapeFormatter.kt:53` and `:76`.
- Loading replaces editor state with that pretty text: `app/src/main/java/com/npnpatidar/ncal/ui/TapeViewModel.kt:223`.
- Canonical writing rounds every entry: `app/src/main/java/com/npnpatidar/ncal/tape/CalcFile.kt:204`.
- Save reparses the already rounded text and writes it again: `app/src/main/java/com/npnpatidar/ncal/storage/NotesRepository.kt:143`.

**Reproduction scenario**

With two displayed decimals:

```text
 + 0.005
 * 100
```

The exact in-memory result is `0.50`. After pressing `=`, loading, or saving/reloading, the entry can become `0.01`, and the chain becomes `1.00`.

**Expected:** presentation must not change source operands used by later calculations.  
**Actual:** changing precision, closing a block, or saving can change future arithmetic.

**Recommendation:** keep exact source text/model separate from rendered display; never replace the source with pretty output; define whether `.calc` persistence intentionally quantizes operands. If not, preserve source precision and render a separate view. Add save/load/pretty idempotence tests beyond already-rounded fixtures.

### H-08 — Consecutive leading multiplicative operators calculate the wrong value

**Evidence**

For the first `*`, `/`, or `^` in a block, the evaluator computes a target but stores `target - base` in `cur`: `app/src/main/java/com/npnpatidar/ncal/tape/TapeEvaluator.kt:155`. Later multiplicative operators apply to `cur`: `TapeEvaluator.kt:196`.

**Reproduction scenario**

```text
 + 100
 ------------------
 + 100
 * 2
 * 3
```

Expected total: `600`. Actual total: `400`. The first operation produces delta `100`; the second multiplies that delta to `300`, which is added to base `100`. Division and power chains have the same representation problem.

**Expected:** the chain value should track the actual running target.  
**Actual:** subsequent operators act on a delta.

**Recommendation:** represent the current target and additive base separately, or set the first chain operand to the target and track the base delta once. Add two-or-more-operator regression tests for `*`, `/`, and `^` after a balance.

### H-09 — CI uses a Gradle release affected by 2026 advisories

**Evidence**

- CI installs Gradle 8.7: `.github/workflows/build-apk.yml:30`.
- Multiple remote repositories are configured: `settings.gradle.kts:2`.
- No Gradle dependency-verification metadata or wrapper is tracked.

Gradle advisories [GHSA-w78c-w6vf-rw82](https://github.com/gradle/gradle/security/advisories/GHSA-w78c-w6vf-rw82) and [GHSA-mqwm-5m85-gmcv](https://github.com/gradle/gradle/security/advisories/GHSA-mqwm-5m85-gmcv) describe repository-fallback behavior that can allow artifacts from a different repository after an availability/DNS failure. Versions below 8.14.4 are in the affected 8.x range; 9.x is patched at 9.3.0.

**Expected:** CI uses a patched, project-pinned, integrity-verified Gradle distribution.  
**Actual:** the build environment is pinned to an affected release and is not self-contained.

**Recommendation:** upgrade to a patched Gradle compatible with the selected AGP, commit the wrapper and distribution checksum, enable dependency verification, and add repository content filtering. Treat this as a build-host supply-chain issue, not an APK runtime vulnerability.

### H-10 — The project is not ready for a current Google Play release

**Evidence**

- `compileSdk` and `targetSdk` are 34: `app/build.gradle.kts:19`.
- CI installs only SDK/build-tools 34 and publishes a debug APK: `.github/workflows/build-apk.yml:24` and `:40`.
- The release build disables minification and defines no signing configuration: `app/build.gradle.kts:31`.
- No release workflow, bundle/signing configuration, mapping artifact, or provenance publication is tracked.

As of 2026-09-24, Google's [target API requirement](https://developer.android.com/google/play/requirements/target-sdk) requires new phone/tablet apps and updates to target API 36 beginning 2026-08-31. Target 34 is below that requirement.

**Expected:** a current signed release path is reproducible and tested.  
**Actual:** only a GitHub debug artifact is documented; the release build is not signed or production-configured.

**Recommendation:** upgrade AGP/Kotlin/Compose and SDKs together, target/compile API 36, add API 26/current-target test coverage, commit the wrapper, configure protected signing, enable tested R8 rules, and publish checksummed/provenance-bound APK or AAB artifacts.

## Detailed medium-severity findings

### M-01 — Balance values are paired with separator subtotals by unrelated list index

`TapeEvaluator.subtotals` records every separator (`TapeEvaluator.kt:76`), while `CalcFile.write()` consumes that list once per balance (`CalcFile.kt:181`). These are not one-to-one when bare separators, multiple separators, or intervening structures exist.

Example:

```text
 + 10
 ------------------

 + 20
 ------------------
 + 20
```

There are subtotals `[10, 20]` but one balance. The writer emits `10` for the only balance instead of `20`. Live evaluation later snaps the stale value, but serialized content is wrong for external consumers.

**Recommendation:** return a map from balance line index to value, or have the writer compute the running total at each balance directly. Test bare and repeated separators.

### M-02 — Permissive and ambiguous parser input can silently change values

Relevant paths are `CalcFile.kt:27`, `:212`, and `:274`. Examples include:

- Headerless `0,123` becomes `123`, because only one or two digits after a comma are treated as a decimal under default metadata.
- Bare `2026-09-21` tokenizes as `+2026`, `-09`, `-21` and evaluates to `1996`.
- `2*(3+4)` starts with a valid digit, bypasses the bracket warning, and evaluates as `6` rather than remaining an unsupported formula.
- `.5.6` accepts `.5` and turns `.6` into a comment.
- Currency and Unicode support is narrower than comments may imply.

Some ambiguity is inherent without locale metadata, but silent 100× or semantic changes are dangerous for financial input.

**Recommendation:** make locale policy explicit, require metadata or an explicit separator setting for ambiguous comma numbers, recognize dates/parenthesized formulas before numeric tokenization, require a full numeric token boundary, and surface ambiguous-import warnings.

### M-03 — Malformed header-like text can reset global decimals

`CalcFile.hasHeader()` checks only for the opening marker (`CalcFile.kt:40`), while parsing requires a later closing marker (`CalcFile.kt:68`). Import can therefore treat malformed header text as body, receive default metadata, and call `adoptDecimals(5)`: `TapeViewModel.kt:202` and `:464`.

**Recommendation:** expose a validated-header result rather than a substring predicate; reject malformed or incomplete headers without changing global settings.

### M-04 — Power operations fall back to `Double` and can silently return wrong values

`TapeEvaluator.kt:276` converts non-integer and negative integer exponents through `Double.pow()`: `TapeEvaluator.kt:297`.

Examples:

- `5^-1` returns a binary approximation rather than exact decimal `0.2` under the stated BigDecimal model.
- `1e400^-1` converts the base to infinity, computes zero, and silently returns zero.
- `1e400^0.5` is rejected even though `1e200` is representable by `BigDecimal`.

**Recommendation:** define exact supported exponent semantics, use a scale-aware decimal/rational strategy where appropriate, detect non-finite intermediates, and never silently substitute zero.

### M-05 — Invalid calculations are exported and reported as successful

Export logs evaluator errors but still writes files: `app/src/main/java/com/npnpatidar/ncal/export/CalcExport.kt:22` and `:47`. The ViewModel reports success based only on a non-null URI: `TapeViewModel.kt:169` and `:477`.

A `.txt` export containing division by zero can therefore show a plausible but misleading ordinary grand total.

**Recommendation:** return evaluation status with export results, block export by default when errors exist, or require an explicit “export with errors” choice and include error markers in every format.

### M-06 — Current-note import/export loses active metadata or note identity

Current-note export passes only headerless editor text (`TapeViewModel.kt:169` and `:477`; `CalcExport.kt:22`). Reparse creates default decimals/separators and a new UUID (`TapeModel.kt:48`). Conversely, `importText()` assigns a newly parsed UUID to the current note despite its persistence identity (`TapeViewModel.kt:491`).

**Recommendation:** pass the current `CalcMeta` explicitly into import/export; define whether replacing a note preserves the note UUID or adopts the imported document UUID, then test both live and background-note exports.

### M-07 — Crash diagnostics stack handlers and share unsafe formatters

`MainActivity.onCreate()` installs a handler on every Activity creation (`MainActivity.kt:14`). Each installation wraps the previous default handler (`NcalLogger.kt:67`), so recreation builds a chain of wrappers, each performing synchronous storage on a fatal exception. `SimpleDateFormat` instances are shared across normal logging and concurrent crash paths (`NcalLogger.kt:35`). Crash names have one-second resolution and overwrite same-name files.

**Recommendation:** install once per process from an `Application`; make installation idempotent; use thread-safe `DateTimeFormatter`; use unique crash names; bound fatal-path work and add an app-private fallback.

### M-08 — Logger queue and on-device retention are unbounded

A `newSingleThreadExecutor()` has an unbounded task queue (`NcalLogger.kt:34`), one task is created per event (`NcalLogger.kt:105`), and daily files have no size/retention limit (`NcalLogger.kt:157`). A stalled provider or sustained event rate can grow memory and shared-storage use while failures remain mostly invisible.

**Recommendation:** use a bounded queue/drop policy, rotate by size, retain a bounded number of days/files, back off after provider failure, and expose degraded diagnostic state without blocking calculation.

### M-09 — Import readers are not explicitly closed and MIME selection is unrestricted

`TapeViewModel.kt:196` calls `bufferedReader().readText()` without `use`, so stream ownership is not explicit. The picker launches with `*/*`: `TapeScreen.kt:249`.

Repeated cloud-provider imports can leak descriptors/pipes until finalization, and arbitrary binary files are fully decoded as text.

**Recommendation:** nest input/reader closure in `use`; constrain selection by supported MIME/extensions where supported; validate content and size before full read.

### M-10 — Failed note deletion removes metadata and allows resurrection

`NotesRepository.delete()` removes order/name metadata even when `File.delete()` returns false: `NotesRepository.kt:124`. A later `list()` adopts the surviving orphan under a generated-looking name (`NotesRepository.kt:33`).

**Recommendation:** only remove metadata after confirmed deletion, or retain explicit failed-deletion state and retry/recovery behavior.

### M-11 — Settings loading can crash and updates lack a single validation boundary

`SettingsStore.load()` guards enum parsing but not wrong value types (`AppSettings.kt:32`), so a corrupted/migrated preference can throw during ViewModel construction. `TapeViewModel.updateSettings()` accepts arbitrary values (`TapeViewModel.kt:253`), while controls and storage duplicate ranges. Settings sliders persist on every drag frame (`SettingsScreen.kt:94`).

**Recommendation:** make settings parsing total and default-safe; introduce one `sanitize()`/factory boundary; validate finite floats and ranges; preview sliders locally and persist on release.

### M-12 — Undo/redo state is incomplete

Only text snapshots are retained (`TapeViewModel.kt:63`), so undo/redo always move the caret to document end (`:420`). `onTapeChange()` pushes undo even when only selection changed (`:296`), potentially clearing redo. Settings reformatting and metadata-changing imports are not represented in one transaction.

**Recommendation:** snapshot text plus selection and the relevant metadata/settings transaction; ignore selection-only callbacks when text is unchanged; expose a reachable redo action or remove dead state.

### M-13 — Transient UI state and system navigation are inconsistent

Dialogs, rename text, and settings visibility use `remember` rather than `rememberSaveable` (`TapeScreen.kt:134`), so rotation can discard input. Settings has no `BackHandler`; system Back can exit the Activity instead of returning to the tape. Switching to Hide/Calc does not explicitly dismiss the IME (`TapeScreen.kt:153`).

**Recommendation:** use saveable state for dialogs and drafts; register hierarchical Back handling; explicitly control focus/IME for every keypad mode.

### M-14 — Accessibility semantics are incomplete

Notes use color alone for selection and generic “Note options” labels (`TapeScreen.kt:216`). Sliders and switches are not semantically associated with their row labels (`SettingsScreen.kt:94` and `:199`). Result/error text has no labeled status semantics (`TapeScreen.kt:458` and `:592`). Glyph-only undo/backspace/operator keys have no action descriptions (`TapeScreen.kt:809`).

**Recommendation:** add selected/expanded/state semantics, merge label rows with controls, use content descriptions for glyph keys, expose result/error live-region semantics, and test navigation with TalkBack and large fonts.

### M-15 — Backup is enabled without an explicit sensitive-data policy

The manifest sets `android:allowBackup="true"` and defines no data-extraction/full-backup rules: `app/src/main/AndroidManifest.xml:7`. Notes, settings, and potentially pre-Q external diagnostics are eligible for backup under platform/OEM rules.

**Recommendation:** explicitly decide and document whether financial notes participate; add `dataExtractionRules.xml`/`fullBackupContent.xml`; exclude diagnostics and any sensitive note data where appropriate; test restore behavior.

### M-16 — API 26–28 storage paths contradict the README

The README documents `/sdcard/Download/ncal` for all supported versions (`README.md:16`). On API 26–28, exports use app-specific external storage while normal logs use internal `filesDir/logs` (`MediaStoreHelper.kt:41` and `:149`). The UI still says `Download/ncal` (`TapeViewModel.kt:182`), and `file://` results are not shared through a `FileProvider`.

**Recommendation:** document API-specific locations, add a valid share/create-document flow, and make the user-visible success message reflect the actual destination.

### M-17 — Commit count is not a safe application version

`app/build.gradle.kts:7` derives `versionCode` and `versionName` from `git rev-list --count HEAD`, catches every failure, and falls back to `1`. Rebases, shallow/source archives, divergent branches, and synthetic PR merge commits can produce collisions or non-monotonic versions for the same source state.

**Recommendation:** use a protected release tag/ledger for version code/name, record commit SHA separately, and fail configuration when required release inputs are absent.

### M-18 — CI supply-chain controls are incomplete

Actions use mutable major tags (`.github/workflows/build-apk.yml:13`, `:19`, `:31`, `:41`, `:49`); workflow permissions and checkout credential persistence are implicit; Android SDK setup suppresses license failure (`:27`); no Gradle wrapper, dependency lock state, or `verification-metadata.xml` is tracked.

**Recommendation:** pin actions to reviewed SHAs, set least-privilege permissions, use `persist-credentials: false`, make SDK setup fail closed, commit a checksummed wrapper, and verify/lock dependencies.

### M-19 — Verification does not cover Android integration or release behavior

The only test dependency is JUnit, and CI runs only debug assembly plus JVM unit tests (`app/build.gradle.kts:64`; `.github/workflows/build-apk.yml:35`). There is no `androidTest`, lint task, API-level matrix, release build, signature verification, or coverage gate.

**Recommendation:** add repository, exporter, ViewModel, MediaStore, lifecycle, Compose/accessibility, and fault-injection tests; run lint, connected tests on API 26/29/current, and a signed release build in CI.

## Low-severity findings

| ID | Finding | Evidence |
|---|---|---|
| L-01 | Whitespace-only lines are comments, not independent-section dividers, so percentage/precedence behavior can differ from a truly blank line. | `CalcFile.kt:91`; `TapeViewModel.kt:346` |
| L-02 | `lineResults` is not emitted in document order because block results are deferred until flush. | `TapeEvaluator.kt:64` |
| L-03 | Caret metadata is parsed but never restored; note loads always use offset zero. | `CalcFile.kt:76`; `TapeViewModel.kt:230` |
| L-04 | Negative zero can display with `-` despite the formatter contract saying zero stays `+`. | `TapeFormatter.kt:84` |
| L-05 | CR-only files are not split; CRLF balance patching can temporarily produce mixed endings. | `CalcFile.kt:62`; `TapeFormatter.kt:103` |
| L-06 | Entry/comment whitespace is trimmed despite model comments claiming verbatim preservation. | `CalcFile.kt:320`; `CalcFile.kt:209` |
| L-07 | User/provider-controlled note names are logged without control-character escaping. | `NotesRepository.kt:104`; `NcalLogger.kt:97` |
| L-08 | Grand total, memory text, and line marks are calculated but not exposed or styled in the current UI; memory/redo methods have no visible controls. | `TapeViewModel.kt:35`; `TapeScreen.kt:809` |
| L-09 | README test count, exact-precision claim, memory-key availability, and pre-Q storage instructions are stale or overstated. | `README.md:7`, `:40`, `:58`, `:61` |
| L-10 | The app uses the platform default launcher icon and has no tracked resource tree. | `AndroidManifest.xml:9` |

## Test and quality assessment

### Existing strengths

- The pure tape engine is isolated from Android and testable on the JVM.
- The sole test class contains **189** `@Test` methods, covering ordinary arithmetic, precedence, percentages, canonical output, BOM/CRLF, locale metadata, Unicode digits, editing, deletion, and stale-balance regressions.
- The latest CI run for the audited commit succeeded: [`build-debug-apk` run 35847283780](https://github.com/npnpatidar/ncal/actions/runs/35847283780).
- `git diff --check` was clean before writing this report.

### Important missing regression tests

1. Consecutive leading `*`, `/`, and `^` after a balance.
2. More-decimal operands across pretty-print, save, reload, and later chaining.
3. Extreme scientific exponents, numeric token length, `DECIMALS`, and rendered-width limits.
4. Writer behavior with bare/repeated separators and balance count mismatch.
5. Parenthesized formulas beginning with digits, bare dates, and malformed numeric suffixes.
6. Headerless decimal-comma values with three or more fractional digits.
7. Exact/defined behavior for negative and fractional powers at BigDecimal boundaries.
8. ViewModel metadata continuity, failed loads, wrong-note prevention, and transactional undo.
9. Atomic-save and MediaStore failure injection.
10. API 26 and API 29+ storage, backup, import-reader closure, and lifecycle tests.
11. TalkBack semantics, system Back, rotation, IME mode changes, and large-font layouts.

### Validation performed

- Reviewed every tracked Kotlin source file, the manifest, Gradle files, CI workflow, `.gitignore`, and README.
- Counted 4,991 Kotlin lines and 189 JVM test methods.
- Checked repository state and current revision.
- Ran `git diff --check` successfully.
- Queried the latest CI run for commit `e22a9a5`; assembly, unit tests, and artifact upload all succeeded.

### Validation limitations

The local environment has Java 21 but no Gradle, Kotlin compiler, Android SDK manager, or `adb`, and the repository has no Gradle wrapper. Therefore local `testDebugUnitTest`, lint, APK assembly, and connected/instrumentation tests could not be run. The successful remote CI run confirms the existing debug build and JVM tests, but not lint, device behavior, release signing, or API-level storage behavior. Dependency resolution was not executed, so this report does not claim a complete resolved-CVE inventory.

## Prioritized remediation plan

### Immediate: protect data and prevent process failure

1. Add strict numeric, exponent, precision, file-size, line-count, and metadata bounds.
2. Replace direct note writes with atomic replacement and surface `Result`-style save errors.
3. Make note switching transactional; never save old text under a failed target ID.
4. Disable/redact production file logging and establish retention limits.
5. Fix the leading-chain calculation and preserve exact operands across display/save paths.

### Near term: reliability and compatibility

6. Move import/export/save/startup work off Main and bound undo memory.
7. Make MediaStore writes pending/atomic, folder-scoped, and collision-safe.
8. Correct writer balance association and define strict parser ambiguity policy.
9. Validate metadata, settings, and power behavior at one boundary.
10. Add ViewModel/repository/export fault-injection tests.

### Before a public release

11. Upgrade Gradle to a patched release, add a verified wrapper, and update AGP/Kotlin/Compose.
12. Target/compile API 36 and test API 26, the MediaStore boundary, and current Android.
13. Add lint, instrumentation, accessibility, and signed release builds to CI.
14. Configure protected signing, R8, artifact checksums, provenance, and release governance.
15. Define backup, logging, storage-location, and data-retention policies.

## Recommended acceptance criteria

The highest-risk findings should be considered resolved only when:

- malformed or extreme input is rejected without excessive allocation;
- an interrupted save cannot destroy the last valid note;
- a failed note load cannot alter the active note ID/content pairing;
- release builds cannot persist note text by default;
- large imports and saves do not block the main thread;
- exports are unique, folder-scoped, and published atomically;
- display/settings changes do not change source arithmetic;
- two or more leading chained operators calculate against the running target; and
- the relevant regression, fault-injection, lint, and device tests pass in CI.
