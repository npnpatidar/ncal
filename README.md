# ncal

Notepad-tape calculator for Android (CalcTape-style), built with Kotlin and Jetpack Compose.
Type lines such as `+ 119.00 bob`, close a block with `=`, and edit the tape while it recalculates.

## Build and verification

The repository uses the Gradle 9.6.1 wrapper and an integrity-checked dependency graph.

```sh
./gradlew :app:testDebugUnitTest :app:lintDebug :app:assembleDebug
./gradlew :app:bundleRelease \
  -PappVersionCode=1 -PappVersionName=1.0.0 \
  -PreleaseStoreFile=/path/to/release.jks \
  -PreleaseStorePassword="$RELEASE_STORE_PASSWORD" \
  -PreleaseKeyAlias="$RELEASE_KEY_ALIAS" \
  -PreleaseKeyPassword="$RELEASE_KEY_PASSWORD"
```

Release builds require a version code from `1` through `2100000000`, a semver version name, and a complete signing configuration. The protected workflow also requires a protected `RELEASE_CERT_SHA256` fingerprint, verifies it before building, and publishes a GitHub artifact attestation.
CI also runs the JVM tests, Android lint, and debug assembly; release bundles, R8 mapping files, checksums, provenance, and attestations are published. Tag releases obtain the version code from the protected `RELEASE_VERSION_CODE` secret; manual releases provide it as an input.

For the complete local build, APK installation, release signing, and the verified ARM64/QEMU AAPT2 workaround on this machine, see [`BUILDING.md`](BUILDING.md).

## Storage and diagnostics

Notes are app-private canonical `.calc` files. Writes use same-directory temporary files and atomic replacement.
Exports are written to `Download/ncal` through MediaStore on Android 10+.
On Android 8–9, exports use the app-specific external Downloads directory and are shared through a `FileProvider`;
crash files follow the same export path. Debug file logging is disabled in release builds, capped per day,
and pruned by age in debug builds.
Android backup is disabled for the financial-note store and settings.

## `.calc` compatibility

- Entry: `" " + op + amount.padStart(17) + " " + comment`
- Separator: ` ------------------ `
- A `+X`/`-X` row directly after a separator is a display-only balance restatement
- A blank line starts an independent calculation
- Headers carry `DECIMALS`, separators, UUID, and caret metadata; BOMs and CRLF/CR input are accepted
- `* / ^` bind before `+ -`; `%` resolves against the running subtotal
- Decimal arithmetic uses a bounded 34-digit context; entry source precision is preserved across display and save
  while derived balances are rounded only for presentation

## Project layout

```text
app/src/main/java/com/npnpatidar/ncal/
  tape/                 parser, evaluator, formatter, editor
  storage/              notes, MediaStore/FileProvider paths
  export/               canonical import/export
  logging/              bounded redacted diagnostics
  ui/                   state holder, Compose editor, settings
  settings/             validated persisted settings
app/src/test/            JVM engine and regression tests
```
