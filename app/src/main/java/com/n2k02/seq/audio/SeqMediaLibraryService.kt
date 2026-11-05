package com.n2k02.seq.audio

import android.content.Intent
import android.net.Uri
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.session.LibraryResult
import androidx.media3.session.MediaLibraryService
import androidx.media3.session.MediaSession
import com.google.common.collect.ImmutableList
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture

/**
 * Real Android Auto media browsable library of user-selected local audio files.
 * Audio is processed by SEQ's native DSP only when decoded stereo PCM16 is supported.
 * Not a universal audio intercept. Android Auto approval and real-car testing remain required.
 */
@androidx.annotation.OptIn(UnstableApi::class)
class SeqMediaLibraryService : MediaLibraryService() {
  companion object {
    const val PLAY = "com.n2k02.seq.MEDIA_PLAY"
    const val PAUSE = "com.n2k02.seq.MEDIA_PAUSE"
    const val RESUME = "com.n2k02.seq.MEDIA_RESUME"
    const val STOP = "com.n2k02.seq.MEDIA_STOP"
    private const val ROOT = "seq_library"
    fun intent(context:android.content.Context,action:String): Intent =
      Intent(context,SeqMediaLibraryService::class.java).setAction(action)
  }
  private lateinit var player: ExoPlayer
  private lateinit var session: MediaLibrarySession
  private val catalog get() = LocalMusicLibrary(this)

  private fun trackItem(track:LocalTrack):MediaItem =
    MediaItem.Builder().setMediaId(track.mediaId).setUri(track.uri)
      .setMediaMetadata(MediaMetadata.Builder()
        .setTitle(track.title).setIsPlayable(true).setIsBrowsable(false).build()).build()

  private fun rootItem():MediaItem =
    MediaItem.Builder().setMediaId(ROOT)
      .setMediaMetadata(MediaMetadata.Builder()
        .setTitle("SEQ Local Music").setIsBrowsable(true).setIsPlayable(false).build()).build()

  private val callback=object:MediaLibrarySession.Callback {
    override fun onGetLibraryRoot(
      session: MediaLibrarySession,
      browser: MediaSession.ControllerInfo,
      params: LibraryParams?
    ):ListenableFuture<LibraryResult<MediaItem>> =
      Futures.immediateFuture(LibraryResult.ofItem(rootItem(),params))

    override fun onGetChildren(
      session: MediaLibrarySession,
      browser: MediaSession.ControllerInfo,
      parentId:String,
      page:Int,
      pageSize:Int,
      params:LibraryParams?
    ):ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> {
      if(parentId!=ROOT) return Futures.immediateFuture(LibraryResult.ofError(LibraryResult.RESULT_ERROR_BAD_VALUE))
      val list=catalog.items().map(::trackItem)
      val start=(page.toLong()*pageSize).coerceIn(0L,list.size.toLong()).toInt()
      val pageItems=list.drop(start).take(pageSize.coerceIn(1,100))
      return Futures.immediateFuture(LibraryResult.ofItemList(pageItems,params))
    }

    override fun onGetItem(
      session:MediaLibrarySession,browser:MediaSession.ControllerInfo,mediaId:String
    ):ListenableFuture<LibraryResult<MediaItem>> {
      val item=if(mediaId==ROOT) rootItem()
        else catalog.items().firstOrNull { it.mediaId==mediaId }?.let(::trackItem)
      return Futures.immediateFuture(
        if(item!=null) LibraryResult.ofItem(item,null)
        else LibraryResult.ofError(LibraryResult.RESULT_ERROR_BAD_VALUE)
      )
    }

    override fun onAddMediaItems(
      mediaSession:MediaSession,controller:MediaSession.ControllerInfo,mediaItems:List<MediaItem>
    ):ListenableFuture<List<MediaItem>> {
      val allowed=catalog.items().associateBy { it.mediaId }
      return Futures.immediateFuture(mediaItems.mapNotNull { item ->
        allowed[item.mediaId]?.let(::trackItem)
      })
    }
  }

  override fun onCreate() {
    super.onCreate()
    val renderers=object:DefaultRenderersFactory(this) {
      override fun buildAudioSink(context:android.content.Context,
        enableFloatOutput:Boolean,enableAudioTrackPlaybackParams:Boolean):AudioSink {
        return DefaultAudioSink.Builder(context)
          .setAudioProcessors(arrayOf(SeqNativeAudioProcessor(context))).build()
      }
    }
    player=ExoPlayer.Builder(this,renderers).build()
    player.setAudioAttributes(AudioAttributes.Builder()
      .setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MUSIC).build(),true)
    session=MediaLibrarySession.Builder(this,player,callback).build()
    SeqRuntime.media.value="Media library ready"
  }
  override fun onGetSession(controllerInfo:MediaSession.ControllerInfo):MediaLibrarySession=session

  override fun onStartCommand(intent:Intent?,flags:Int,startId:Int):Int {
    when(intent?.action) {
      PLAY -> {
        val uri=intent.data
        val files=catalog.items()
        val index=files.indexOfFirst { it.uri==uri }
        if(index>=0) {
          val list=files.map(::trackItem)
          player.setMediaItems(list,index,0L)
          player.prepare()
          player.play()
          SeqRuntime.media.value="Playing "+files[index].title
        }
      }
      PAUSE -> { player.pause(); SeqRuntime.media.value="Paused" }
      RESUME -> { player.play(); SeqRuntime.media.value="Playing" }
      STOP -> {
        player.stop()
        SeqRuntime.media.value="Stopped"
        stopSelf()
      }
    }
    return super.onStartCommand(intent,flags,startId)
  }
  override fun onDestroy() {
    session.release()
    player.release()
    SeqRuntime.media.value="Stopped"
    super.onDestroy()
  }
}
