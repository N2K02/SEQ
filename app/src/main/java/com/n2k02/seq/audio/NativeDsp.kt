package com.n2k02.seq.audio

import com.n2k02.seq.model.*
import java.io.Closeable

/** 16 native filter slots: 3 tone bands plus 12 imported measured filters. */
class NativeDsp(
  sampleRate: Int, tone: Tone, enabled: Boolean,
  preset: HeadphoneProfile? = null
) : Closeable {
  companion object { init { System.loadLibrary("seq_jni") } }
  private var handle = nCreate()
  init {
    val filters = ArrayList<EqFilter>()
    filters += EqFilter(FilterKind.LOW_SHELF,110.0,tone.bass.toDouble(),0.7071)
    filters += EqFilter(FilterKind.PEAK,1100.0,tone.mid.toDouble(),0.80)
    filters += EqFilter(FilterKind.HIGH_SHELF,6000.0,tone.treble.toDouble(),0.7071)
    preset?.filters?.filter { it.enabled }?.let { filters.addAll(it) }
    require(filters.size<=16) { "Too many DSP filters" }
    val spec = DoubleArray(filters.size*4) { index ->
      val f=filters[index/4]
      when(index%4) {
        0 -> f.kind.nativeCode.toDouble()
        1 -> f.frequency
        2 -> f.gain
        else -> f.q
      }
    }
    if(handle==0L || !nConfigure(handle,sampleRate,spec,preset?.preampDb ?: 0.0,enabled)) {
      close()
      throw IllegalArgumentException("Native DSP configuration rejected")
    }
  }
  fun process(samples:FloatArray, frames:Int) {
    check(handle!=0L && nProcess(handle,samples,frames)) { "DSP input rejected" }
  }
  override fun close() {
    val h=handle;handle=0L
    if(h!=0L) nDestroy(h)
  }
  private external fun nCreate():Long
  private external fun nConfigure(handle:Long, rate:Int, filters:DoubleArray, preamp:Double, enabled:Boolean):Boolean
  private external fun nProcess(handle:Long, samples:FloatArray, frames:Int):Boolean
  private external fun nDestroy(handle:Long)
}
