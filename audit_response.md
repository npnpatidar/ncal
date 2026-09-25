# ncal Audit Response

**Date:** 2026-09-24  
**Base revision:** `e22a9a579b69d180ba3939029f00852f4bf7a429`  
**Scope:** implementation response to `audit.md`

## Summary

The agreed second-round fixes are now implemented: separator-safe rendering, save/load generation guards, inactive-note save preservation, broader aggregate limits, lifecycle/settings/import hardening, fail-closed MediaStore provider handling, explicit release version governance, and adaptive accessibility state. The JVM regression suite now contains 243 passing tests (227 engine + 5 tombstone + 11 journal), and local debug/release builds succeed. Remaining gaps are primarily device/API-level verification and remote protected-environment governance.

## Ninth-round implementation (functionality first)

Release/deployment items remain deferred per the audit appendix.

1. **Durability guarantee (explicit).** Every debounced save stages its snapshot to the write-ahead journal before its 800 ms timer starts; every `onPause()` flush stages fsynced. A kill at any point loses at most the keystrokes since the last snapshot capture (one edit burst), and every staged-but-unapplied entry is replayed through `repo.save` on the next startup in filename order. Drains apply only current-generation, non-tombstoned entries; malformed entries are discarded. Covered by `NoteJournalTest.restartRecoveryAppliesStagedEntries` plus the existing ordering/discard/cap tests.
2. **Retention accounting.** `trimOwnLogs()` now enforces per-file size, age, a 50-file count cap, and an 8 MiB aggregate cap on both API paths.
3. **Metadata precedence (explicit).** Export uses caller meta when provided, parsed header otherwise, and preserves source caret; duplicates/imports intentionally reset caret for new documents; multi-file import keeps explicit partial-success semantics instead of silent rollback. Covered by `exportMetaPrecedenceIsCallerThenHeader`, `formatEntryRejectsControlSeparators`, `controlUuidIsSanitizedOnWrite`, and `importPreservesDocumentUuid`.

Process-death replay on real devices, TalkBack/rotation/API-26/font-scale behavior, and the deferred release appendix still require device/remote verification (M-19).

## Eighth-round implementation (functionality first)

Release/deployment items remain deferred per the audit appendix.

1. **Replay semantics — planned and tested.** `NoteJournal.planReplay()` centralizes drain decisions (malformed/deleted/stale → discard, else apply in filename order); `drainJournal()` executes the plan. Covered by `planReplayAppliesInOrder`, `planReplayDiscardsDeletedAndStaleEntries`, and `planReplayDiscardsMalformedEntries`.
2. **Precedence made explicit.** `CalcExport.resolveExportMeta()` documents caller-wins/parsed-header-otherwise with a unit test; `formatEntry` control separators fall back to defaults (`formatEntryRejectsControlSeparators`).
3. **Restart-safe log cap.** Rollover seeds the daily byte counter from the existing day-file size via `MediaStoreHelper.ownFileSize()`, so restarts cannot grant a fresh 512 KB.

Process-death replay on real devices, TalkBack/rotation/API-26/font-scale behavior, and the deferred release appendix still require device/remote verification (M-19).

## Seventh-round implementation (functionality first)

Release/deployment items remain deferred per the audit appendix.

