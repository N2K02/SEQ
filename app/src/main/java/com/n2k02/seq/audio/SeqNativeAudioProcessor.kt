package com.n2k02.seq.audio

import android.content.Context
import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.util.UnstableApi
import com.n2k02.seq.model.AudioProfile
import com.n2k02.seq.model.HeadphoneProfileStore
import com.n2k02.seq.model.SettingsStore
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Native DSP in Media3's owned PCM path. Never sees Spotify/other apps or protected streams.
 * Fixed 2048-frame scratch; no allocations inside the normal queueInput loop.
 * Pure mode copies the decoded 16-bit PCM bytes exactly without DSP.
 */
@androidx.annotation.OptIn(UnstableApi::class)
class SeqNativeAudioProcessor(private val context: Context) : BaseAudioProcessor() {
  private var dsp: NativeDsp? = null
  private val samples=FloatArray(4096)

  override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
    return if (inputAudioFormat.encoding==C.ENCODING_PCM_16BIT && inputAudioFormat.channelCount==2)
      inputAudioFormat else AudioProcessor.AudioFormat.NOT_SET
  }
  override fun onFlush() {
    dsp?.close()
    dsp=null
    val settings=SettingsStore(context).read()
    val car=settings.carManual // No unsupported guesses about current car route.
    val corr=if (settings.headphoneCorrection && !car &&
      settings.profile!=AudioProfile.PURE) HeadphoneProfileStore(context).read() else null
    if (settings.profile!=AudioProfile.PURE) {
      dsp=NativeDsp(inputAudioFormat.sampleRate,settings.activeTone(car),true,corr)
    }
    SeqRuntime.log(if(dsp!=null) "Native Media3 PCM DSP active" else "Media3 PCM Pure bypass")
  }
  override fun onReset() {
    dsp?.close()
    dsp=null
  }
  override fun queueInput(inputBuffer: ByteBuffer) {
    val size=inputBuffer.remaining()
    val output=replaceOutputBuffer(size)
    inputBuffer.order(ByteOrder.LITTLE_ENDIAN)
    output.order(ByteOrder.LITTLE_ENDIAN)
    if(dsp==null) {
      output.put(inputBuffer)
      output.flip()
      return
    }
    while(inputBuffer.remaining()>=4) {
      val frames=min(inputBuffer.remaining()/4,samples.size/2)
      for(i in 0 until frames*2) samples[i]=inputBuffer.short.toFloat()/32768f
      dsp?.process(samples,frames)
      for(i in 0 until frames*2) {
        val pcm=(samples[i]*32768f).roundToInt().coerceIn(-32768,32767)
        output.putShort(pcm.toShort())
      }
    }
    output.flip()
  }
}
