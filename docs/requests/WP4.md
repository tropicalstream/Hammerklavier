# Requests from WP2 to WP4 (AudioOutput)

One contract-change request (item 1, for WP0/WP4); the rest are notes on how to drive `engine.EngineCore` from `AudioOutput`.

1. **Bench results.** `Cmd.BENCH` (l = seconds) runs `EngineBench` inside `EngineCore.render` in slices
   of about 2 ms per block (the output stays silent if nothing plays). The results are on the concrete
   class, not on `EngineCoreApi`: `(core as? EngineCore)?.bench?.result` (fields `nsVoiceHermite`,
   `nsVoiceLinear`, `nsVoiceCopy`, `nsSpectral`, `nsComb`, `nsCombNoDispersion`, `nsSoft`, `nsRoom`,
   `nsMaster`, `nsVoice2`, `q0Cap`, `saved*`), and `bench.completed` bumps (volatile) when a run ends.
   Before sending `Cmd.BENCH`, set `(core as EngineCore).benchCpuMhz` to the CPU frequency you read
   (`scaling_cur_freq`) so the figures are normalised to 2.0 GHz. `EngineBench(sr).run(seconds, dsp,
   cpuMhz)` is also usable directly (blocking) on HKAudio for the title-card run.
   **Contract-change request (review of WP2):** reading these through the concrete class bypasses the
   frozen `EngineCoreApi` and breaks when SineCore or a wrapper sits in the slot. Proposed: WP0 adds
   `EngineCoreApi.diagnostics()` keys (or `AudioStats` fields) `benchCompleted`, `bench.nsVoiceHermite`, ...
   one per field above, plus a `benchCpuMhz` argument carried in `Cmd.BENCH` (e.g. `f` = MHz). WP2 will
   publish into them once the contract lands. Interim: only a safe `as?` cast, never `as`.
2. **Tokens.** `prepareBank` returns an `engine.PreparedBank` and `prepareKeyMap` an
   `engine.PreparedKeyMap`; pass them unchanged as `ref` of `SET_BANK` / `SET_KEYMAP`. `prepareBank`
   calls `bank.newReader()` once: the reader it creates is used only on HKAudio.
3. **Frames.** The engine counts its own output frames (256 per `render`) and resyncs to
   `blockStartFrame` whenever it jumps (skipped or repeated block), shifting its END timing, steal ages
   and debug-onset stamps by the same delta, so `CoreClockState` and `debugOnset` stay on WP4's timeline.
   Pass the real output frame of each block.
4. **Idle.** `CoreClockState.idle` is true when paused (or no performance), no voice (main, kill or
   noise) is active and `room.tailActive` is false.
5. **RATE deviation (for the plan owner, §10).** `Cmd.RATE` while playing also rewinds the cursor and
   drops not-yet-heard voices (like PAUSE, without a fade), beyond the §2.5/R5 list, because pending
   voices were scheduled at the old rate. Tested: an onset 1–255 frames after a rate change sounds once.