1. **Lifecycle durability — write-ahead journal.** New `NoteJournal` stages every debounced save (unsynced) and every `onPause()` flush (fsynced) as an atomic file, so a kill during the debounce, during the flush, or during filesystem work loses nothing: startup replays staged entries through `repo.save` before loading notes. A background drain applies live entries with generation checks and deletes them on success; deleted-note entries are discarded via the tombstone check. Covered by `NoteJournalTest` (round trip, ordering, malformed entries, generation discard, caps).
2. **Bounded flush, stranded edits.** `flushNow()` stages synchronously (one bounded file write, no dispatcher waits) and drains asynchronously; oversized notes behave identically since staging cost is O(bytes) with no parse/evaluate on Main. Stranded edits set a flag drained by the next successful commit. Timeout/executor machinery removed as obsolete.
3. **Deletion hardening.** Tombstone marking precedes file removal so every crash state resolves to the intended outcome; startup `reconcileTombstones()` finishes interrupted deletions; file-lock failures are save/delete failures (fail closed); tombstones never evict and are fsynced with directory sync; `atomicWrite` syncs the parent directory.
4. **Precedence documented.** Export uses caller meta when provided, parsed header otherwise, and preserves source caret; duplicates/imports intentionally reset caret for new documents; multi-file import keeps explicit partial-success semantics instead of silent rollback. Covered by `importPreservesSourceCaret` and `malformedHeaderRoundTripIsStable`.

Process-death replay on real devices, TalkBack/rotation/API-26/font-scale behavior, and the deferred release appendix still require device/remote verification (M-19).

## Sixth-round implementation (functionality first)

Release/deployment items remain deferred per the audit appendix.

1. **Lifecycle durability — size-budgeted flush.** `flushNow()` persists small notes synchronously on the lifecycle thread via `repo.saveIfCurrent()` (dispatcher-independent locks only) and routes oversized notes through a single-thread flush executor with a 750 ms bounded wait; timeouts and failures surface messages instead of losing data silently. The budget predicate is pinned by `syncFlushBudgetBoundary`.
2. **Deletion preservation — mark-first ordering with reconcile.** Tombstones are written before the note file is removed, so any crash state resolves to the intended outcome; startup runs `reconcileTombstones()` to finish interrupted deletions. File locking is fail-closed (lock failures become save/delete failures), tombstone markers are fsynced with directory sync and never evicted, `atomicWrite` syncs the parent directory, and edits stranded by suppression are drained on the next commit.
3. **Writer metadata — closed remaining paths.** `formatNum`/`formatEntryNum` coerce control/duplicate separators to defaults; out-of-range header caret fails closed to safe defaults; `.calc` export preserves source caret. Covered by `controlSeparatorsFallBackToDefaults`, `headerCaretValuesAreBounded`, and `malformedHeaderRoundTripIsStable`.
4. **Retention/selection hardening.** Log trimming enforces a 50-file cap alongside age/size rules on both API paths; logger gates are now private; adaptive icons gained monochrome layers.

Process-death replay, TalkBack/rotation/API-26/font-scale behavior, and the deferred release appendix still require device/remote verification (M-19).

## Fifth-round implementation (functionality first)

Release/deployment items remain deferred per the audit appendix.

1. **Lifecycle durability — synchronous write on the lifecycle thread.** `flushNow()` no longer uses `runBlocking`/timeout: it snapshots on Main, invalidates the debounce generation, cancels without joining, and calls `repo.saveIfCurrent()` directly on the calling thread. The repository method is fully blocking and uses only dispatcher-independent locks (JVM monitor plus file lock), so `onPause()` waits only for one atomic write and can never deadlock on Main. Superseded generations skip inside the file lock; failures surface “Background save failed”.
2. **Cross-instance preservation — durable markers.** `FileTombstones` writes fsynced marker files with directory sync (no eviction cap); `save()`/`list()` consult them, and save/delete critical sections serialize on a file lock that now fails closed (lock failures become save/delete failures, never silent proceeds or crashes). Covered by `FileTombstonesTest` (round trip, cross-instance visibility, path safety, non-eviction).
3. **Writer metadata — closed remaining paths.** `formatNum`/`formatEntryNum` coerce control/duplicate separators to defaults; header caret values are upper-bounded like fallbacks; `.calc` export preserves source caret instead of forcing end-of-document. Covered by `controlSeparatorsFallBackToDefaults` and `headerCaretValuesAreBounded`.
4. **Stranded-edit save** — edits made while a note id is suppressed set a flag drained by the next successful commit, so no edit is silently dropped.

Process-death replay testing, TalkBack/rotation/API-26/font-scale behavior, and the deferred release appendix still require device/remote verification (M-19).

