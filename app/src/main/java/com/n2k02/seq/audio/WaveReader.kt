package com.n2k02.seq.audio

import java.io.InputStream

data class WaveInfo(val sampleRate: Int, val channels: Int, val bitsPerSample: Int, val dataBytes: Long)

/** Streaming RIFF/WAVE PCM16 parser with bounded chunk and size validation. */
object WaveReader {
  private fun readFully(input: InputStream, buffer: ByteArray, n: Int = buffer.size) {
    var got=0
    while (got<n) {
      val count=input.read(buffer,got,n-got)
      if (count<=0) throw IllegalArgumentException("Truncated WAV file")
      got+=count
    }
  }
  private fun u16(b: ByteArray, i: Int)=(b[i].toInt() and 0xff) or ((b[i+1].toInt() and 0xff) shl 8)
  private fun u32(b: ByteArray, i: Int): Long =
    ((u16(b,i).toLong() or (u16(b,i+2).toLong() shl 16)) and 0xffffffffL)
  private fun skip(input: InputStream, count: Long) {
    require(count in 0..1000000000) { "Unsupported WAV metadata size" }
    var left=count
    val scratch=ByteArray(4096)
    while(left>0) {
      val n=minOf(left,scratch.size.toLong()).toInt()
      readFully(input,scratch,n)
      left-=n
    }
  }
  fun parse(input: InputStream): WaveInfo {
    val riff=ByteArray(12); readFully(input,riff)
    require(String(riff,0,4,Charsets.US_ASCII)=="RIFF" &&
      String(riff,8,4,Charsets.US_ASCII)=="WAVE") { "Not an uncompressed RIFF/WAVE file" }
    var sampleRate=0; var channels=0; var bits=0
    repeat(64) {
      val chunk=ByteArray(8); readFully(input,chunk)
      val id=String(chunk,0,4,Charsets.US_ASCII)
      val size=u32(chunk,4)
      when(id) {
        "fmt " -> {
          require(size in 16..4096) { "Invalid fmt chunk" }
          val data=ByteArray(size.toInt()); readFully(input,data)
          val encoding=u16(data,0)
          channels=u16(data,2); sampleRate=u32(data,4).toInt(); bits=u16(data,14)
          require(encoding==1 && channels==2 && bits==16 && sampleRate in 8000..192000) {
            "Only stereo 16-bit PCM WAV (8–192 kHz) is supported"
          }
          if ((size and 1L)!=0L) skip(input,1)
        }
        "data" -> {
          require(sampleRate>0 && channels==2 && bits==16) { "WAV fmt chunk must precede audio" }
          require(size%4==0L) { "Unaligned stereo PCM data" }
          return WaveInfo(sampleRate,channels,bits,size)
        }
        else -> skip(input,size+(size and 1L))
      }
    }
    throw IllegalArgumentException("Audio chunk not found")
  }
  fun toFloats(data: ByteArray, validBytes: Int, output: FloatArray): Int {
    require(validBytes>=0 && validBytes%4==0 && validBytes<=data.size)
    val samples=validBytes/2
    require(samples<=output.size)
    for (i in 0 until samples) {
      val value=((data[i*2].toInt() and 255) or (data[i*2+1].toInt() shl 8)).toShort()
      output[i]=value.toFloat()/32768f
    }
    return samples/2
  }
}
