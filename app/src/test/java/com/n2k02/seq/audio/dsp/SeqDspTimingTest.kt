package com.n2k02.seq.audio.dsp

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SeqDspTimingTest {
  @Test fun chunkIsAtLeastTenMsInWholeBursts() {
    assertEquals(576, SeqDspTiming.chunkFrames(192, 48000)) // Pixel: 3 bursts = 12 ms
    assertEquals(512, SeqDspTiming.chunkFrames(512, 48000))
    assertEquals(480, SeqDspTiming.chunkFrames(240, 48000))
    assertEquals(441, SeqDspTiming.chunkFrames(441, 44100))
  }

  @Test fun chunkNeverBelowBurstNorAbove20Ms() {
    assertEquals(1024, SeqDspTiming.chunkFrames(1024, 48000)) // burst wins
    assertTrue(SeqDspTiming.chunkFrames(128, 48000) <= 960)
  }

  @Test fun threadsWaitForWorkInsteadOfPolling() {
    val engine = listOf(File("app/src/main/java/com/n2k02/seq/audio/dsp/SeqDspEngine.kt"),
      File("../app/src/main/java/com/n2k02/seq/audio/dsp/SeqDspEngine.kt")).first { it.isFile }.readText()
    assertFalse("no 1 ms polling", engine.contains("parkNanos(1_000_000)"))
    assertFalse("no fixed 40 ms idle loop", engine.contains("Thread.sleep(40)"))
    assertTrue(engine.contains("renderThread?.let(LockSupport::unpark) // hand over"))
    assertTrue(engine.contains("LockSupport.parkNanos(SeqDspTiming.IDLE_PARK_NS)"))
    assertTrue("idle change wakes both threads", engine.contains("captureThread?.let(LockSupport::unpark)"))
    assertTrue(SeqDspTiming.WAIT_PARK_NS >= 10_000_000L)
  }
}
