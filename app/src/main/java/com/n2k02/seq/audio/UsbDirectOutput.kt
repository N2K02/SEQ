package com.n2k02.seq.audio

import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioMixerAttributes
import android.media.AudioTrack
import android.os.Build
import androidx.annotation.RequiresApi
import java.io.InputStream

/** No PCM transformation. Android 14+ optional, HAL-dependent USB mixer mode. */
@RequiresApi(34)
class UsbDirectOutput(private val audio:AudioManager,private val attributes:AudioAttributes) {
  companion object {
    fun canSelect(format:AudioFormat,mixer:AudioMixerAttributes):Boolean =
      mixer.mixerBehavior==AudioMixerAttributes.MIXER_BEHAVIOR_BIT_PERFECT &&
      mixer.format.encoding==format.encoding &&
      mixer.format.sampleRate==format.sampleRate &&
      mixer.format.channelMask==format.channelMask
  }
  fun play(stream:InputStream,fmt:WaveInfo,continuePlayback:()->Boolean) {
    require(Build.VERSION.SDK_INT>=34) { "USB bit-perfect requires Android 14+" }
    val format=AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT)
      .setSampleRate(fmt.sampleRate).setChannelMask(AudioFormat.CHANNEL_OUT_STEREO).build()
    val candidates=audio.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
      .filter { it.type==AudioDeviceInfo.TYPE_USB_DEVICE || it.type==AudioDeviceInfo.TYPE_USB_HEADSET }
    val matched=candidates.firstNotNullOfOrNull { device ->
      audio.getSupportedMixerAttributes(device).firstOrNull { canSelect(format,it) }?.let { device to it }
    } ?: throw IllegalStateException("No PCM16 stereo bit-perfect USB DAC mode for this sample rate")
    val (device,mixer)=matched
    check(audio.setPreferredMixerAttributes(attributes,device,mixer)) {
      "Android denied preferred USB mixer attributes"
    }
    try {
      val min=AudioTrack.getMinBufferSize(fmt.sampleRate,AudioFormat.CHANNEL_OUT_STEREO,
        AudioFormat.ENCODING_PCM_16BIT)
      require(min>0) { "Unsupported direct USB format" }
      val track=AudioTrack.Builder().setAudioAttributes(attributes).setAudioFormat(format)
        .setBufferSizeInBytes(maxOf(min*2,16384)).setTransferMode(AudioTrack.MODE_STREAM).build()
      try {
        check(track.setPreferredDevice(device)) { "Cannot route directly to selected USB DAC" }
        track.play()
        check(track.routedDevice?.id==device.id) { "USB output route was not verified" }
        SeqRuntime.player.value="USB direct PCM16 • "+fmt.sampleRate+" Hz • no SEQ processing"
        val bytes=ByteArray(8192)
        var left=fmt.dataBytes
        while(left>0 && continuePlayback()) {
          val max=minOf(bytes.size.toLong(),left).toInt()
          var read=0
          while(read<max) {
            val n=stream.read(bytes,read,max-read)
            require(n>0) { "Truncated WAV data" }
            read+=n
          }
          left-=read
          var written=0
          while(written<read && continuePlayback()) {
            val n=track.write(bytes,written,read-written,AudioTrack.WRITE_BLOCKING)
            require(n>0) { "USB direct write failed" }
            written+=n
          }
          if(continuePlayback()) check(track.routedDevice?.id==device.id) { "USB DAC disconnected or routing changed" }
        }
      } finally { runCatching{track.pause();track.flush();track.release()} }
    } finally {
      audio.clearPreferredMixerAttributes(attributes,device)
    }
  }
}
