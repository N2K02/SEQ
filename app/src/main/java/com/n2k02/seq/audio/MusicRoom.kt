package com.n2k02.seq.audio

import kotlin.math.*

/** Small stereo room: filtered send, two damped combs per ear, normalised feedback.
 * Keeps dry stereo intact. No allocations in process; all state is reset on mode changes.
 */
internal class MusicRoom(private val rate: Int) {
    private val lengths = intArrayOf(1213, 1429, 1283, 1499).map { max(1, (it * rate / 48000.0).roundToInt()) }
    private val lines = lengths.map { FloatArray(it) }
    private val heads = IntArray(4)
    private val damped = FloatArray(4)
    private val previous = FloatArray(2)
    private val high = FloatArray(2)
    private val hp = exp(-2 * PI * 180 / rate).toFloat()
    private val lp = (1 - exp(-2 * PI * 4200 / rate)).toFloat()
    var feedback = 0.7f
    var wet = 0f
    var outL = 0f; private set
    var outR = 0f; private set
    fun reset() {
        lines.forEach { it.fill(0f) }; heads.fill(0); damped.fill(0f)
        previous.fill(0f); high.fill(0f); outL = 0f; outR = 0f
    }
    fun process(l: Float, r: Float) {
        val fb = feedback.coerceIn(0f, 0.75f)
        high[0] = hp * (high[0] + l - previous[0]); previous[0] = l
        high[1] = hp * (high[1] + r - previous[1]); previous[1] = r
        var left = 0f; var right = 0f
        for (k in 0..3) {
            val tap = lines[k][heads[k]]
            damped[k] += lp * (tap - damped[k])
            if (abs(damped[k]) < 1e-20f) damped[k] = 0f
            // Input (1-feedback) makes the tail bounded independently of decay.
            lines[k][heads[k]] = high[k/2] * (1-fb) + damped[k] * fb
            heads[k] = if (heads[k]+1 == lengths[k]) 0 else heads[k]+1
            if (k < 2) left += tap else right += tap
        }
        outL = l + left * 0.5f * wet
        outR = r + right * 0.5f * wet
    }
}
