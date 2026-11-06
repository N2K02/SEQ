# SEQ implementation notes

## Scope of this experimental branch
- UI: charcoal Material 3, top-left page title + top-right SEQ brand, Home / Settings / Quit. Settings: EQ, device profiles, Android Auto, optional AI, Diagnostics, Logs, Updates, About.
- Foreground service started explicitly by user. Only attaches to positive, cooperative Android audio-session IDs (never global mix ID 0); attaches and releases by OPEN/CLOSE actions. Cannot guarantee third-party-app interoperability.
- Platform Equalizer bands are queried from OEM and adjusted conservatively with an estimated attenuation budget. No compressor, reverb, spatializer, loudness maximizer, or automatic music analysis.
- Direct ADB is **on demand**, using a localhost-only embedded ADB client with a separate persisted key. SEQ verifies the result of `id -u` is precisely 2000, runs fixed read-only audio commands, and immediately disconnects. No Shizuku, UserService, root, or TCP/5555 enable operation.
- Android Auto route correction is **conditional** on attachable music session and user-specified Bluetooth output matching or explicit car mode. A connected output is not necessarily the active media route. No car-screen app is advertised because SEQ is not a media player.
- AI is **foreground-only / opt-in** and uses an installed Google AICore model if supported. Output is parsed as three bounded EQ suggestions and applied only when user confirms. No continuous microphone, audio, or GPU workload.
- Native DSP supports 16 biquads (3 tone bands + up to 12 imported), conservative headroom and allocation-free per-frame processing. It is connected to local Media3 decoded PCM16 and reference WAV playback, not injected into third-party apps.

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
