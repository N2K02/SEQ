# SEQ implementation notes

## Scope of this experimental branch
- UI: charcoal Material 3, top-left page title + top-right SEQ brand, Home / Settings / Quit. Settings: EQ, device profiles, Android Auto, optional AI, Diagnostics, Logs, Updates, About.
- Foreground service started explicitly by user. Only attaches to positive, cooperative Android audio-session IDs (never global mix ID 0); attaches and releases by OPEN/CLOSE actions. Cannot guarantee third-party-app interoperability.
- Platform Equalizer bands are queried from OEM and adjusted conservatively with an estimated attenuation budget. No compressor, reverb, spatializer, loudness maximizer, or automatic music analysis.
- Direct ADB is **on demand**, using a localhost-only embedded ADB client with a separate persisted key. SEQ verifies the result of `id -u` is precisely 2000, runs fixed read-only audio commands, and immediately disconnects. No Shizuku, UserService, root, or TCP/5555 enable operation.
- Android Auto route correction is **conditional** on attachable music session and user-specified Bluetooth output matching or explicit car mode. A connected output is not necessarily the active media route. No car-screen app is advertised because SEQ is not a media player.
- AI is **foreground-only / opt-in** and uses an installed Google AICore model if supported. Output is parsed as three bounded EQ suggestions and applied only when user confirms. No continuous microphone, audio, or GPU workload.
- Native reference DSP is a **separate C++20 PCM processing library**, not currently used for third-party Android audio. 12 biquads, conservative headroom, bypass identity and no audio-thread allocations. Configure only between buffer processing calls.

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
3. Extend the native PCM16 WAV player to FLAC and other high-resolution input formats; measure latency and bit-exact bypass on devices.
4. Verified per-headphone AutoEQ data licensing + profile import/calibration; car calibration with an external measurement microphone.
5. Investigate app store compliance, car media eligibility if SEQ becomes a genuine player, notification permissions and background restrictions.
6. On-device listening studies comparing XEQ and SEQ with matched loudness.

## Tests
Unit tests include tone/headroom calculations and AI output constraints. Native CTest covers bypass, neutral behavior, channel isolation, clipping counter, filter response and invalid settings. Device instrumentation will be needed for session ownership, direct ADB socket and key lifecycle and Android Auto.