## Fourth-round implementation (functionality first)

Release/deployment items remain deferred per the audit appendix.

1. **Lifecycle durability — bounded wait, no Main block.** `flushNow()` snapshots on Main, invalidates the debounce generation, cancels without joining, and persists under `saveMutex` inside `runBlocking(Dispatchers.IO)` with a 2.5 s `withTimeoutOrNull` bound. The wait chain touches only IO-dispatched work, so Main can never block indefinitely. A timed-out flush surfaces “Background save failed”.
2. **Cross-instance deletion — durable markers.** `FileTombstones` records deletions as marker files (`.tombstone-<id>`, pruned to 200) so a second ViewModel or process cannot resurrect a note: `save()` refuses tombstoned ids and `list()` hides them. Save/delete critical sections are additionally serialized with a file lock. Covered by `FileTombstonesTest` (round trip, cross-instance visibility, path safety, prune bound).
3. **Writer metadata — fallback sanitization.** `parse()` sanitizes fallback separators (control chars replaced, equal separators split to `,`/`.`), closing the direct-`CalcMeta` emission path; header authority is unchanged. Covered by `unsafeFallbackSeparatorsAreSanitized`.
4. **Caret precedence** is header-wins-when-valid, caller-fallback otherwise, exercised by the header-discovered round-trip test; export/duplicate caret resets are intentional new-document behavior.

Process-death testing, TalkBack/rotation/API-26/font-scale behavior, and the deferred release appendix still require device/remote verification (M-19).

## Third-round implementation (functionality first)

Release/deployment items (H-09, H-10, M-17, M-18, M-19) are deferred per the audit appendix and were not touched beyond the existing source controls.

1. **Lifecycle deadlock — fixed without blocking Main.** `flushNow()` no longer uses `runBlocking`: it snapshots on Main, invalidates the debounce generation, cancels the pending job without joining, and persists on `Dispatchers.IO + NonCancellable`. Debounced saves now run on `Dispatchers.IO`, so `cancelAndJoin()` in lifecycle operations can never wait on a Main-dispatched coroutine. Superseded saves are skipped inside the save mutex via a `SaveResult.Superseded` outcome.
2. **Startup/active-delete guards — all commit paths guarded.** `init` and active-delete replacement now pass `LoadGuard` into `commitLoaded()`; a failed startup guard publishes only the notes list instead of overwriting early edits.
3. **Active-delete edits — stabilized or preserved.** Deletion retries save-until-stable (bounded), aborts when the note keeps changing, and falls back to preserving concurrent edits into the replacement note with a guarded recommit.
4. **Metadata/grouping — header authority fixed; negative Indian grouping fixed.** `pretty()` renders with parsed-document metadata; `evaluate()` passes document separators to `patchBalances()`; `indian()` preserves a leading minus. New round-trip tests cover header-discovered German separators and negative Indian grouping.
5. **Budgets/writers — universal.** `formatEntry`/`formatBalance` sanitize metadata; `groupNumber` rejects control separators; `estimatedAmountChars` accounts multibyte separator width; `exportTxtResult` applies the same warning gate as `.calc` (including “too long”); the unsafe list-based `CalcFile.write` overload was removed and all callers/tests migrated to the index-keyed map overload.
6. **MediaStore cleanup — reachable.** `discardInterruptedPendingRows()` collects rows inside the cursor block and deletes them after the cursor closes.
7. **Settings/undo/import — one transaction each.** Settings changes are skipped when no-op, recorded once in undo history, and undo/redo run under the lifecycle mutex with ordered persistence. Multi-file import reports explicit partial success (`Imported N; M failed`) and no longer publishes stale lists. `flushNow`/`scheduleSave`/`persist` share one generation protocol.
8. **Accessibility/docs — source risks closed.** Saveable slider drafts, actionable-row selection indicator, adaptive keypad viewport cap, scrollable option rows, per-day log cap, cleaned crash fields, and corrected README/MainActivity wording. TalkBack, rotation, API 26, and font-scale behavior still need device evidence.

