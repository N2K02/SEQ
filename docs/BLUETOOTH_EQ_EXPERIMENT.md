# SEQ experimental Bluetooth EQ backends

## What actually works

- **Android Effects**: session-0 DynamicsProcessing, with compatibility attempts at 31,
  10, then 5 bands; OEM Equalizer is the final fallback. Attachment alone cannot
  prove that the active Bluetooth stream goes through the software output mix.
- **Huawei FreeBuds 5 device EQ**: optional, explicit user control on Home.
  Uses the same Bluetooth RFCOMM/SPP protocol as the HFreebuds app:
  `EQ_WRITE=0x2B49`, `EQ_READ=0x2B4A` and readback parameter 2.
  Supported *manufacturer presets only*: Default (1), Bass (2), Treble (3),
  Voices (9). These are **not** interchangeable with SEQ's 10-band curve.
- **Other Bluetooth brands**: remain on Android Effects until a manufacturer
  protocol and a real-device test are available. Do not send Huawei commands
  to unknown devices or claim universal Bluetooth EQ support.

## Safety rules

- Never use native FreeBuds EQ without Bluetooth permission, an explicit user
  toggle, a matching connected FreeBuds 5 route and verified EQ readback.
- Never disable the Android effect solely because an RFCOMM connection opens.
- Invalidate readback on route-id changes or reconnect; avoid duplicated EQ.
- Do not auto-change the Bluetooth codec, developer offload settings or reboot
  the device. The app cannot reliably infer actual A2DP hardware-offload use
  just from having a working session-0 effect.
- A second app (including HFreebuds) may already own the earbud SPP connection;
  the native attempt will fail safely and use Android fallback. A future shared
  companion IPC must authenticate callers and explicitly coordinate device
  ownership; do not expose unrestricted Bluetooth command execution.
- Do not send an arbitrary parametric custom curve to FreeBuds 5: no captured,
  validated write protocol for those curves is currently available.

## Device verification steps

1. Connect HUAWEI FreeBuds 5 and play a continuous track.
2. Turn on the experimental native hardware EQ and grant Bluetooth permission.
3. Switch Default -> Bass -> Treble and check for meaningful audible changes.
4. Confirm logs contain `Huawei native EQ verified preset=...`. If only
   `Native EQ readback unavailable` appears, the platform backend remains.
5. While playing, reconnect the earbuds; ensure the readback is invalidated
   and native EQ is not falsely marked verified.
6. Compare Bluetooth SBC versus LDAC only through user-controlled Android
   settings. Never treat changing codecs as proof that DSP is active.
7. Check for clipping, latency, underflows and CPU impact. Treat Android
   Bluetooth HAL underflows separately from effect-attachment logs.

## Related open-source research

- N2K02/HFreebuds: manufacturer packet transport and FreeBuds 5 captured presets.
- Pittvandewitt/Wavelet: Android audio-effects compatibility and offload issues.
- timschneeb/RootlessJamesDSP: optional captured PCM path, restricted by Android
  playback capture policy and latency; not used as SEQ's default.
- OpenPeats and PairFree: manufacturer-specific adapters; not portable protocols.

Further verified adapters should implement discovery, capability read, command
write, readback and safe fallback before joining the native backend.
