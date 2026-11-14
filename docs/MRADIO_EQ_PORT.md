# mRadio equalizer feature port to SEQ

Source inspected: N2K02/MRadio `SoundFx.kt`, `EqualizerUi.kt`, `SoundEngine.kt`, and `SoundProcessor.kt`.

## Implemented

- All ten mRadio EQ presets: Flat, Bass boost, Vocal, Pop, Rock, Dance, Hip-hop, Jazz, Classical, Treble boost.
- mRadio preset frequency anchors (60, 230, 910, 3600, 14000 Hz) interpolated to SEQ's ten editable bands with its existing ±6 dB safety limit.
- mRadio-style horizontally scrolling chips, horizontal gain sliders, bass boost control, a sound-effect toggle (SEQ's existing Sound switch), and reset (SEQ's existing Reset all sound settings button).
- Spatial modes Off, Wide, Headphone 3D, Cinema, Concert hall and 8D orbit displayed with supported capability information. The first three *supported* modes attempt Android's Virtualizer at session 0, and have a strength control. All settings persist between launches and synchronize with the existing output-route processing.
- Existing output-mix EQ, limiter, ADB diagnostics, and FreeBuds native preset override retained.

## Hard limitation: owned PCM vs cross-app Android audio

mRadio's SoundProcessor runs **inside its own Media3 audio sink**. Its stereo crossfeed, Freeverb-style concert hall, and continuously rotating 8D orbit act directly on decoded PCM that the radio app owns. SEQ is an audio-effect companion for *other* apps; an ordinary Android app cannot inject mRadio's PCM SoundProcessor into arbitrary audio apps. Replaying MediaProjection captured audio would add latency and can duplicate sound, and some apps disallow capture.

- **Wide / Headphone 3D / Cinema**: optional, best-effort Android Virtualizer; exact mRadio DSP is not claimed. Hardware offload, OEM effects and routing can bypass this path.
- **Concert hall / 8D orbit**: chips are visible but disabled for external-app media. They would need a separately validated exclusive PCM path (or own playback) to avoid duplicate audio.
- **mRadio per-station loudness normalization**: cannot be faithfully implemented for third-party playback without its PCM. SEQ keeps its existing independent volume-adaptive loudness compensation, which is not the same as stream normalization.
- No automatic Bluetooth codec, offload or system-policy alterations.
- SEQ resets to a flat ten-band EQ and spatial Off. Legacy stored 31-band curves are migrated on read.

## Physical device validation

1. Choose a preset while music plays; verify all ten sliders immediately reflect the new curve.
2. Change a slider and confirm the preset switches to custom tuning and is saved across app restart.
3. Test Reset, confirming ten 0 dB bars, bass 0%, spatial Off.
4. Test Wide and Headphone 3D on a wired headset where AudioEffect Virtualizer is implemented; check logs for attachment only and confirm audible difference by ear.
5. Test Bluetooth LDAC and Android Auto; the hardware playback path may bypass output-mix effects. Confirm SEQ never promises spatial effects are audible just from attachment.
6. Confirm HFreebuds verified native EQ bypass releases SEQ's Virtualizer and other audio effects.


## Privileged audio investigation and experimental PCM prototypes (November 2026)

SEQ now offers these diagnostic-only capabilities under Settings → ADB connection:
- Verified-ADB `dumpsys media.audio_flinger` effect-chain discovery (bounded 60-line output).
- Verified-ADB `dumpsys media.audio_policy` output/route/offload discovery.
- Public `AudioEffect.queryEffects()` inventory for OEM reverb, spatial and virtualizer implementations.
- Transient `PresetReverb` probe on session 0, always released immediately.

IMPORTANT: `PresetReverb` and `EnvironmentalReverb` are auxiliary effects. A third-party player must opt in and set its auxiliary-send level; an ADB shell grant does not configure that player. A positive capability probe **does not mean** Concert Hall is audible across apps.

The pure-Kotlin `SeqHallPcm` and `SeqOrbitPcm` processors are isolated, testable implementations for audio that SEQ already owns. Neither captures nor plays audio. They are deliberately not wired into the global Android effect, because PCM replay without an exclusive source/mute path produces duplicated audio. Future integration requires a proven exclusive PCM path and device-level tests with wired, Bluetooth and Android Auto routes. Shizuku/ADB shell cannot by itself register a new native audio-effect library or intercept every output stream; system-level installation generally requires OEM/root access.