Remaining parser gaps closed along the way: date-suffixed lines, Unicode formulas, malformed exponents, malformed header lines, and operator-like separators are now comments/invalid with regression tests; `memoryRecall` enforces the input limit; import decoding rejects control characters.

## Response to the second-round audit for auditor review

### Overall position

I agree with the audit’s central conclusion: the current application should not be treated as merge-ready or release-ready. The audit correctly identifies remaining corruption, data-loss, durability, and release-governance risks.

I also agree that the second-round section should be treated as the current assessment and the first-round section as historical context. The exact totals in the status table are a point-in-time count and should be recalculated after the current uncommitted changes are reviewed.

The agreement analysis below preceded implementation. The current source now contains the agreed fixes; the remaining open items are verification and deployment matters noted after the implementation summary.

### Priority issues

1. **Non-default separators — implemented and regression-tested.** `TapeFormatter.pretty()` now localizes both decimal and thousands separators, `patchBalances()` receives active metadata, and UI/TXT totals use the same separators. A German `1.234,50` pretty → parse → evaluate round-trip test passes.

2. **Inactive-note delete/duplicate save loss — implemented.** Pending autosave cancellation is now limited to active-note operations; inactive delete/duplicate operations leave the active note’s save job intact. Active deletion retains suppression, persistence, replacement, and repository tombstone protection.

3. **Second-phase note-load race — implemented.** `LoadGuard` rechecks switch/edit generations and note state before and after the formatting suspension. `createNote()` now returns after a post-create mismatch instead of committing the new note over newer edits.

4. **Aggregate output budgets — implemented across public writers.** Unsupported direct values are rejected before `setScale()`, raw text is measured in UTF-8 bytes, TXT export checks input/output budgets, final writer output is rechecked, and derived totals report support errors. The common parsed-input and direct-writer regression cases pass.

5. **Main-thread and lifecycle durability — implemented.** Undo/redo use cached byte sizes and incremental counters, settings writes are serialized, `flushNow()` is an awaited lifecycle barrier, and note publication uses a generation token. Full document work remains off Main.

6. **Provider recovery and release governance — source controls implemented; deployment remains open.** MediaStore provider errors now fail closed, pending rows are ownership/path/name/pending verified before cleanup, collision suffixes are covered, and binary reads are rejected. Release checksums are artifact-relative, version codes are explicit/capped at `2100000000`, and protected environment/certificate/attestation controls remain declared. Remote environment configuration and a protected CI run still require deployment verification.

7. **Settings and import transactions — implemented.** Settings callbacks merge changed fields under the lifecycle mutex, settings persistence is serialized, undo/redo persists restored settings, and note-list publication is generation-checked so stale imports cannot overwrite newer delete/rename state.

8. **Accessibility and documentation — source risks addressed; device validation remains.** Slider drafts are saveable, selected note semantics are on the actionable row, keypad sizing derives from adaptive columns and font height with bounded overflow scrolling, and Settings has a snackbar host. Actual TalkBack and large-font behavior still requires device verification.

### Points where I would qualify the audit wording

- M-10’s original active-note resurrection and the remediation-introduced inactive-note save loss are now both addressed in source; the auditor should re-run the race reproduction before changing the table status.
- I agree that the exact fixed/partial/regressed totals are useful, but they are not stable until the auditor reruns against the final working tree.
- I do not treat “zero configured environments” as a source-code fact. The workflow declares `environment: release`; whether that environment is protected, reviewed, and populated with secrets is a separate GitHub configuration fact.
- I agree that the absence of instrumentation/API-level/minified-runtime tests keeps M-19 partial, but those are verification gaps rather than proof of a new runtime defect.

## Finding response

### H-01 — Unbounded numeric and metadata scale

