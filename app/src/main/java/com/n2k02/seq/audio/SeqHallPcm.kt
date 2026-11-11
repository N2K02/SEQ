package com.n2k02.seq.audio

/**
 * Stereo concert-hall prototype for audio SEQ already owns, not for captured
 * third-party media. Four independent feedback delays create a diffuse tail.
 * Input and output are bounded float PCM. PCM owners must call reset on track
 * changes; this class never plays, captures or routes sound.
 */
class SeqHallPcm(sampleRate: Int) {
  private val rate = sampleRate.also { require(it in 8000..192000) }
  private val lengths = intArrayOf(29, 37, 43, 53).map { (it * rate / 1000).coerceAtLeast(1) }
  private val buffers = lengths.map { FloatArray(it) }
  private val heads = IntArray(4)
  var wet = 0.2f
    set(value) { field = value.coerceIn(0f, 0.35f) }
  var decay = 0.65f
    set(value) { field = value.coerceIn(0f, 0.78f) }

  fun reset() {
    buffers.forEach { it.fill(0f) }
    heads.fill(0)
  }

  fun process(stereo: FloatArray, offset: Int = 0, frames: Int = (stereo.size - offset) / 2) {
    require(offset >= 0 && offset % 2 == 0 && frames >= 0 && offset + frames * 2 <= stereo.size)
    for (i in 0 until frames) {
      val p = offset + i * 2
      val l = stereo[p].coerceIn(-1f, 1f)
      val r = stereo[p + 1].coerceIn(-1f, 1f)
      val feed = (l + r) * 0.5f
      var tailL = 0f
      var tailR = 0f
      for (k in 0..3) {
        val line = buffers[k]
        val pos = heads[k]
        val tap = line[pos]
        line[pos] = (feed * 0.18f + tap * decay).coerceIn(-1f, 1f)
        heads[k] = if (pos + 1 == line.size) 0 else pos + 1
        if (k % 2 == 0) tailL += tap else tailR += tap
      }
      val mix = wet
      stereo[p] = (l * (1f - mix) + tailL * mix * 0.5f).coerceIn(-1f, 1f)
      stereo[p + 1] = (r * (1f - mix) + tailR * mix * 0.5f).coerceIn(-1f, 1f)
    }
  }
}
