# Music fidelity and Concert safety

Concert is now a subtle filtered stereo room rather than a loud mono echo. Dry left/right
channels remain intact. The room uses four short preallocated damped feedback delays,
a 180 Hz high-pass send and a normalised decay; bass and vocals are less masked.
Intensity is proportional to the effect and 0% is neutral in every spatial mode.

Tonal preset curves are unchanged: Flat, Bass boost, Vocal, Pop, Rock, Dance, Hip-hop,
Jazz, Classical and Treble boost. Headroom now includes the combined EQ/bass response
and spatial gain. These settings can sound quieter than the old overdriven versions;
use output volume for listening level. Loudness matching remains a separate feature.

No GPU, convolution or additional playback stream was added. Delay buffers are reused;
unused stages are skipped. These are implementation properties, not measured device
battery claims. Android Auto, Bluetooth, phone speakers and headphones still require
listening and performance checks on hardware.

Regression checks cover all ten presets with extra bass, high-level sine distortion,
0% neutrality, stereo separation, mode changes and stale tails. Tones establish linearity
and peak safety for the tested cases; they do not replace music listening tests or prove
intersample true-peak protection.