Implemented centralized input/numeric limits, direct-value validation before scaling, UTF-8 aggregate sizing, final writer checks, TXT export limits, and derived-total support checks in `TapeModel.kt`, `TapeFormatter.kt`, `CalcFile.kt`, `CalcExport.kt`, and `TapeEvaluator.kt`. Regression tests cover aggregate, direct-writer, German-separator, and unsupported-derived-total cases.

### H-02 — Non-atomic note writes and hidden failures

`NotesRepository.kt:161-179` now returns `SaveResult`, writes through a same-directory temporary file, flushes file contents, and atomically replaces the prior note. Create, duplicate, import, and save all use the same atomic path. Save failures are surfaced by the ViewModel, which reports autosave/background-save errors.

### H-03 — Failed loads could associate old text with a new note

Note loading is serialized by lifecycle/edit generations. `LoadGuard` is checked before and after the formatting suspension, and create/delete/duplicate paths invalidate or preserve the correct pending save. Repository mutation locking and a deleted-ID tombstone prevent stale saves from recreating deleted notes.

### H-04 — Sensitive release diagnostics

`NcalLogger.kt:25-112` enables file persistence only for debug builds, redacts release diagnostics, removes tape-content logging from the ViewModel, bounds messages, and uses a bounded executor. Crash names are unique and crash output is size-bounded. Debug files are retained for bounded periods in `MediaStoreHelper.kt:157-196`.

### H-05 — Blocking import/export/save and UI work

Startup, note enumeration, loads, saves, imports, exports, equality calculations, and full evaluation are dispatched off the main thread. Lifecycle mutexes serialize note/settings operations; history uses cached byte counters; and `flushNow()` is an awaited onPause persistence barrier. Device/process-kill testing remains unavailable.

### H-06 — Destructive, weakly scoped exports

`MediaStoreHelper` fails closed on provider errors, verifies owner/path/name/pending metadata before cleanup or publication, covers collision suffixes, rejects malformed binary reads, and preserves durable atomic writes. `TapeViewModel.shareNote` emits a granted `ACTION_SEND` intent. Device-level provider behavior remains to be tested.

### H-07 — Display/persistence precision changes

Entry operands retain source scale and all formatter paths now apply active decimal/grouping separators. German pretty/parse/evaluate and canonical writer round-trip coverage verifies that display localization no longer changes values.

### H-08 — Incorrect consecutive leading multiplicative chains

`TapeEvaluator.kt:95-249` now tracks whether the current chain includes the running base and subtracts that base exactly once. Consecutive leading `*`, `/`, and `^` chains use the actual running target. Regression tests cover all three operators and chained results.

### H-09 — Vulnerable/unpinned Gradle supply chain

The tracked Gradle 9.6.1 wrapper and SHA-256 distribution checksum are in `gradle/wrapper/gradle-wrapper.properties:1-10`. Dependency verification metadata is committed at `gradle/verification-metadata.xml`. CI uses the wrapper, least-privilege permissions, pinned action SHAs, `persist-credentials: false`, fail-closed SDK license setup, and content-filtered repositories in `settings.gradle.kts:1-31`.

### H-10 — Play/release readiness

`app/build.gradle.kts` requires a positive version code, semver version name, existing keystore, and complete signing properties for every release task. The release workflow declares a protected `release` environment, validates tags/dispatch versions, verifies the release certificate SHA-256 fingerprint against a protected secret, builds a signed bundle, emits source-bound provenance plus SHA-256 checksum, and creates a GitHub artifact attestation. The source cannot prove that the remote environment, reviewers, environment secrets, or a protected release run are configured; mapping upload is mandatory and signing secrets remain external.

### M-01 — Balance/subtotal index mismatch

`CalcFile.kt:201-251` accepts `Map<Int, BigDecimal>` and indexes balances by document line. Repository and export callers now pass `EvalResult.balanceTotals`; the legacy list overload remains for compatibility. A separator/balance mismatch regression test was added.

### M-02 — Permissive/ambiguous parser input

