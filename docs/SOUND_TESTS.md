# Sound quality and efficiency validation

Automated tests: native DSP bypass identity, filter response, stable 44.1/48/96 kHz processing, parameter validation, stereo isolation and clipping guard. JVM tests: presets, headroom, audio-session policy, ADB identity and AI bounds.

On-device measurements still required: latency, CPU per buffer, one-hour battery drain, dropouts, headphone/calibrated microphone frequency response and volume-matched listening against XEQ.

Pure bypass preserves SEQ's decoded PCM samples when possible; Android's output stack, Bluetooth codecs, and DAC may still alter playback. Reference is flat unless measured correction is explicitly enabled. Refined intentionally colors the signal and is not objectively higher fidelity.

True-peak limiting, per-speaker vehicle correction, calibrated convolution, and USB bit-perfect mode are *not* claimed in this experimental APK.
