package com.n2k02.seq.model

import android.content.Context
import android.util.Base64
import com.n2k02.seq.audio.WaveReader
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** User-provided, measured stereo impulse response. No synthetic "calibration". */
data class MeasuredImpulse(
  val name: String, val sampleRate: Int,
  val left: FloatArray, val right: FloatArray
) {
  fun valid():Boolean =
    name.isNotBlank() && name.length<=100 && sampleRate in 8000..192000 &&
    left.size==right.size && left.isNotEmpty() && left.size<=256 &&
    (left.asSequence()+right.asSequence()).all { it.isFinite() && it in -4f..4f }
}
/** FIR length capped at 256 taps to bound realtime CPU. */
object MeasuredImpulseImport {
  fun parse(name:String, input:InputStream):MeasuredImpulse {
    val fmt=WaveReader.parse(input)
    require(fmt.dataBytes in 4..1024 && fmt.dataBytes%4==0L) {
      "Impulse must be at most 256 stereo PCM16 frames"
    }
    val bytes=ByteArray(fmt.dataBytes.toInt())
    var count=0
    while(count<bytes.size) {
      val n=input.read(bytes,count,bytes.size-count)
      require(n>0) { "Truncated IR WAV" }
      count+=n
    }
    val samples=FloatArray(bytes.size/2)
    WaveReader.toFloats(bytes,bytes.size,samples)
    val ir=MeasuredImpulse(name.take(100),fmt.sampleRate,
      FloatArray(samples.size/2){ samples[it*2] },
      FloatArray(samples.size/2){ samples[it*2+1] })
    require(ir.valid())
    return ir
  }
}
class MeasuredImpulseStore(context:Context) {
  private val prefs=context.applicationContext.getSharedPreferences("seq_measured_fir",Context.MODE_PRIVATE)
  fun save(ir:MeasuredImpulse) {
    require(ir.valid())
    val buffer=ByteBuffer.allocate(ir.left.size*8).order(ByteOrder.LITTLE_ENDIAN)
    ir.left.indices.forEach { i -> buffer.putFloat(ir.left[i]);buffer.putFloat(ir.right[i]) }
    prefs.edit().putString("name",ir.name).putInt("sampleRate",ir.sampleRate)
      .putString("data",Base64.encodeToString(buffer.array(),Base64.NO_WRAP)).apply()
  }
  fun read():MeasuredImpulse?=runCatching {
    val encoded=prefs.getString("data",null) ?: return null
    val bytes=Base64.decode(encoded,Base64.NO_WRAP)
    require(bytes.size in 8..2048 && bytes.size%8==0)
    val buffer=ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
    val taps=bytes.size/8
    val left=FloatArray(taps);val right=FloatArray(taps)
    for(i in 0 until taps) {left[i]=buffer.float;right[i]=buffer.float}
    MeasuredImpulse(prefs.getString("name","Measured FIR") ?: "Measured FIR",
      prefs.getInt("sampleRate",48000),left,right).takeIf { it.valid() }
  }.getOrNull()
  fun clear(){prefs.edit().clear().apply()}
}
