# Sound quality and efficiency validation

Experimental improvements: 10-band default interface; optional 10-band precision controls;
10-band DynamicsProcessing internally where supported; smooth 200 ms EQ updates on
platform and OEM fallbacks; conservative preamp headroom; controlled bass; four
tone modes (Natural, Smooth, Deep Bass, Immersive). Immersive is a tonal
character, not artificial phase widening.

JVM tests cover tone modes, EQ transitions/headroom, manual EQ, routing and playback.

Still requires physical-device tests before merging: test preset changes and slider changes
during continuous audio on phone speaker, wired headphones, Bluetooth and Android Auto.
Check for clicks, pumping, distortion, route-change jumps, background playback and
battery drain. Compare with Pure at matched loudness. Other EQ apps and bypassing
Android mixers can affect compatibility; do not claim universal true-peak limiting.
