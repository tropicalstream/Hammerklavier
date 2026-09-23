# WP9 progress (Library, import and companion) — branch wp9-library

## Done
- `core/.../library/`: `Sha1`, `ImportRules` (limits, display-name sanitising, relative names, titles, composer from folder,
  slugs, natural filename order, the default-instrument rule), `CatalogCodec` (catalog.json schema 1 → LibraryModel with a
  Start here shelf; missing assets dropped; `/api/library` encoder), `LibraryIndex` (index.json schema 2 codec; bundled +
  imported merge with the Imported shelf after Start here), `ImportStore` (byte-identical `<sha1>.mid` copies, SHA-1 dedupe,
  rejection / deletion records by (source, size, mtime), zips, subfolders → one work, index written temp + atomic rename,
  rebuilt from the copies when lost, one lock).
- `app/.../library/android/LibraryServiceImpl` (AssetManager + `filesDir/imports` + `getExternalFilesDir("Scores")`,
  `Os.stat` ownership, test-twin shelf, asset:/test:/synth: ids).
- `app/.../companion/CompanionServer` (routes of §1.6, token, 403/429 flood guard, 413 before the body, raw upload to
  `upload-<n>.tmp`, JSON 500), `NetInfo` (site-local IPv4, Wi-Fi callback), `assets/companion.html`.
- Wiring note `docs/wiring/WP9.md`; requests `docs/requests/WP9.md`.

## Tests
- T9.1 `CatalogCodecTest` 8: 7 green incl. the wp11 `catalog_fixture.json`; `fullCatalogueHas70Works` runs via `Assume` and skips until WP11's `assets/catalog.json` (M6), no @Ignore left. T9.2 `ImportStoreTest` 18 green,
  `ImportRulesTest` 3, `LibraryIndexTest` 2. T9.3 in `ImportRulesTest.defaultInstrumentRule` + `ImportStoreTest.harpsichordDefaultFromFolderName`.
- T9.4 `CompanionServerTest` 14 green (app JVM unit tests on 127.0.0.1).
- After merging main (contracts-v1.1): `:core:test` 96 tests, 0 failures, 1 skipped (the full catalogue); `:app:testDebugUnitTest` 14/14.
- Device checks (phone upload, curl, push_scores.sh + rescan, no-Wi-Fi, /api/state < 50 ms) remain for M6.

## Decisions / deviations
- The tests use WP9's private `MiniCompiler` (a small SMF reader) instead of WP1's parser, so T9.2 is independent of contracts-v1.1.
- PERMISSION_DENIED and IO_ERROR are not recorded as rejections: chmod does not change mtime, so a recorded one would never retry.
- index.json items carry two extra fields: `notes` (for the upload reply) and `recovered` (re-indexed after index.json was lost;
  a rescan re-adopts the pushed source with its folder).
- Zip: a `..`/absolute entry rejects the whole zip (ZIP_TRAVERSAL); > 200 entries or > 20 MiB → ZIP_LIMIT; uncompressed total
  bounded at 64 MiB; entries without a folder form one work named after the zip. Uploaded zips are renamed to
  `<tmp>/upload-<n>/<name>.zip` so the work keeps the zip's name.
- `CompanionServer` has two extra defaulted constructor parameters (`tmpDir` = java.io.tmpdir, which ART sets to cacheDir;
  `nowMs`); the frozen call still compiles. Ten wrong tokens in 60 s: the tenth answers 429, then every /api call from that
  address is 429 for 60 s; a missing token is 403 and not counted.
- Fold count for the harpsichord rule comes from `compile(..., HARPSICHORD).perf.info.folded`, only when the range leaves 29–89.
- `ImportedFacts` (core interface) gives the server the note count without changing the contract.

## Remaining
- None in code. The full-catalogue assertion turns on automatically when `assets/catalog.json` lands (M6); device checks run by the integrator.

## Review fixes (round 2)
- Test shelf: `assets/midi/test/*.mid` shelf ("Test scores") is added only when `BuildConfig.DEBUG` or when no catalogue parsed (M2 bundled-only). Test ids never enter `startHere`. `test:` ids always resolve in `readBytes`. Release library keeps the §1.5 15 shelves.
- Import rejections from a failed `inspect()` now call `compile()` and store `"<detail> @<byteOffset>"` in RejectRecord.detail (§4.8).
- DUPLICATE rejections are no longer recorded (like PERMISSION_DENIED / IO_ERROR), so a pushed copy re-imports after the original is deleted.
- Upload reply takes title/duration from ImportStore via `ImportedFacts.importedTitle/importedDurationSec` (default-null interface methods) instead of `library.load()`.
- `HEAD /` returns headers only (Content-Length of the page). `rejected[]` carries `reasonText` (local table in CompanionServer, since UiText is WP10's; page prefers it over its own table).
- Wiring: `model = { null }` so `/api/library` reflects imports immediately.
