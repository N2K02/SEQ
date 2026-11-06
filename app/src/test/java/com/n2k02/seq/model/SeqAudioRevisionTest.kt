package com.n2k02.seq.model

import org.junit.Assert.*
import org.junit.Test

class SeqAudioRevisionTest {
  @Test fun revisionIsMonotonicWithoutBackgroundPolling() {
    val before=SeqAudioRevision.current()
    val next=SeqAudioRevision.changed()
    assertEquals(before+1,next)
    assertEquals(next,SeqAudioRevision.current())
  }
}
