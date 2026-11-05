package com.n2k02.seq.model

import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

class MeasuredImpulseTest {
  private fun wav(frames:Int=2):ByteArray {
    val out=ByteArrayOutputStream()
    fun text(s:String)=out.write(s.toByteArray(Charsets.US_ASCII))
    fun w16(v:Int){out.write(v and 255);out.write(v shr 8 and 255)}
    fun w32(v:Int){w16(v and 65535);w16(v ushr 16)}
    text("RIFF");w32(36+frames*4);text("WAVE")
    text("fmt ");w32(16);w16(1);w16(2);w32(48000);w32(192000);w16(4);w16(16)
    text("data");w32(frames*4)
    repeat(frames){w16(if(it==0)32767 else 0);w16(if(it==0)32767 else 0)}
    return out.toByteArray()
  }
  @Test fun stereoImpulse() {
    val ir=MeasuredImpulseImport.parse("Measured",ByteArrayInputStream(wav()))
    assertTrue(ir.valid());assertEquals(48000,ir.sampleRate)
    assertEquals(2,ir.left.size);assertTrue(ir.left[0]>.99f)
  }
  @Test(expected=IllegalArgumentException::class) fun overlyLongRejected() {
    MeasuredImpulseImport.parse("Huge",ByteArrayInputStream(wav(257)))
  }
  @Test(expected=IllegalArgumentException::class) fun truncatedRejected() {
    MeasuredImpulseImport.parse("Short",ByteArrayInputStream(wav().copyOf(49)))
  }
  @Test fun dangerousValuesRejected() {
    assertFalse(MeasuredImpulse("NaN",48000,floatArrayOf(Float.NaN),floatArrayOf(0f)).valid())
  }
}
