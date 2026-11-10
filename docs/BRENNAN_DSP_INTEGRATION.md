# RootlessBrennanDSP reference integration into SEQ

Upstream: https://github.com/bshea-1/RootlessBrennanDSP (GPL-3.0).
Source is a reference, not vendored or copied. Any direct reuse of GPL code
requires an explicit licensing decision and GPL-compatible distribution.

## Implemented in experimental PR #3
- Bounded stereo single-producer/single-consumer ring buffer with underflow silence,
  no overwrite, and explicit shutdown. Inspired by upstream's ring-buffer
  capture/render split but independently implemented.
- A guarded exclusive PCM processing adapter for SEQ's existing Concert Hall and
  8D Orbit algorithms. It refuses input and mutes output without seven independent
  confirmations: user opt-in, capture consent, verified PCM producer, exclusive
  audio policy registration, original playback suppression, output-route
  verification, and registered rollback.
- Read-only ADB audit of audio routing privileges and remote-submix hints.
- Existing Android AudioEffect EQ, Bluetooth Huawei native EQ and spatial
  Virtualizer remain primary processing paths.
- Unit tests for buffer integrity, shutdown, gate rejection, DSP processing and
  no unsafe ADB mutations.

## Upstream capabilities to evaluate for future, separate integrations
- Parametric EQ and AutoEQ profile parsing and filter validation.
- Convolution (WAV/IRS impulse responses), FIR CPU budgeting and latency.
- Dynamics compander, bass enhancement, stereo widening and DDC corrections.
- Low-latency native direct buffers and realtime scheduling.
- LiveProg scripting and tube modeling are intentionally lower priority because
  of crash, security, CPU and sound-quality risks.
- Shizuku policy calls need OS-version-specific, runtime-verified capability
  checks. Never assume a hidden API is available because another app works.

## Important constraints
- RootlessBrennanDSP's internal playback capture plus AudioTrack replay is not
  equivalent to exclusive routing and can produce duplicate sound.
- It may obtain audio from some apps on a particular phone; that is not proof
  every app/codec/Android Auto route can be captured, and capture-protection
  bypass is not implemented in SEQ.
- Android capture restrictions, user consent and protected-media policies must
  be respected; SEQ must not bypass protected-app capture controls.
- ADB DUMP and shell UID 2000 do not prove MODIFY_AUDIO_ROUTING or that a
  remote-submix policy is registered.
- Hardware-offloaded Bluetooth playback can bypass effects or capture.
- Do not start MediaProjection or AudioTrack from the experimental DSP adapter.
- On device failure, rollback must restore the original audio policy immediately.
- Do not turn on both SEQ AudioEffect and a replay path simultaneously unless
  signal duplication, feedback and processing order are explicitly tested.

## Next verification
1. Install experimental APK, open Settings → ADB connection, run routing audit.
2. Record shell identity, permission hints, submix hints and AudioFlinger routing.
3. Verify supported, user-authorized capture source and exclusive audio policy
   on the actual Android version before connecting a live PCM producer.
4. If exclusivity cannot be proven, keep live capture/replay disabled and use
   the existing EQ and native Bluetooth backend.
5. Only after a safe exclusive path exists, benchmark native FIR/PEQ/compander
   on 48 kHz stereo, including Bluetooth/Android Auto route changes.
