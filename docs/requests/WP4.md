# Requests from WP2 to WP4 (AudioOutput)

No contract change is needed; these are notes on how to drive `engine.EngineCore` from `AudioOutput`.

1. **Bench results.** `Cmd.BENCH` (l = seconds) runs `EngineBench` inside `EngineCore.render` in slices
   of about 2 ms per block (the output stays silent if nothing plays). The results are on the concrete
   class, not on `EngineCoreApi`: `(core as? EngineCore)?.bench?.result` (fields `nsVoiceHermite`,
   `nsVoiceLinear`, `nsVoiceCopy`, `nsSpectral`, `nsComb`, `nsCombNoDispersion`, `nsSoft`, `nsRoom`,
   `nsMaster`, `nsVoice2`, `q0Cap`, `saved*`), and `bench.completed` bumps (volatile) when a run ends.
   Before sending `Cmd.BENCH`, set `(core as EngineCore).benchCpuMhz` to the CPU frequency you read
   (`scaling_cur_freq`) so the figures are normalised to 2.0 GHz. `EngineBench(sr).run(seconds, dsp,
   cpuMhz)` is also usable directly (blocking) on HKAudio for the title-card run.
   If you would rather have these in `AudioStats` or `diagnostics()`, say so and WP0 can add fields.
2. **Tokens.** `prepareBank` returns an `engine.PreparedBank` and `prepareKeyMap` an
   `engine.PreparedKeyMap`; pass them unchanged as `ref` of `SET_BANK` / `SET_KEYMAP`. `prepareBank`
   calls `bank.newReader()` once: the reader it creates is used only on HKAudio.
3. **Frames.** The engine counts its own output frames (256 per `render`), so `blockStartFrame` is
   informational; a short `AudioTrack.write` must not call `render` again for the same block.
4. **Idle.** `CoreClockState.idle` is true when paused (or no performance), no voice (main, kill or
   noise) is active and `room.tailActive` is false.
