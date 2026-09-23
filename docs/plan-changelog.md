# Plan changelog

Changes to `docs/PLAN.md`'s decisions outside `contract/**`, approved by WP0 (PLAN, change
control). Contract changes go in `docs/contracts-changelog.md`. Newest last.

---

## 2026-09-22 · WP0 · day 0 (contracts-v1 candidate)

1. **Gradle build slots: `tools/gw`, 2 slots.** Every agent runs Gradle only through `tools/gw
   <tasks>` from its worktree root (a python `fcntl` wrapper over `/tmp/hk-gradle-slot-N.lock`,
   `HK_GRADLE_SLOTS`, default 2). This replaces §7.1 rule 4's "3 build slots (the same lock with
   three slot files)"; `tools/ci.sh` calls Gradle through `tools/gw`. Reason: the lead measured
   that two concurrent AGP builds are what this 16 GB Mac sustains with six agents.
2. **`gradle.properties`** is the lead's, not §2.2's: `org.gradle.jvmargs=-Xmx2560m
   -XX:MaxMetaspaceSize=768m`, `kotlin.compiler.execution.strategy=in-process` (no separate
   Kotlin daemon per worktree), `org.gradle.daemon.idletimeout=900000`. §2.2's `-Xmx1536m`,
   `kotlin.daemon.jvmargs=-Xmx1g` and `org.gradle.workers.max=2` do not apply.
3. **`:core` test JVMs** run with `-XX:-DoEscapeAnalysis -XX:-EliminateAllocations` (as planned)
   and a 1 GiB heap.
4. **Manifest:** `FOREGROUND_SERVICE_MEDIA_PLAYBACK` is declared next to `FOREGROUND_SERVICE`
   (required with targetSdk 35 on API 34+; inert on the glasses' SDK 32). WP4's `PlaybackService`
   is declared `enabled="false"` with `tools:ignore="MissingClass"` until WP4 ships the class.
   The activity also sets `hardwareAccelerated`; two separate intent filters (MAIN + LAUNCHER,
   MAIN + `com.rayneo.intent.category.AR_APP`).
5. **`.gitattributes`:** LF normalisation; binaries marked; **Git LFS for
   `app/src/main/assets/instruments/**/*.opus` and `**/*.bin`** (kit units and env.bin;
   `map.json` stays text; the `wp11` test fixtures stay ordinary objects). WP11 needs no further
   lines unless it adds other large asset types.
6. **`res/values/colors.xml`** rewritten with the Hammerklavier overlay colours (HUD_TEXT,
   HUD_ACCENT, GILT_LIT from `Pal`); the MathCosmos comments are gone.
7. **`app/src/main/baseline-prof.txt`** is deferred to M8 (§7.4 lists it there); the
   `profileinstaller` dependency is already in.
8. **Purity gate:** `tools/purity_dirs.txt` lists the pure source roots (today `core/src/main`).
   `tools/check_purity.sh` rejects import lines outside `kotlin.*`, `java.*`, `org.json.*` and the
   project's packages, any `java.awt` import, and fully qualified `android.`, `androidx.`,
   `javax.` or `java.awt` names on non-comment lines.
9. **`tools/device/lock.sh`** restores `device_wearing 0` *while still holding the lock* (inside
   the python locker, also on failure or interrupt), not in a bash trap after the release, so it
   can never reset the next holder's session. `HK_LOCK_NO_ADB=1` skips the restore for tests. The
   locker keeps the command's stdin.
10. **`tools/ci.sh --contracts`** checks every `wp*` branch in a temporary detached worktree
    (`/tmp/hk-contracts.*`, removed afterwards) with the current checkout's `contract/**` laid
    over it, compiling `:core:compileTestKotlin :app:compileDebugUnitTestKotlin`. Missing WP11
    pipeline steps (tests, ledger, size report) are reported as SKIP until they exist.
11. **Crash file:** `HammerklavierApp` reads `files/crash.txt` at the next launch (first line →
    status) and renames it `crash.prev.txt`, so the self-test's "crash.txt absent" means "no crash
    since the last launch".
12. **`:app` unit tests** may use the `:core` test fixtures (`testImplementation(testFixtures(
    project(":core")))`).
13. **BuildConfig:** `GIT_BRANCH` (`git symbolic-ref --short`, else "detached") and `GIT_COMMIT`
    (12-hex short hash, "-dirty" when tracked files differ, "uncommitted" before the first
    commit), read at configuration time from the worktree being built.
