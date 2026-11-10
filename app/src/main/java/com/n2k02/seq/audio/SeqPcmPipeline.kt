package com.n2k02.seq.audio

/**
 * A bounded single-producer / single-consumer stereo PCM bridge, modeled after
 * RootlessBrennanDSP's low-allocation capture-to-render transport.
 *
 * This library component DOES NOT acquire or emit third-party audio. Only an
 * explicitly owned/exclusive PCM source may be connected to a render sink.
 * Underflow returns silence rather than repeating old samples.
 */
class SeqPcmRingBuffer(
  val capacityFrames: Int = 8192
) {
  init {
    require(capacityFrames in 256..65536 && capacityFrames and (capacityFrames - 1) == 0)
  }
  private val samples = FloatArray(capacityFrames * 2)
  private val mask = capacityFrames - 1
  @Volatile private var readFrame = 0L
  @Volatile private var writeFrame = 0L
  @Volatile private var stopped = false

  /** Producer thread only. Never overwrites frames not yet consumed. */
  fun offer(source: FloatArray, offset: Int = 0, frames: Int = (source.size - offset) / 2): Int {
    validate(source, offset, frames)
    if (stopped || frames == 0) return 0
    val used = (writeFrame - readFrame).coerceIn(0L, capacityFrames.toLong()).toInt()
    val available = capacityFrames - used
    val n = minOf(frames, available)
    for (i in 0 until n) {
      val position = ((writeFrame + i).toInt() and mask) * 2
      val input = offset + i * 2
      samples[position] = source[input]
      samples[position + 1] = source[input + 1]
    }
    writeFrame += n
    return n
  }

  /** Consumer thread only. Zeros unavailable frames to avoid stale or repeated audio. */
  fun poll(destination: FloatArray, offset: Int = 0, frames: Int = (destination.size - offset) / 2): Int {
    validate(destination, offset, frames)
    if (frames == 0) return 0
    if (stopped) {
      destination.fill(0f, offset, offset + frames * 2)
      return 0
    }
    val available = (writeFrame - readFrame).coerceIn(0L, capacityFrames.toLong()).toInt()
    val n = minOf(available, frames)
    for (i in 0 until n) {
      val position = ((readFrame + i).toInt() and mask) * 2
      val output = offset + i * 2
      destination[output] = samples[position]
      destination[output + 1] = samples[position + 1]
    }
    if (n < frames) destination.fill(0f, offset + n * 2, offset + frames * 2)
    readFrame += n
    return n
  }

  fun availableFrames(): Int = (writeFrame - readFrame).coerceIn(0L, capacityFrames.toLong()).toInt()

  /** Stop and mute on route loss, explicit quit, or policy revocation. */
  fun stop() {
    stopped = true
    readFrame = writeFrame
  }

  private fun validate(buffer: FloatArray, offset: Int, frames: Int) {
    require(offset >= 0 && offset % 2 == 0 && frames >= 0 && frames <= buffer.size / 2 &&
      offset.toLong() + frames.toLong() * 2 <= buffer.size.toLong())
  }
}

/** A pure gate that prevents any experimental PCM replay until all prerequisites
 * are positively established. ADB connection, DUMP and remote-submix strings
 * alone MUST NOT be treated as exclusive playback ownership.
 */
data class SeqExclusiveRouteEvidence(
  val userOptIn: Boolean = false,
  val captureConsent: Boolean = false,
  val producerPcmVerified: Boolean = false,
  val exclusiveAudioPolicyRegistered: Boolean = false,
  val originalPlaybackSuppressed: Boolean = false,
  val outputRouteVerified: Boolean = false,
  val rollbackRegistered: Boolean = false
) {
  val mayReplay: Boolean get() = userOptIn && captureConsent && producerPcmVerified &&
    exclusiveAudioPolicyRegistered && originalPlaybackSuppressed &&
    outputRouteVerified && rollbackRegistered
  fun missing(): List<String> = buildList {
    if (!userOptIn) add("User opt-in")
    if (!captureConsent) add("Capture consent")
    if (!producerPcmVerified) add("Verified PCM source")
    if (!exclusiveAudioPolicyRegistered) add("Exclusive AudioPolicy")
    if (!originalPlaybackSuppressed) add("Original output suppressed")
    if (!outputRouteVerified) add("Output route verification")
    if (!rollbackRegistered) add("Safe rollback")
  }
}