`CalcFile` now rejects date-times, unmatched formulas, malformed grouping, ambiguous zero-comma values, and incomplete one-separator headers while preserving them as comments. Active metadata fallback and stale import generation checks remain in place.

### M-03 — Malformed header metadata

`CalcFile.kt` validates complete headers, rejects headers supplying exactly one separator, and preserves missing-separator compatibility for otherwise header-only metadata. Invalid headers cannot cause global decimal adoption.

### M-04 — Incorrect power fallback

`TapeEvaluator` uses exact BigDecimal integer powers and a reduced rational exponent. Fractional roots use scale-relative Newton convergence plus powered-result verification, so tiny values, zero bases, and unreduced fractions are handled without binary floating point. Unsupported degrees remain explicit errors. Tests cover `2e-10000^0.5`, `0^0.5`, `4^0.25`, `(-32)^0.2`, and reciprocal powers.

### M-05 — Invalid calculations exported as successful

`CalcExport` exposes typed results, blocks evaluator errors and blocking parser warnings, bounds TXT output, and reports the specific failure rather than claiming success from a URI alone.

### M-06 — Lost metadata/identity on import/export

Current-note export/import pass active metadata through parsing and rendering, preserve the current UUID, use active separators, and commit only when the captured generation is unchanged. German separator round-trip coverage is included.

### M-07 — Crash handler stacking and unsafe formatters

`NcalLogger.kt:35-76` uses immutable `DateTimeFormatter` instances, installs one crash handler per process, bounds stack output, and uses unique crash names.

### M-08 — Unbounded logger queue/retention

The logger uses a bounded `ThreadPoolExecutor` queue at `NcalLogger.kt:28-34`; `MediaStoreHelper.trimOwnLogs` removes old/oversized diagnostic files. Release file logging remains disabled. The finding remains partial because current-day files, aggregate retention, and pre-Q crash-file counts are not fully bounded between prune passes.

### M-09 — Unclosed readers and unrestricted picker MIME

`TapeScreen.kt` uses bounded document MIME selection, and ViewModel/repository readers close streams, cap bytes, and decode UTF-8 with malformed-input rejection. A supported filename containing binary data is rejected.

### M-10 — Failed deletion/resurrection

Active-note deletion is serialized with saves, suppresses/cancels pending autosaves, persists the latest snapshot, and replaces the active note transactionally. Inactive delete/duplicate operations no longer cancel the active note’s save. Repository locking/tombstones prevent resurrection.

### M-11 — Settings corruption and validation

`AppSettings.kt:16-106` adds one `sanitized()` boundary, finite/range checks, and type-safe preference reads. Settings sliders in `SettingsScreen.kt:205-222` persist on release rather than every drag frame.

### M-12 — Incomplete undo/redo

`TapeViewModel` snapshots text, selection, metadata, and settings; history uses cached UTF-8 byte counters; undo/redo persists restored settings; and rapid settings callbacks merge changed fields under lifecycle/version checks.

### M-13 — Rotation, Back, and IME state

`TapeScreen.kt` uses saveable dialog/settings state and explicit IME control. Settings slider drafts use `rememberSaveable`, and the Settings screen has a snackbar host. Actual rotation/device behavior remains part of M-19 verification.

### M-14 — Accessibility semantics and touch targets

Note selection semantics are attached to the actionable row, keypad sizing derives from adaptive columns/font height with bounded overflow scrolling, and accessibility labels/live regions remain present. Device-level TalkBack and large-font verification remains open under M-19.

### M-15 — Backup policy

`AndroidManifest.xml:7-14` disables backup and references exclusion rules in `res/xml/data_extraction_rules.xml` and `res/xml/backup_rules.xml`, keeping notes/settings/diagnostics out of backup.

### M-16 — API 26–28 storage contradiction

Pre-29 exports now use the app-specific external Downloads directory with a shareable `FileProvider`; diagnostics remain app-private. `README.md:18-25` documents the version-specific behavior.

### M-17 — Commit-count versioning

