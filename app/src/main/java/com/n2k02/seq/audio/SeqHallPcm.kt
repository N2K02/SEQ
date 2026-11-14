package com.n2k02.seq.audio

/** Filtered stereo room. Dry audio remains intact; the chain's final limiter owns peak control. */
class SeqHallPcm(sampleRate: Int) {
  private val room = MusicRoom(sampleRate.also { require(it in 8000..192000) })
  var wet = 0.1f
    set(value) { field = value.takeIf { it.isFinite() }?.coerceIn(0f, 0.15f) ?: 0f }
  var decay = 0.7f
    set(value) { field = value.takeIf { it.isFinite() }?.coerceIn(0f, 0.75f) ?: 0.7f }
  fun reset() = room.reset()
  fun process(stereo: FloatArray, offset: Int = 0, frames: Int = (stereo.size-offset)/2) {
    require(offset >= 0 && offset % 2 == 0 && frames >= 0 && offset.toLong()+frames.toLong()*2 <= stereo.size)
    if (wet == 0f) return
    room.wet = wet; room.feedback = decay
    for (i in 0 until frames) {
      val p = offset+i*2
      room.process(stereo[p], stereo[p+1])
      stereo[p] = room.outL; stereo[p+1] = room.outR
    }
  }
}
