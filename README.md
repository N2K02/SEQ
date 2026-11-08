# SEQ — high-fidelity Android sound engine

SEQ is a battery-conscious experimental sound engine, with a minimal neutral Material 3 interface.

**Experimental PR:** https://github.com/N2K02/SEQ/pull/1

## What SEQ does
- **SEQ Auto** (default) tunes media routed through a compatible Android output mix. It uses 31-band DynamicsProcessing with headroom and an optional limiter. If the platform rejects that backend, SEQ tries the OEM EQ with cut-only band levels. **Pure** bypasses SEQ processing.
- On Android 13 and later, SEQ uses Android's predicted media route when available. Wired headphones, Bluetooth, phone speaker and car mode each have their own tuning.
- The Home screen reports whether Android enabled the effect. Actual processing depends on the device, player and route. Verify wired and wireless Android Auto on a head unit or DHU, including apps that use offload or direct playback.

Both channels use the same **R8-minified release APK**. `main` publishes stable releases; the single experimental branch publishes prereleases. Both require the **same SEQ-specific persistent signing identity**; a mismatched or missing signing secret prevents release publication. See [Release pipeline and one-time signing setup](docs/RELEASE_PIPELINE.md) and [Architecture](docs/ARCHITECTURE.md).

## Direct ADB setup
1. If VRec has already enabled TCP/5555 on the device, open SEQ → Settings → Diagnostics and check port 5555. Authorize SEQ's separate ADB identity when Android prompts. VRec's own private key cannot be shared between app sandboxes.
2. If TCP/5555 is unavailable, enable Wireless debugging in Developer options, open *Pair device with pairing code*, enter the displayed six-digit code and pairing port in SEQ Diagnostics. After pairing, enter the **connection** port from the Wireless debugging page and run Verify. These two ports differ.
3. When UID 2000 is verified, SEQ runs a bounded read-only audio diagnostics command and **disconnects immediately** to avoid idle battery use. It never turns on ADB TCP mode, modifies audio settings, or asks for root.

## APK testing
1. Install the experimental APK from PR #1 and turn the engine on (allow notifications and audio analysis).
2. Play music in any app; Home should show "Output mix effect enabled" and the selected tuning. Compare SEQ Auto and Pure to verify the effect on that route.
3. Connect Android Auto and play from Spotify/YouTube Music; Home should show "Android Auto".
4. Compare SEQ Auto and Pure at matched volume; test Quit during playback.

Shared private dependencies: CI needs the `N2K02_PACKAGES_TOKEN` repository secret with read access to `N2K02/n2k02theme` and `N2K02/n2k02updater` GitHub Packages; credentials must never enter public source or the APK.

## Material 3 Expressive and GitHub pre-releases
SEQ shares `n2k02theme` and `n2k02updater` with mRadio and VRec. A verified CI build publishes a GitHub **pre-release** APK and an experimental updater manifest on the `gh-pages` branch. The updater checks the public manifest and verifies the package and APK SHA-256 before installation. No GitHub token is bundled in SEQ.

The older experimental debug APKs used an unstable signing identity. New stable and experimental releases will require the same permanently configured SEQ-specific signing key, and the first migration may require uninstalling older debug builds. Refer to [Release pipeline](docs/RELEASE_PIPELINE.md).

## Automatic ADB (VRec-style)
SEQ contains no manual pairing-code or port input UI. The user enables Accessibility once in Android Settings; SEQ reads only the Settings Wireless debugging pairing dialog, discovers on-device TLS/pairing ports via mDNS, and verifies shell UID 2000. It supports automatic reconnect, optional TCP/5555 promotion, boot recovery, watchdog and opt-in restore-after-restart with Android-granted WRITE_SECURE_SETTINGS. TCP/5555 may be network-reachable; switch it off when unused. Closing Diagnostics releases discovery and the ADB socket; Quit cancels scheduled recovery. Logs and About screens are removed, while bounded internal diagnostics remain.