Safety conditions before enabling experimental PCM on any route:
1. Reliable producer ownership of the source PCM; no capture-policy violation.
2. Original playback is absent or legally routed exclusively to the processor.
3. Sample rate/channel format are validated, and no duplicate sound, feedback or echo.
4. Route loss/shutdown cancels DSP immediately; all effects release correctly.
5. Clipping, silence, CPU, latency and reconnect tests pass on a real device.


## RootlessBrennanDSP-inspired extended DSP prototype

Reference: https://github.com/bshea-1/RootlessBrennanDSP (GPLv3). The Kotlin
processor here was independently implemented; no upstream C++/JNI implementation
or build sources were copied. Before integrating upstream files, review GPLv3
compatibility and maintain the required source and notices.

The reference offers AutoEQ, parametric EQ, convolution, dynamic bass, tube
modeling, stereo widening, multiband companding, DDC, and LiveProg scripting.
SEQ already has graphic EQ, parametric headphone-correction filters, bass, and
platform compression. This PR adds **owned/exclusive-PCM implementations** for:

- Stereo widening and tube-style saturation.
- Low-frequency enhancement and envelope-compression with bounded output.
- Stereo FIR convolution from a caller-supplied, validated float impulse.
- Concert Hall and 8D Orbit processors, all chained through
  `SeqExclusivePcmProcessor` and a bounded producer/consumer PCM ring buffer.
- Explicit `SeqExclusiveRouteEvidence` safety requirements; all seven
  conditions must be met before the processor can return usable audio.
- Read-only ADB audit of the SEQ package's routing permission hints, shell UID
  and remote-submix availability. A matching dump line is NOT a registered
  exclusive policy.

**Not yet integrated in live third-party audio:** The source app retains PCM
ownership. RootlessBrennanDSP uses MediaProjection/AudioPlaybackCapture with
AudioRecord → processing → AudioTrack; its own documentation warns of duplicate
sound on some hardware-accelerated paths. The present SEQ PR deliberately does
not start that capture-and-replay path or change another app's capture policy.
The reference's Shizuku setAllowedCapturePolicy workaround is not copied.

**Not yet ported:** Native libjamesdsp, thousands of prepackaged AutoEQ
profiles, multiband compander, DDC databases, and LiveProg scripting. These
would need separate integrations, source/license review, and verification.
SEQ's existing correction and compression cover parts of that functionality,
not feature-identical replacements.

The new DSP components are CPU-only prototypes and cannot be described as
end-to-end low-latency until measured on actual device playback. No user-facing
switch should suggest these PCM modes alter third-party audio.


## New optional third-party PCM compatibility test

SEQ Settings → ADB connection now offers a **user-consented** PCM capture test
based on Android's `MediaProjectionManager` + `AudioPlaybackCaptureConfiguration`
+ `AudioRecord` APIs. This is a **read-only** foreground-service probe:

1. User grants Android's RECORD_AUDIO runtime permission (if needed).
2. User explicitly confirms the system MediaProjection capture dialog.
3. The service probes compatible MEDIA/GAME PCM at 48 kHz stereo float.
4. Only peak/RMS/frame counts are displayed in SEQ and sent to local logs.
5. PCM is never written to files, transmitted or replayed through AudioTrack.
6. User can Stop at any time; MediaProjection revocation and SEQ Quit terminate
   capture. A new start requires new consent on Android 14+.

A zero-level capture stream is reported as silence, not successful audible PCM.
Apps can prohibit capture. Protected/DRM media are not forced to allow capture;
hidden setAllowedCapturePolicy bypasses and ReVanced modifications are not used.
Success here confirms PCM capture is possible for a compatible source; it is
**not** evidence of exclusive routing and does not enable real-time Hall/8D sound.

Advanced PCM options in `SeqAdvancedPcm`: FIR convolution, stereo widening,
soft tube-like saturation, broadband compression and low-frequency enhancement.
`SeqExclusivePcmProcessor` combines these with Hall/8D and SPSC buffering,
but is gated by a seven-condition exclusive-route evidence object and is not
wired to third-party playback output. This intentionally preserves SEQ's
working AudioEffect path for media and Bluetooth/Android Auto playback.

Outstanding features that cannot be described as complete: native libjamesdsp,
LiveProg scripting, full DDC database, packaged AutoEQ library and multiband
compander. SEQ already imports user-provided AutoEQ parametric profiles and
implements basic compression, but is not feature-identical to RootlessBrennanDSP.