`app/build.gradle.kts` validates semver names and version codes from `1..2100000000`; the release workflow requires an explicit manual code or protected `RELEASE_VERSION_CODE` secret and no longer derives codes from rerun numbers. Remote ledger governance remains external.

### M-18 — CI supply-chain controls

Both workflows pin actions, use least-privilege permissions, and keep secrets/context out of shell source. The release workflow validates inputs, uses protected environment/certificate/attestation controls, emits portable checksums/provenance, and requires mapping output. Remote deployment controls and a post-change run remain open.

### M-19 — Verification coverage

CI runs JVM tests, lint, and debug assembly through the wrapper, and the release workflow runs release lint/bundle plus mapping/checksum/attestation artifacts. The finding remains partial because device instrumentation, API-level coverage, minified-runtime testing, and a protected-release run are still absent.

### L-01 — Whitespace-only section dividers

`CalcFile.kt:116-119` uses `isBlank()`, so whitespace-only lines are independent sections. The existing regression expectation was updated accordingly.

### L-02 — Out-of-order line results

`TapeEvaluator.kt:27-80` sorts `lineResults` by document index before returning.

### L-03 — Caret metadata restoration

`TapeViewModel` maps persisted line/column metadata for normal loads and imports, handles CR/CRLF offsets, and rechecks load guards before commit. Full device rotation/caret verification remains open.

### L-04 — Negative zero

`TapeFormatter.kt:90-101` normalizes signed zero to a positive operator and zero magnitude; a regression test was added.

### L-05 — CR-only/CRLF handling

`CalcFile.kt:81-83` uses newline-aware line splitting, and `TapeFormatter.kt:109-145` preserves the detected line ending while patching balances. CR-only and CRLF tests were added.

### L-06 — Comment whitespace

Comment text is no longer indiscriminately trimmed during formatting/writing, and structural separator padding is removed before layout. Trailing comment content is preserved, but leading comment whitespace is still not fully round-trip safe; the finding remains partial.

### L-07 — Control characters in diagnostics

`NcalLogger.clean()` replaces every ISO control character, bounds messages, and redacts release identifiers/URIs/paths.

### L-08 — Dead totals/memory/redo/line marks

The UI now exposes section/grand totals, memory controls, redo state, and line-mark styling through `TapeMarkTransformation` in `TapeScreen.kt:946-970`.

### L-09 — Documentation drift

`README.md` documents the wrapper, API levels, storage variants, release governance, logging policy, and precision behavior; protection claims now identify remote/device verification dependencies.

### L-10 — Default launcher icon

`AndroidManifest.xml:10-11` references tracked adaptive launcher resources under `app/src/main/res/mipmap-anydpi-v26` and `drawable`.

## Verification

Passed:

- Expanded JVM suite: **243 tests, 0 failures** using JUnit 4.13.2 and the Kotlin compiler.
- `./gradlew :app:compileDebugKotlin` with the tracked wrapper and local API 37 SDK.
- `./gradlew help` with dependency verification enabled.
- `./gradlew :app:testDebugUnitTest :app:lintDebug :app:assembleDebug` on this ARM64 machine using the documented QEMU AAPT2 override.
- Temporary local test-key `:app:bundleRelease :app:lintRelease` completed with R8, signing, and mapping output.
- `git diff --check`.
- Final debug APK built at `app/build/outputs/apk/debug/app-debug.apk` with SHA-256 `14925d7d927c008a9e0fb1995f0b8808e9cbed25c9d9f072f253dce2b4c3664d` (not copied to Download — copies only on request).

The direct ARM64 Gradle resource tasks initially failed because the SDK supplies x86-64 AAPT2 binaries. The exact temporary-wrapper procedure is documented in [`BUILDING.md`](BUILDING.md); it successfully produced `app/build/outputs/apk/debug/app-debug.apk`.

No commit was created. No connected-device instrumentation, API-level matrix, minified-runtime test, or post-change protected GitHub release run was available for this response.
