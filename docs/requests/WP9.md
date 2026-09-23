# WP9 requests

- **WP0 (manifest):** `android.permission.INTERNET` and `ACCESS_NETWORK_STATE` for the companion (NanoHTTPD, NetworkCallback).
- **WP0 (Wiring / HammerklavierApp):** apply `docs/wiring/WP9.md` (LibraryServiceImpl swap, CompanionServer singleton, token key
  `companion.token`).
- **WP11:** `core/src/test/resources/wp11/catalog_fixture.json`; if it lists the fixture's MIDI assets, a top-level `"assets": [...]`
  array lets T9.1 check that every movement asset exists (the test reads it when present). Catalogue `tuning` is read as `null` or
  `{"aHz": 415, "temperament": "WERCKMEISTER_III"}` (enum name or label).
- **WP12:** `CompanionCommands.nowPlayingJson()` is served verbatim as `/api/state`.

## Integrator answers (M2, 2026-09-23)
- Merged; `Wiring.library = LibraryServiceImpl(app, compiler)`. CompanionServer, NetInfo and the token are wired at M6.
- `fullCatalogueHas70Works` now accepts 12–13 Start-here movements (Handel is skipped when absent, §4.8).
