# SEQ — high-fidelity Android sound engine

SEQ is a battery-conscious experimental sound engine, with a minimal neutral Material 3 interface.

**Experimental PR:** https://github.com/N2K02/SEQ/pull/1

## Working components under development
- **All media** processing (default): output-mix DynamicsProcessing EQ (31 bands, headroom, optional limiter) for every app, including Android Auto; per-app session mode available.
- Native C++20 DSP with 3 tone controls and up to 12 imported measured filters, used by the Media3 PCM16 processor and direct WAV reference player.
- Media3 local music library + actual Android Auto MediaLibraryService (host validation pending); car profiles can be selected manually or by named connected output. Imported AutoEq headphone corrections are optional.
- Optional direct ADB diagnostics over loopback, using VRec's libadb architecture, persistent per-app ADB identity and shell UID 2000 verification. Connection is on demand and closes after diagnostics.
- Optional foreground-only Gemini Nano assistance via Android AICore; proposes bounded adjustments which require user confirmation.
- mRadio-style single-header Home / Settings / Quit navigation, shared Material 3 Expressive `n2k02theme` color/motion system, built-in `n2k02updater` Updates submenu with pending-update badges and install guards.

## Accuracy and safety
- Direct/Pure bypass means no SEQ effect, **not** bit-perfect end-to-end hardware playback.
- Output-mix effects are deprecated by Android but supported on Pixel; another EQ app with control of the mix (e.g. Wavelet) takes precedence. Android Auto projection routing must be verified on a head unit/DHU.
- Native C++ backend is used for SEQ's own playback; the platform effect is suspended then to avoid double EQ.
- No compressor, artificial stereo widening, continuous AI processing, GPU signal processing, polling loops or microphone permission. AutoEq correction is off until explicitly enabled.
- Native audio has not yet been verified against calibrated listening equipment. Media3's native DSP supports decoded **stereo PCM16**, with other formats limited by device decoder support; it does not promise bit-perfect audio.

## Build and release channels
JDK 17, Android SDK 37, NDK 27.3 and Gradle 9.6.
`gradle :app:testDebugUnitTest :app:lintDebug :app:assembleRelease -PseqVersion=0.3.0 -PseqChannel=experimental -PseqBuild=123`
Native tests: `cmake -S dsp -B dsp/build && cmake --build dsp/build && ctest --test-dir dsp/build --output-on-failure`.

Both channels use the same **R8-minified release APK** and independent native DSP tests. `main` publishes stable releases; the single experimental branch publishes prereleases. Both require the **same SEQ-specific persistent signing identity**; a mismatched or missing signing secret prevents release publication. See [Release pipeline and one-time signing setup](docs/RELEASE_PIPELINE.md), [Architecture](docs/ARCHITECTURE.md) and [Reference Player](docs/REFERENCE_PLAYER.md).

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

Shared private dependencies: CI needs the `N2K02_PACKAGES_TOKEN` repository secret with read access to `N2K02/n2k02theme` and `N2K02/n2k02updater` GitHub Packages; credentials must never enter public source or the APK.

## Material 3 Expressive and GitHub pre-releases
SEQ shares `n2k02theme` and `n2k02updater` with mRadio and VRec. A verified CI build publishes a GitHub **pre-release** APK and an experimental updater manifest on the `gh-pages` branch. The updater checks the public manifest and verifies the package and APK SHA-256 before installation. No GitHub token is bundled in SEQ.

The older experimental debug APKs used an unstable signing identity. New stable and experimental releases will require the same permanently configured SEQ-specific signing key, and the first migration may require uninstalling older debug builds. Refer to [Release pipeline](docs/RELEASE_PIPELINE.md).

## Automatic ADB (VRec-style)
SEQ contains no manual pairing-code or port input UI. The user enables Accessibility once in Android Settings; SEQ reads only the Settings Wireless debugging pairing dialog, discovers on-device TLS/pairing ports via mDNS, and verifies shell UID 2000. It supports automatic reconnect, optional TCP/5555 promotion, boot recovery, watchdog and opt-in restore-after-restart with Android-granted WRITE_SECURE_SETTINGS. TCP/5555 may be network-reachable; switch it off when unused. Closing Diagnostics releases discovery and the ADB socket; Quit cancels scheduled recovery. Logs and About screens are removed, while bounded internal diagnostics remain.
