# SEQ — high-fidelity Android sound engine

SEQ is a battery-conscious experimental sound engine, with a minimal neutral Material 3 interface.

**Experimental PR:** https://github.com/N2K02/SEQ/pull/1

## Working components under development
- Opt-in user-started Android audio-session Equalizer effects for *cooperating* players, with Pure / Reference / Refined modes and release-on-close.
- Native C++20 DSP with 3 tone controls and up to 12 imported measured filters, used by the Media3 PCM16 processor and direct WAV reference player.
- Media3 local music library + actual Android Auto MediaLibraryService (host validation pending); car profiles can be selected manually or by named connected output. Imported AutoEq headphone corrections are optional.
- Optional direct ADB diagnostics over loopback, using VRec's libadb architecture, persistent per-app ADB identity and shell UID 2000 verification. Connection is on demand and closes after diagnostics.
- Optional foreground-only Gemini Nano assistance via Android AICore; proposes bounded adjustments which require user confirmation.
- Compact Home / Settings / Quit interface, diagnostics, in-memory Logs, Updates information.

## Accuracy and safety
- Direct/Pure bypass means no SEQ effect, **not** bit-perfect end-to-end hardware playback.
- Spotify and other external apps require compatible audio sessions; SEQ never force-captures other apps or attaches to global mix ID 0. Android Auto music browsing is implemented for SEQ's **own local library**, but car hardware compatibility is not yet verified.
- System backend uses OEM-provided fixed-band Equalizer. Native C++ backend is available only for our supported owned PCM playback.
- No compressor, artificial stereo widening, continuous AI processing, GPU signal processing, polling loops or microphone permission. AutoEq correction is off until explicitly enabled.
- Native audio has not yet been verified against calibrated listening equipment. Media3's native DSP supports decoded **stereo PCM16**, with other formats limited by device decoder support; it does not promise bit-perfect audio.

## Build
JDK 17, Android SDK 36, NDK 27.3, Gradle 8.13.
`gradle :app:assembleDebug :app:testDebugUnitTest`
Native tests: `cmake -S dsp -B dsp/build && cmake --build dsp/build && ctest --test-dir dsp/build --output-on-failure`.

CI publishes a debug APK artifact **only after the Android build and tests pass**.
See [Architecture](docs/ARCHITECTURE.md) and [Reference Player](docs/REFERENCE_PLAYER.md).

## Direct ADB setup
1. If VRec has already enabled TCP/5555 on the device, open SEQ → Settings → Diagnostics and check port 5555. Authorize SEQ's separate ADB identity when Android prompts. VRec's own private key cannot be shared between app sandboxes.
2. If TCP/5555 is unavailable, enable Wireless debugging in Developer options, open *Pair device with pairing code*, enter the displayed six-digit code and pairing port in SEQ Diagnostics. After pairing, enter the **connection** port from the Wireless debugging page and run Verify. These two ports differ.
3. When UID 2000 is verified, SEQ runs a bounded read-only audio diagnostics command and **disconnects immediately** to avoid idle battery use. It never turns on ADB TCP mode, modifies audio settings, or asks for root.

## APK testing
1. Install the debug APK from successful GitHub Actions run on the experimental PR.
2. Keep Reference neutral, or import an AutoEq/Equalizer APO parametric text preset in Settings → Equalizer.
3. Add WAV, FLAC, AAC, MP3 or Ogg files under Settings → Music Library; play and verify the PCM processing path.
4. Connect Android Auto and open SEQ's local music library. External-app EQ still depends on each source's session and OEM effects.
5. To inspect audio using ADB UID 2000, open Settings → Diagnostics, enter the local Wireless debugging connection port or existing TCP/5555. On-demand only; no persistent shell.
6. Test Quit during playback; compare Pure and Reference at matched volume and inspect Logs.
