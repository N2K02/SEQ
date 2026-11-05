# SEQ — high-fidelity Android sound engine

SEQ is a battery-conscious experimental sound engine, with a minimal neutral Material 3 interface.

**Experimental PR:** https://github.com/N2K02/SEQ/pull/1

## Working components under development
- Opt-in user-started Android audio-session Equalizer effects for *cooperating* players, with Pure / Reference / Refined modes and release-on-close.
- Native C++20 12-filter biquad engine, used by a local reference WAV player (PCM16 stereo only) via JNI, plus reproducible DSP tests.
- Automatic (named Bluetooth output) or manual car profile selection; can work over car audio routes, but this is **not a car-display app**.
- Optional Shizuku UserService diagnostics, only after permission and shell UID 2000 verification.
- Optional foreground-only Gemini Nano assistance via Android AICore; proposes bounded adjustments which require user confirmation.
- Compact Home / Settings / Quit interface, diagnostics, in-memory Logs, Updates information.

## Accuracy and safety
- Direct/Pure bypass means no SEQ effect, **not** bit-perfect end-to-end hardware playback.
- Android Auto, Spotify and other players require compatible audio sessions; SEQ never force-captures other apps or attaches to global mix ID 0.
- System backend uses OEM-provided fixed-band Equalizer. Native C++ backend is available only for our supported owned PCM playback.
- No compressor, artificial stereo widening, continuous AI processing, unnecessary GPU work, polling loops or microphone permission.
- Native audio is not yet verified against calibrated listening equipment, and car audio compatibility has not yet been measured.

## Build
JDK 17, Android SDK 36, NDK 27.3, Gradle 8.13.
`gradle :app:assembleDebug :app:testDebugUnitTest`
Native tests: `cmake -S dsp -B dsp/build && cmake --build dsp/build && ctest --test-dir dsp/build --output-on-failure`.

CI publishes a debug APK artifact **only after the Android build and tests pass**.
See [Architecture](docs/ARCHITECTURE.md) and [Reference Player](docs/REFERENCE_PLAYER.md).
