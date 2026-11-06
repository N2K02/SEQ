package com.n2k02.seq.audio

import android.app.*
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFocusRequest
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.net.Uri
import android.os.IBinder
import android.os.Build
import com.n2k02.seq.MainActivity
import com.n2k02.seq.model.AudioProfile
import com.n2k02.seq.model.SettingsStore
import com.n2k02.seq.model.HeadphoneProfileStore
import com.n2k02.seq.model.MeasuredImpulseStore
import kotlinx.coroutines.*
import java.io.InputStream

/**
 * Actual NativeDsp usage: bounded, owned PCM16 stereo WAV playback.
 * MediaSession provides ordinary media controls but is NOT a car browsing service.
 */
class SeqReferencePlayerService : Service() {
  companion object {
    const val PLAY="com.n2k02.seq.PLAYER_PLAY"
    const val STOP="com.n2k02.seq.PLAYER_STOP"
    private const val CHANNEL="seq_player"
    fun intent(context: Context, action: String)=
      Intent(context,SeqReferencePlayerService::class.java).setAction(action)
  }
  private val scope=CoroutineScope(SupervisorJob()+Dispatchers.IO)
  private var work: Job?=null
  private lateinit var session: MediaSession
  private lateinit var audio: AudioManager
  private var focus: AudioFocusRequest?=null

