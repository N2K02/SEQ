package com.n2k02.seq.audio

import com.n2k02.seq.model.Tone
import java.io.Closeable

/** Allocated once per owned playback; never share across playback threads. */
class NativeDsp(sampleRate: Int, tone: Tone, enabled: Boolean) : Closeable {
  companion object { init { System.loadLibrary("seq_jni") } }
  private var handle = nCreate()
  init {
    if (handle == 0L || !nConfigure(handle, sampleRate, tone.bass, tone.mid, tone.treble, enabled)) {
      close()
      throw IllegalArgumentException("Native DSP configuration rejected")
    }
  }
  fun process(samples: FloatArray, frames: Int) {
    check(handle != 0L && nProcess(handle, samples, frames)) { "DSP buffer rejected" }
  }
  override fun close() {
    val h = handle
    handle = 0L
    if (h != 0L) nDestroy(h)
  }
  private external fun nCreate(): Long
  private external fun nConfigure(handle: Long, rate: Int, bass: Float, mid: Float, treble: Float, enabled: Boolean): Boolean
  private external fun nProcess(handle: Long, pcm: FloatArray, frames: Int): Boolean
  private external fun nDestroy(handle: Long)
}
