# WP9 wiring (wp9-library)

## Swap
- `Wiring.library`: `StubLibrary(readAsset, scoresDir)` → `library.android.LibraryServiceImpl(ctx = app, compiler = compiler)`
  (bundled-only until WP11 ships `assets/catalog.json`: with no catalogue it still lists `assets/midi/test/<name>.mid` as a
  "Test scores" shelf and resolves `asset:`, `test:` and `synth:` ids exactly like StubLibrary).

## Hook-ups
- `HammerklavierApp` owns one `companion.CompanionServer(port = CompanionServer.PORT, token = { settings.getString("companion.token", "") },
  library = wiring.library, model = { null }, commands = <SessionController>,
  page = { assets.open("companion.html").use { String(it.readBytes(), Charsets.UTF_8) } }, post = wiring.post)`;
  `start(5000, true)` with the engine, `stop()` when the activity finishes.
- Token: if `companion.token` is empty or `!CompanionServer.isToken(it)`, store `CompanionServer.newToken()` (String, default "").
  The Import panel's `Rotate token` row writes a fresh `newToken()`.
- `NetInfo.watchWifi(app) { post { hud refresh; server.url() } }` — keep the returned function and call it on shutdown.
  `server.url()` is null → show `no Wi-Fi: use push_scores.sh`.
- `LibraryService.rescan()` on resume, when the Library opens and on the CONTROL `rescan` extra (HKLoader). `ImportScan.scoresDirForeign`
  → `StatusCode.SCORES_DIR_FOREIGN`; a rejection with `PERMISSION_DENIED` → `IMPORT_PERMISSION`; other rejections → `IMPORT_FAILED`.
- `LibraryServiceImpl.setLastInstrument(movementId, id)` (optional) records the instrument last chosen for an imported work.
- No manifest change: main's manifest already declares `INTERNET` (NanoHTTPD) and `ACCESS_NETWORK_STATE` (the Wi-Fi callback).

## Smoke
- `curl -H 'x-hk-token: <token>' -H 'Expect:' -H "x-hk-name: H%C3%A4ndel%20Suite.mid" --data-binary @file.mid http://<ip>:19112/api/upload`
  (the empty `Expect:` avoids curl's 1 s 100-continue wait on files > 1 MiB).
- `tools/gw :core:test --tests 'com.tropicalstream.hammerklavier.library.*'` and
  `tools/gw :app:testDebugUnitTest --tests 'com.tropicalstream.hammerklavier.companion.*'`.
- Device (integrator, M2/M6): launch, open Library → the Test scores shelf (or Start here once `catalog.json` lands) lists and
  `asset:midi/test/<name>.mid` plays; More → Import shows `http://<ip>:19112` and the token; open it on a phone on the same
  Wi-Fi, upload a .mid → it appears on the Imported shelf; upload a text file renamed .mid → rejected with its reason;
  wrong token 10x → 429; `tools/device/push_scores.sh <dir>` then rescan → the folder appears as one work;
  Wi-Fi off → `no Wi-Fi: use push_scores.sh`; `curl -w '%{time_total}' .../api/state` < 50 ms.

- `model = { null }` is deliberate: the server then calls `library.load()`, so `/api/library` reflects an upload immediately rather than waiting for `importsChanged` to reload on HKLoader.
