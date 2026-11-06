# SEQ implementation notes

## Scope of this experimental branch
- UI: charcoal Material 3, top-left page title + top-right SEQ brand, Home / Settings / Quit. Settings: EQ, device profiles, Android Auto, optional AI, Diagnostics, Logs, Updates, About.
- **All media (default):** `SeqGlobalEffect` attaches one effect to the output mix (session 0) so every app's media — speaker, headphones and Android Auto — is processed without needing players to broadcast a session. It is driven by the engine service (settings, route, car-mode changes) and the system-bound media listener (playback start/stop, boot). It is suspended while SEQ's own player plays (native DSP already applied) and released on Pure / engine off.
- **Any-media detection:** `SeqPlaybackMonitor` uses Android's playback callback (every active player, any app, no notification access needed) plus car-mode and device callbacks; on media start or Android Auto entry it re-syncs the effect and tries to start the engine. Notification access is optional: it names the playing app and keeps SEQ alive in the background.
- **Per-app mode (All media off):** attaches only to positive session IDs announced by OPEN/CLOSE control broadcasts.
- Both modes use `SeqSystemEffect`: DynamicsProcessing with 31 one-third-octave bands sampled from the *same* biquad response as the native DSP, input-gain headroom and optional limiter (peak guard); OEM Equalizer is only a fallback.
- Car profile: manual, named Bluetooth device, or Android Auto car UI mode (`SeqRoute`). No compressor, reverb, spatializer, loudness maximizer, or automatic music analysis.
- Direct ADB is **on demand**, using a localhost-only embedded ADB client with a separate persisted key. SEQ verifies the result of `id -u` is precisely 2000, runs fixed read-only audio commands, and immediately disconnects. No Shizuku, UserService, root, or TCP/5555 enable operation.
- Android Auto route correction is **conditional** on attachable music session and user-specified Bluetooth output matching or explicit car mode. A connected output is not necessarily the active media route. No car-screen app is advertised because SEQ is not a media player.
- Native DSP supports 16 biquads (3 tone bands + up to 12 imported), conservative headroom and allocation-free per-frame processing. It is connected to local Media3 decoded PCM16 and reference WAV playback, not injected into third-party apps.

## SEQ Auto — automatic sound engine (default)
No sliders. Two choices only: **SEQ Auto** or **Pure**. `AutoSound` composes, per path:
1. **Output-aware balance** (`RoutePolicy`): phone speaker (bass protection, presence), wired/USB headphones (Harman-style bass shelf), Bluetooth (gentle lift), Android Auto car mode (road-noise bass/treble lift, slight mid scoop).
2. **Adaptive music correction** (`SeqAdaptiveAnalyzer`): Visualizer on the output mix, 2 FFT reads/s only while media plays, 4 s averaged octave spectrum compared with a typical music spectrum; corrects half the deviation, ±3 dB cap, smoothed, applied only when ≥0.4 dB changes. Needs RECORD_AUDIO (Android's permission for output analysis); the microphone is never opened and nothing is stored. Without it, Auto uses 1 + 3.
3. **Volume-adaptive loudness** (not in the car) and always-on limiter with smart headroom.
Generative AI (Gemini Nano assistant) was removed: it cannot hear audio and added settings, not sound quality.

## Sound quality (engine investigation 2026-11-06)
- **Headroom vs loudness:** a +6 dB boost previously cost 6 dB of overall loudness (full headroom), which listeners perceive as worse. With the limiter on (default), headroom is halved and a lookahead limiter catches remaining peaks — native and DynamicsProcessing paths alike.
- **Native limiter:** replaced the block-wise instant gain jump (audible pumping/zipper) with a per-sample 64-frame lookahead envelope, 4× cubic intersample detection continuous across blocks, ~80 ms release, hard bound at the ceiling.
- **Dither:** native output was truncated float→PCM16; now TPDF dither (±1 LSB) removes quantization distortion on quiet passages and fades.
- **FIR normalization:** L1 normalization over-attenuated measured FIRs; now normalized by the peak of the magnitude response (unity worst case, no needless loss).
- **Volume-adaptive loudness:** equal-loudness shelf compensation from media volume (up to +5 dB bass / +2 dB treble at low volume, 0 at max); off in car (head-unit volume) and Pure; re-applied on volume change.

## Fidelity principles
- Do not advertise true bit-perfect audio if any platform equalizer is enabled.
- Reference is neutral by default. "Refined" is a small subjective coloration and is labelled accordingly.
- Analog performance, headphone correction and Android Auto performance require actual devices and calibrated measurements.
- No safety-signal or other-app audio capture.

## Battery / CPU strategy
No periodic background timers, no spectrum animations, no audio FFT running continuously, no continuously active ADB service. Android system effects are handled by OS; native reference DSP is allocation-free in its process() method. Use small in-memory logs and dynamic receiver/device callbacks. Avoid starting when user has disabled engine.

## Future verified-deliverable gates (not yet complete)
1. Android CI green and device smoke test.
2. Measured external-player sessions and Android Auto routes across real devices.
3. Verify Media3 FLAC/WAV/other codecs and the native decoded PCM16 DSP, then extend measurement-verified 24/32-bit PCM handling.
4. Verified per-headphone AutoEQ data licensing + profile import/calibration; car calibration with an external measurement microphone.
5. Investigate app store compliance, car media eligibility if SEQ becomes a genuine player, notification permissions and background restrictions.
6. On-device listening studies comparing XEQ and SEQ with matched loudness.

## Tests
Unit tests include tone/headroom calculations and AI output constraints. Native CTest covers bypass, neutral behavior, channel isolation, clipping counter, filter response and invalid settings. Device instrumentation remains required for session ownership, direct ADB transport and Android Auto.

## ADB UX parity with VRec
- Diagnostics begins with SEQ ADB Details (shell UID, transport, last verification, discovered ports).
- No manual pairing or port entry. An explicitly enabled Accessibility service reads **only Android Settings** pairing dialogs; no code is persisted.
- mDNS discovers local-only services. TCP/5555 migration requires a verified UID 2000 and re-verification after adbd restarts.
- Optional boot restore respects a same-boot user-off state. WorkManager offers bounded 15-minute recovery if enabled and previously authorized.
- Quit stops searches and worker, without changing audio DSP permission restrictions.

## VRec parity audit — Diagnostics permission card
- A separate Permissions card shows Accessibility, ADB UID 2000, optional Secure settings and optional notifications. Secure settings stays visible even when reboot restore is off.
- Accessibility and notification permissions refresh after returning from Android Settings. A previously authorized UID is never displayed as an active connection, and a prior Android boot's UID is not considered currently verified.
- Disabling TCP/5555 asks for confirmation because VRec can share the listener.
- WorkManager owns and disposes isolated ADB diagnostic controllers so it cannot hijack the foreground pairing-code Accessibility owner.
- VRec recording-only permissions and its capture daemon are intentionally not copied into SEQ.
