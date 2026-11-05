# Local WAV reference player

Unlike cooperative Android AudioEffect sessions, SEQ's Reference Player really processes PCM via the C++ DSP core. It accepts a user-selected 16-bit stereo PCM WAV (8–192 kHz), streams in 2,048-frame blocks, and writes floating PCM to Android AudioTrack. It uses a foreground service and a MediaSession with Stop controls.

- Pure: no DSP filter stage, but the PCM16-to-float conversion, output routing and Android mixer still mean **not bit-perfect**.
- Reference: three flat bands by default. Refined: subtle 0.6 dB bass / 0.35 dB treble color.
- Platform effects and native DSP are separate; SEQ does not double-process owned audio with itself.
- The player does not decode FLAC/ALAC/DSD, implement an Android Auto browsing app, select a DAC bit-perfect mode, or bypass any OEM audio stack.
- AudioTrack is used instead of a continuously polling engine. Native calculations are per-buffer, non-allocating inside C++ Processor::process; JNI array pinning and Android audio routing remain measurable costs.

Future: Media3 player to support FLAC/ALAC and car browsing when genuinely supported, parameterized headphone-profile import, and automated latency/battery benchmarking on physical hardware.
