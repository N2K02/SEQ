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
