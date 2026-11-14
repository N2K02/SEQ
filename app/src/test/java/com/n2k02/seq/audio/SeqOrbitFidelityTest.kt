package com.n2k02.seq.audio

import org.junit.Assert.*
import org.junit.Test
import kotlin.math.*

class SeqOrbitFidelityTest {
  @Test fun orbitDoesNotCancelAntiPhaseStereoOrLeakLeftIntoRight() {
    val rate = 48000
    val orbit = SeqOrbitPcm(rate).apply { strength = 1f }
    val b = FloatArray(rate * 2) { i -> (.5 * sin(2 * PI * 997 * (i / 2) / rate) * if (i % 2 == 0) 1 else -1).toFloat() }
    orbit.process(b)
    val rms = sqrt(b.sumOf { it.toDouble() * it } / b.size)
    assertTrue("stereo content retained: rms=$rms", rms > .2)
    orbit.reset()
    val leftOnly = FloatArray(4800) { if (it % 2 == 0) .3f else 0f }
    orbit.process(leftOnly)
    assertTrue(leftOnly.indices.filter { it % 2 == 1 }.all { leftOnly[it] == 0f })
  }

  @Test fun centreCrossingsAreSmoothAndBlockSizeDoesNotChangeTheResult() {
    val rate = 8000 // four crossings in four seconds at maximum speed
    fun render(block: Int): FloatArray {
      val orbit = SeqOrbitPcm(rate).apply { strength = 1f; revolutionsPerMinute = 30f }
      val output = FloatArray(rate * 4 * 2)
      var at = 0
      while (at < output.size / 2) {
        val frames = minOf(block, output.size / 2 - at)
        val b = FloatArray(frames * 2) { i -> (.5 * sin(2 * PI * 40 * (at + i / 2) / rate)).toFloat() }
        orbit.process(b); b.copyInto(output, at * 2); at += frames
      }
      return output
    }
    val a = render(127); val b = render(512)
    assertArrayEquals(a, b, 0f)
    var jump = 0f
    for (i in 2 until a.size) jump = max(jump, abs(a[i] - a[i - 2]))
    assertTrue("smooth crossing jump=$jump", jump < .025f)
    assertTrue(a.all { it.isFinite() && abs(it) <= .501f })
  }
}
