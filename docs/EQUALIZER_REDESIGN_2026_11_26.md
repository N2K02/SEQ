# Equalizer redesign and logic recheck — 2026-11-26

One experimental PR. Pure DSP logic is covered by JVM tests; the sound itself still needs
listening and measurement on a device.

## Logic defects found and fixed
| Area | Defect | Fix |
|---|---|---|
| Shelf filters | `SeqBiquad` and `EqResponse` ignored Q on low/high shelves (fixed slope 1), so AutoEQ `LSC`/`HSC` filters with Q ≠ 0.707 were rendered wrongly and the graph disagreed with the AutoEQ source. | Shelves use the RBJ shelf formula with the filter's Q. Q = 1/√2 is bit-identical to before. |
| Two copies of the maths | `EqResponse.db` re-implemented the biquad; any future change could make graph, platform curve and PCM engine disagree. | `EqResponse.db` now evaluates `SeqBiquad.coefficients` (single implementation, tested for equality). |
| Graphic EQ accuracy | Ten overlapping one-octave peaks were applied at the slider gain, so neighbouring bands added up (all bands +6 dB measured ≈ +10 dB mid-band). | `GraphicEq` solves the filter gains (matrix solve + refinement) so the combined response at every band centre equals its slider (±0.15 dB tested). |
| EQ edits | Any change faded the audio to silence (10 ms) and cleared all filter state, so dragging a slider produced repeated dropouts. | Pure EQ changes glide the biquad coefficients (stable by convexity of the stability triangle) with no mute. Spatial, limiter or dynamics changes still fade. |
| Limiter | Sample-peak detection only: inter-sample peaks could exceed the ceiling. | 8-point interpolated inter-sample peak detection (≈0.2 dB error at fs/4); tested with a fs/4 tone whose true peak is 1.41× its sample peak. |
| Range | ±6 dB cap on the manual EQ. | ±12 dB; headroom stays automatic (pre-gain + limiter). Stored ±6 curves remain valid. |
| Validation | `EqFilter.valid()` capped gain at ±12, which would silently drop solver filters. | Default ±24; imported AutoEQ profiles are still limited to ±12. |

## UI
Equalizer card shows the real rendered response (smooth curve) with ten draggable points,
selected-band readout, ±0.5 dB steppers (accessibility), double-tap to flatten a band and a
Flat button. Style/preset chips come first; Bass and 3D follow. No new pages, no Apply step,
no logs screen.

## Not changed / still unverified
- Listening quality, loudness matching and latency on real devices and Android Auto.
- Limiter is a sample-domain look-ahead limiter with inter-sample detection, not a certified
  BS.1770 true-peak meter.
- Android CI (Gradle, Compose compile, instrumented checks) runs on the PR.

## References
- RBJ Audio EQ Cookbook (W3C Note): https://www.w3.org/TR/audio-eq-cookbook/ — peaking and shelf biquads, `alpha = sin(w0)/(2Q)`.
- ITU-R BS.1770 (true-peak via oversampling): https://www.itu.int/rec/R-REC-BS.1770
- EBU R 128 (−1 dBTP maximum permitted true peak): https://tech.ebu.ch/docs/r/r128.pdf
- AutoEq (parametric correction format): https://github.com/jaakkopasanen/AutoEq
- Android DynamicsProcessing (platform fallback): https://developer.android.com/reference/android/media/audiofx/DynamicsProcessing
- RootlessBrennanDSP (architecture reference only, no code copied): https://github.com/bshea-1/RootlessBrennanDSP
