package com.n2k02.seq.audio

import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

class WaveReaderTest {
  private fun wav(): ByteArray {
    val out=ByteArrayOutputStream()
    fun text(s: String)=out.write(s.toByteArray(Charsets.US_ASCII))
    fun i16(n: Int) { out.write(n and 255); out.write(n shr 8 and 255) }
    fun i32(n: Int) { i16(n and 65535); i16(n ushr 16) }
    text("RIFF");i32(40);text("WAVE")
    text("fmt ");i32(16);i16(1);i16(2);i32(48000);i32(192000);i16(4);i16(16)
    text("data");i32(4);i16(32767);i16(-32768)
    return out.toByteArray()
  }
  @Test fun readsPcm16Stereo() {
    val bytes=wav();val stream=ByteArrayInputStream(bytes)
    val info=WaveReader.parse(stream)
    assertEquals(48000,info.sampleRate)
    assertEquals(4L,info.dataBytes)
    val data=ByteArray(4);assertEquals(4,stream.read(data))
    val buffer=FloatArray(2);assertEquals(1,WaveReader.toFloats(data,4,buffer))
    assertTrue(buffer[0]>.99f);assertEquals(-1.0f,buffer[1],0.0001f)
  }
  @Test(expected=IllegalArgumentException::class) fun rejectsGarbage() {
    WaveReader.parse(ByteArrayInputStream(ByteArray(44)))
  }
  @Test(expected=IllegalArgumentException::class) fun rejectsTruncatedFile() {
    WaveReader.parse(ByteArrayInputStream(wav().copyOf(16)))
  }
}