  override fun onCreate() {
    super.onCreate()
    audio=getSystemService(AUDIO_SERVICE) as AudioManager
    (getSystemService(NOTIFICATION_SERVICE) as NotificationManager)
      .createNotificationChannel(NotificationChannel(CHANNEL,"SEQ reference playback",NotificationManager.IMPORTANCE_LOW))
    session=MediaSession(this,"SEQ Reference Player")
    session.setCallback(object: MediaSession.Callback() {
      override fun onStop() { stopSelf() }
      override fun onPause() { stopSelf() }
      override fun onPlayFromSearch(query: String?, extras: android.os.Bundle?) {
        SeqRuntime.log("Reference player voice search unsupported")
      }
    })
    session.isActive=true
  }
  override fun onStartCommand(intent: Intent?,flags: Int,startId: Int): Int {
    if (intent?.action==STOP) { stopSelf(); return START_NOT_STICKY }
    val uri=intent?.data
    if (intent?.action!=PLAY || uri==null) { stopSelf(); return START_NOT_STICKY }
    startForeground(1002,notification())
    work?.cancel()
    work=scope.launch {
      try { play(uri) }
      catch (e: CancellationException) { SeqRuntime.player.value="Stopped" }
      catch (e: Exception) {
        SeqRuntime.player.value="Playback unavailable: "+(e.message ?: e.javaClass.simpleName).take(140)
        SeqRuntime.log("Native playback failed: "+e.javaClass.simpleName)
      } finally { stopSelf(startId) }
    }
    return START_NOT_STICKY
  }
  private fun notification(): Notification {
    val stop=PendingIntent.getService(this,2,intent(this,STOP),
      PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    val open=PendingIntent.getActivity(this,2,Intent(this,MainActivity::class.java),
      PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    return Notification.Builder(this,CHANNEL)
      .setSmallIcon(android.R.drawable.ic_media_play)
      .setContentTitle("SEQ Reference Player").setContentText("Native reference DSP • local WAV")
      .setContentIntent(open).setOngoing(true)
      .addAction(Notification.Action.Builder(android.R.drawable.ic_media_pause,"Stop",stop).build())
      .setStyle(Notification.MediaStyle().setMediaSession(session.sessionToken))
      .build()
  }
  private fun readChunk(stream: InputStream,bytes: ByteArray,limit: Int): Int {
    var count=0
    while(count<limit) {
      val got=stream.read(bytes,count,limit-count)
      if(got<=0) break
      count+=got
    }
    return count
  }
  private fun carProfile(): Boolean = SeqRoute.isCar(this,SettingsStore(this).read())
  private suspend fun play(uri: Uri) {
    val attributes=AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
      .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build()
    val req=AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
      .setAudioAttributes(attributes).setOnAudioFocusChangeListener {
        if(it==AudioManager.AUDIOFOCUS_LOSS) stopSelf()
      }.build()
    require(audio.requestAudioFocus(req)==AudioManager.AUDIOFOCUS_REQUEST_GRANTED) { "Audio focus denied" }
    focus=req
    contentResolver.openInputStream(uri)?.use { stream ->
      val fmt=WaveReader.parse(stream)
      val setting=SettingsStore(this).read()
      if(setting.usbDirect) {
        require(setting.profile==AudioProfile.PURE) { "USB direct requires Pure profile" }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
          throw UnsupportedOperationException("USB Direct requires Android 14+")
        }
        val playbackJob=currentCoroutineContext()[Job]
        UsbDirectOutput(audio,attributes).play(stream,fmt) { playbackJob?.isActive==true }
        return
      }
      val tone=setting.activeTone(carProfile())
      val min=AudioTrack.getMinBufferSize(fmt.sampleRate,AudioFormat.CHANNEL_OUT_STEREO,AudioFormat.ENCODING_PCM_FLOAT)
      require(min>0) { "Output sample rate unsupported" }
      val format=AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
        .setSampleRate(fmt.sampleRate).setChannelMask(AudioFormat.CHANNEL_OUT_STEREO).build()
      val track=AudioTrack.Builder().setAudioAttributes(attributes).setAudioFormat(format)
        .setBufferSizeInBytes(maxOf(min*2,32768))
        .setTransferMode(AudioTrack.MODE_STREAM).build()
      try {
        val correction = if (setting.headphoneCorrection && !carProfile() &&
            setting.profile != AudioProfile.PURE) HeadphoneProfileStore(this).read() else null
        val impulse=if(setting.convolution && !carProfile() &&
          setting.profile!=AudioProfile.PURE)
          MeasuredImpulseStore(this).read()?.takeIf { it.sampleRate==fmt.sampleRate } else null
        NativeDsp(fmt.sampleRate,tone,setting.profile!=AudioProfile.PURE,correction,impulse,
          setting.peakGuard && setting.profile!=AudioProfile.PURE,
          setting.peakCeilingDb.toDouble()).use { dsp ->
          val bytes=ByteArray(8192)
          val pcm=FloatArray(4096)
          var remaining=fmt.dataBytes
          session.setPlaybackState(PlaybackState.Builder()
            .setActions(PlaybackState.ACTION_STOP or PlaybackState.ACTION_PAUSE)
            .setState(PlaybackState.STATE_PLAYING,0,1f).build())
          SeqRuntime.player.value="Playing "+fmt.sampleRate+" Hz • "+setting.profile.title
          track.play()
          // Own native DSP already processes this PCM: suspend the all-media effect.
          if(SeqOwnedAudioSessions.setPlaying("reference",true)) SeqGlobalEffect.sync(this@SeqReferencePlayerService)
          while (remaining>0 && currentCoroutineContext().isActive) {
            val limit=minOf(bytes.size.toLong(),remaining).toInt()
            val read=readChunk(stream,bytes,limit)
            require(read>0 && read%4==0) { "Truncated PCM sample data" }
            remaining-=read
            val frames=WaveReader.toFloats(bytes,read,pcm)
            dsp.process(pcm,frames)
            val length=frames*2
            var written=0
            while(written<length && currentCoroutineContext().isActive) {
              val n=track.write(pcm,written,length-written,AudioTrack.WRITE_BLOCKING)
              require(n>0) { "AudioTrack write error "+n }
              written+=n
            }
          }
          if(currentCoroutineContext().isActive) SeqRuntime.player.value="Playback complete"
        }
      } finally {
        runCatching { track.pause(); track.flush(); track.release() }
        if(SeqOwnedAudioSessions.setPlaying("reference",false)) SeqGlobalEffect.sync(this@SeqReferencePlayerService)
      }
    } ?: throw IllegalArgumentException("Cannot open file")
  }
  override fun onDestroy() {
    work?.cancel()
    scope.cancel()
    focus?.let { runCatching { audio.abandonAudioFocusRequest(it) } }
    session.setPlaybackState(PlaybackState.Builder().setState(PlaybackState.STATE_STOPPED,0,0f).build())
    session.release()
    SeqRuntime.player.value="Stopped"
    super.onDestroy()
  }
  override fun onBind(intent: Intent?): IBinder?=null
}
