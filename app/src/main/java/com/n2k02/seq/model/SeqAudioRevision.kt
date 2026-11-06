package com.n2k02.seq.model

import java.util.concurrent.atomic.AtomicLong

/** In-process signal: no timers, sockets, observers or allocations per PCM block. */
object SeqAudioRevision {
  private val generation=AtomicLong(0L)
  fun current():Long=generation.get()
  fun changed():Long=generation.incrementAndGet()
}
