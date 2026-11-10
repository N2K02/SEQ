package com.n2k02.seq.audio

import com.n2k02.seq.model.SpatialMode

/** Owns the DSP state and reusable buffer for an EXCLUSIVE PCM source.
 * No recorder, audio sink, projection, or system-policy registration is created.
 * Nothing can process live third-party playback without independent evidence
 * that the original audio has been exclusively rerouted and can be restored.
 */
class SeqExclusivePcmProcessor(
  sampleRate: Int,
  private val mode: SpatialMode,
  capacityFrames: Int = 4096
) {
  private val queue = SeqPcmRingBuffer(capacityFrames)
  private val hall = SeqHallPcm(sampleRate)
  private val orbit = SeqOrbitPcm(sampleRate)
  private val render = FloatArray(capacityFrames * 2)
  @Volatile private var closed = false
  private var valid = false

  fun submitOwnedPcm(
    samples: FloatArray,
    evidence: SeqExclusiveRouteEvidence,
    frames: Int = samples.size / 2
  ): Int {
    if (closed || !evidence.mayReplay || frames <= 0 || samples.size != frames * 2) return 0
    valid = true
    return queue.offer(samples, 0, frames)
  }

  /** Consumer thread: returns the number of safe processed frames, never the
   * original stream and never more than the queued amount. If validation has
   * been revoked, any queued PCM is discarded by close().
   */
  fun processInto(
    destination: FloatArray,
    evidence: SeqExclusiveRouteEvidence,
    frames: Int = destination.size / 2
  ): Int {
    if (closed || !valid || !evidence.mayReplay) {
      destination.fill(0f)
      close()
      return 0
    }
    require(frames >= 0 && frames <= render.size / 2 && destination.size == frames * 2)
    val count = queue.poll(render, 0, frames)
    if (count <= 0) {
      destination.fill(0f)
      return 0
    }
    when (mode) {
      SpatialMode.HALL -> hall.process(render, 0, count)
      SpatialMode.ORBIT_8D -> orbit.process(render, 0, count)
      else -> { /* Unsupported mode: passthrough only for exclusive owned PCM */ }
    }
    render.copyInto(destination, 0, 0, count * 2)
    if (count < frames) destination.fill(0f, count * 2, frames * 2)
    return count
  }

  fun close() {
    if (closed) return
    closed = true
    valid = false
    queue.stop()
    hall.reset()
    orbit.reset()
    render.fill(0f)
  }
}
