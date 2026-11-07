package com.n2k02.seq.model

import com.n2k02.seq.audio.SpectrumMath
import org.junit.Assert.*
import org.junit.Test

class AutoSoundTest {
  private val target = doubleArrayOf(4.0, 4.0, 2.0, 1.0, 0.0, -3.0, -6.0, -10.0)

  @Test fun autoIsDefaultAndOldProfilesAreGone() {
    assertEquals(AudioProfile.AUTO, EngineSettings().profile)
    assertEquals(listOf(AudioProfile.AUTO, AudioProfile.PURE), AudioProfile.entries.toList())
  }
  @Test fun everyOutputHasItsOwnBalance() {
    val tones = RouteKind.entries.map { AutoSound.base(it) }.toSet()
    assertEquals(RouteKind.entries.size, tones.size)
    assertTrue(AutoSound.base(RouteKind.SPEAKER).bass < 0f) // protect small speakers
    assertTrue(AutoSound.base(RouteKind.WIRED).bass > 0f)
    assertTrue(AutoSound.base(RouteKind.CAR).treble > 0f)
  }
  @Test fun loudnessFollowsVolumeButNotInCar() {
    val quiet = AutoSound.compose(RouteKind.WIRED, 0.3f, Tone())
    val full = AutoSound.compose(RouteKind.WIRED, 1f, Tone())
    assertTrue(quiet.bass > full.bass)
    assertEquals(AutoSound.compose(RouteKind.CAR, 1f, Tone()), AutoSound.compose(RouteKind.CAR, 0.2f, Tone()))
  }
  @Test fun balancedMusicNeedsNoCorrection() {
    val t = AutoSound.adapt(target.map { it + 50 }.toDoubleArray())!!
    assertEquals(0f, t.bass, 1e-4f); assertEquals(0f, t.treble, 1e-4f); assertEquals(0f, t.mid, 1e-4f)
  }
  @Test fun thinBrightMusicGetsWarmerGently() {
    val thin = target.copyOf().also { it[0] -= 8; it[1] -= 8; it[6] += 4; it[7] += 4 }
    val t = AutoSound.adapt(thin)!!
    assertTrue(t.bass > 0f && t.bass <= 3f)
    assertTrue(t.treble < 0f && t.treble >= -3f)
  }
  @Test fun correctionIsBoundedAndRejectsBadInput() {
    val extreme = target.copyOf().also { it[0] -= 60; it[1] -= 60 }
    assertEquals(3f, AutoSound.adapt(extreme)!!.bass, 0f)
    assertNull(AutoSound.adapt(DoubleArray(3)))
    assertNull(AutoSound.adapt(DoubleArray(8) { Double.NaN }))
  }
  @Test fun changesAreSmoothAndOnlyAppliedWhenAudible() {
    val s = AutoSound.smooth(Tone(), Tone(bass = 3f))
    assertEquals(0.6f, s.bass, 1e-4f)
    assertFalse(AutoSound.significant(Tone(), Tone(bass = 0.2f)))
    assertTrue(AutoSound.significant(Tone(), Tone(treble = 0.5f)))
  }
  @Test fun spectrumMathReadsVisualizerLayout() {
    val n = 1024
    val fft = ByteArray(n)
    val rate = 48000.0
    for (k in 1 until n / 2) { fft[2 * k] = 20; fft[2 * k + 1] = 0 } // flat spectrum
    val db = SpectrumMath.octaveDb(fft, rate, AutoSound.OCTAVES)!!
    db.forEach { assertEquals(db[4], it, 0.01) }
    assertNull(SpectrumMath.octaveDb(ByteArray(n), rate, AutoSound.OCTAVES)) // silence
  }
}
