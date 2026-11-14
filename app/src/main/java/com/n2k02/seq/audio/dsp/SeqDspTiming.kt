package com.n2k02.seq.audio.dsp

/** Wake-up budget of the capture/render threads (pure, unit tested). */
object SeqDspTiming {
  /** Render waits for the capture hand-off (unpark); this is only a safety timeout. */
  const val WAIT_PARK_NS = 20_000_000L
  /** Playback changes and stop/interrupt wake idle threads; no periodic idle polling. */
  fun awaitIdle() = java.util.concurrent.locks.LockSupport.park()

  /** At least 10 ms per cycle (fewer wake-ups), never below the device burst, at most 20 ms. */
  fun chunkFrames(burstFrames: Int, sampleRate: Int): Int {
    val tenMs = sampleRate / 100
    val burst = burstFrames.coerceAtLeast(1)
    // Whole bursts keep the HAL cadence aligned.
    val bursts = (tenMs + burst - 1) / burst
    return (bursts * burst).coerceAtLeast(burst).coerceAtMost(maxOf(burst, sampleRate / 50))
  }
}
