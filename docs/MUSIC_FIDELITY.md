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

Preset transitions split an incoming buffer at the 10 ms fade-out boundary and apply
the new plan to the remaining frames immediately. There is no extra block-sized mute.
Regression coverage includes 240, 480, 512, 960 and 2048-frame buffers.

Correction headroom includes exact and locally sampled narrow-filter centres, DC/Nyquist
and a 0.5 dB interpolation reserve for boosted curves. Coefficients are prepared once per
scan and probe trigonometry is reused across filters. Non-finite PCM is rejected before
stateful processing. The lookahead limiter applies a linked gain cap to its delayed
stereo frame so attack residuals respect the selected sample ceiling. Recovery, narrow
AutoEQ peaks and sudden stereo transients are covered; this is not a true-peak limiter.
