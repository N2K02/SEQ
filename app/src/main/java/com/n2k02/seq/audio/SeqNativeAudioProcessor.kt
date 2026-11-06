package com.n2k02.seq.audio

import android.content.Context
import android.os.SystemClock
import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.util.UnstableApi
import com.n2k02.seq.model.*
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Native DSP in SEQ-owned stereo PCM16 only. Never intercepts third-party audio.
 * Uses a preallocated scratch buffer, changes settings only on a revision event
 * and crossfades across 128 frames to avoid parameter-change clicks.
 */
@androidx.annotation.OptIn(UnstableApi::class)
class SeqNativeAudioProcessor(private val context: Context) : BaseAudioProcessor() {
  private var dsp: NativeDsp? = null
  private var previous: NativeDsp? = null
  private val samples=FloatArray(4096)
  private val previousSamples=FloatArray(4096)
  private var seenRevision=Long.MIN_VALUE
  private var lastConfiguredAt=0L
  private var fadeRemaining=0

  override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
    return if (inputAudioFormat.encoding==C.ENCODING_PCM_16BIT && inputAudioFormat.channelCount==2) {
      SeqRuntime.mediaDsp.value="PCM16 stereo • compatible"
      inputAudioFormat
    } else {
      SeqRuntime.mediaDsp.value="Native EQ unavailable for decoded audio format"
      AudioProcessor.AudioFormat.NOT_SET
    }
  }

  private fun reconfigure(crossfade:Boolean) {
    val settings=SettingsStore(context).read()
    val car=SeqRoute.isCar(context,settings)
    val correction=if(settings.headphoneCorrection && !car &&
      settings.profile!=AudioProfile.PURE) HeadphoneProfileStore(context).read() else null
    val impulse=if(settings.convolution && !car && settings.profile!=AudioProfile.PURE)
      MeasuredImpulseStore(context).read()?.takeIf { it.sampleRate==inputAudioFormat.sampleRate }
      else null
    val updated=if(settings.profile==AudioProfile.PURE) null else runCatching {
      NativeDsp(inputAudioFormat.sampleRate,settings.activeTone(car),true,correction,
        impulse,settings.peakGuard,settings.peakCeilingDb.toDouble())
    }.onFailure {
      SeqRuntime.log("Native DSP unavailable: "+it.javaClass.simpleName)
    }.getOrNull()

    previous?.close()
    previous=null
    if(crossfade && (dsp!=null || updated!=null)) {
      previous=dsp
      fadeRemaining=128
    } else {
      dsp?.close()
      fadeRemaining=0
    }
    dsp=updated
    seenRevision=SeqAudioRevision.current()
    lastConfiguredAt=SystemClock.elapsedRealtime()

    SeqRuntime.mediaDsp.value=when {
      updated==null && settings.profile==AudioProfile.PURE -> "Pure bypass"
      updated==null -> "Native DSP unavailable • bypass"
      settings.convolution && impulse==null -> "Native EQ • FIR unavailable for this sample rate"
      settings.convolution && impulse!=null -> "Native EQ • measured FIR active"
      settings.peakGuard -> "Native EQ • peak guard active"
      else -> "Native EQ active"
    }
  }

  override fun onFlush() { reconfigure(crossfade=false) }
  override fun onReset() {
    previous?.close()
    previous=null
    dsp?.close()
    dsp=null
    fadeRemaining=0
    seenRevision=Long.MIN_VALUE
    SeqRuntime.mediaDsp.value="Stopped"
  }

  override fun queueInput(inputBuffer: ByteBuffer) {
    // UI changes are detected without polling SharedPreferences on each audio buffer.
    val revision=SeqAudioRevision.current()
    if(revision!=seenRevision && SystemClock.elapsedRealtime()-lastConfiguredAt>=250L)
      reconfigure(crossfade=true)

    val size=inputBuffer.remaining()
    check(size%4==0) { "Unexpected partial stereo PCM16 frame" }
    val output=replaceOutputBuffer(size)
    inputBuffer.order(ByteOrder.LITTLE_ENDIAN)
    output.order(ByteOrder.LITTLE_ENDIAN)
    if(dsp==null && fadeRemaining==0) {
      output.put(inputBuffer)
      output.flip()
      return
    }

    while(inputBuffer.remaining()>=4) {
      val frames=min(inputBuffer.remaining()/4,samples.size/2)
      for(i in 0 until frames*2)
        samples[i]=inputBuffer.short.toFloat()/32768f

      if(fadeRemaining>0) {
        System.arraycopy(samples,0,previousSamples,0,frames*2)
        previous?.process(previousSamples,frames)
      }
      dsp?.process(samples,frames)
      if(fadeRemaining>0) {
        for(frame in 0 until frames) {
          val proportion=((128 - fadeRemaining + frame).coerceIn(0,128))/128f
          val i=frame*2
          samples[i]=previousSamples[i]*(1f-proportion)+samples[i]*proportion
          samples[i+1]=previousSamples[i+1]*(1f-proportion)+samples[i+1]*proportion
        }
        fadeRemaining=(fadeRemaining-frames).coerceAtLeast(0)
        if(fadeRemaining==0) { previous?.close(); previous=null }
      }

      for(i in 0 until frames*2) {
        val pcm=(samples[i]*32768f).roundToInt().coerceIn(-32768,32767)
        output.putShort(pcm.toShort())
      }
    }
    output.flip()
  }
}
