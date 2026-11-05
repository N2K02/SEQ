# SEQ — reference-grade audio for Android

SEQ is an experimental, lightweight Android audio-effect controller with a separate testable native DSP reference library. **No claims of universal system-wide processing or bit-perfect audio with EQ enabled.**

## Design
- Compact dark Material 3 UI, Home / Settings / Quit, with a single header.
- Pure / Reference / Refined profiles; Pure releases effects rather than applying a zeroed EQ.
- User-started foreground engine, listens for cooperating players' audio-session broadcasts, releases effects on close/quit.
- Optional Shizuku shell-UID-2000 *diagnostics only*, using a bounded allowlist. Shizuku cannot force arbitrary apps to expose an effectable audio session.
- Optional foreground-only AICore/Gemini Nano assistant, where available; no model bundled and no continuous inference.
- Car output presets based on routed output-device selection; Android Auto itself is not universally detectable from audio routing.
- Native C++ biquad reference DSP, with CMake/CTest signal-quality checks. Separate from the Android AudioEffect backend until a supported PCM ownership path is wired.

## Status
Experimental implementation — **do not assume Android Auto compatibility or audio-quality improvements have been measured on a device.**
App-session processing works only for apps that emit session OPEN/CLOSE broadcasts and allow compatible effects. Pure mode does not change the stream. Android Auto sound processing is conditional on the source player, routing, and the OEM.

## Build
Requires Android SDK 36, JDK 17, Gradle 8.13 (AGP 8.11.2). Run `gradle :app:assembleDebug :app:testDebugUnitTest`.
Native reference tests: `cmake -S dsp -B dsp/build && cmake --build dsp/build && ctest --test-dir dsp/build --output-on-failure`.
On first local checkout, `gradle wrapper --gradle-version 8.13` can generate standard Gradle wrapper files.

## Limits
SEQ does not bypass Android playback-capture policies, alter the car's speaker timing, guarantee platform session effects are bit-perfect, or perform AI audio mastering. EQ in platform mode is the device's available fixed-band Equalizer; the 12-filter DSP is a standalone reference library, not secretly injected into third-party apps.

See [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) for safety, battery strategy, and acceptance criteria.
