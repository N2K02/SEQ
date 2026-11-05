# Sound quality and efficiency validation

Automated tests: native DSP bypass identity, filter response, stable 44.1/48/96 kHz processing, parameter validation, stereo isolation and clipping guard. JVM tests: presets, headroom, audio-session policy, ADB identity and AI bounds.

On-device measurements still required: latency, CPU per buffer, one-hour battery drain, dropouts, headphone/calibrated microphone frequency response and volume-matched listening against XEQ.

Pure bypass preserves SEQ's decoded PCM samples when possible; Android's output stack, Bluetooth codecs, and DAC may still alter playback. Reference is flat unless measured correction is explicitly enabled. Refined intentionally colors the signal and is not objectively higher fidelity.

True-peak limiting, per-speaker vehicle correction, calibrated convolution, and USB bit-perfect mode are *not* claimed in this experimental APK.

## Optional advanced engine (SEQ 0.3)
- User-loaded stereo PCM16 impulse responses can run a bounded 256-tap FIR per channel. This is a **short, user-measured response**, not automated full-room calibration or long partitioned-FFT convolution.
- The 4x cubic-interpolated peak guard applies conservative block gain adjustment on SEQ-owned PCM. It is **not** a fully ITU BS.1770 compliant true-peak limiter.
- Android 14+ USB direct PCM16 WAV can request the platform BIT_PERFECT mixer attribute only when the connected USB DAC publishes a matching profile. Pure mode required; no EQ/FIR/peak processing or float conversion. It refuses silent fallback.
- Hardware output quality and electrical bit-perfectness must still be tested with compatible devices and a bitstream analyzer.
