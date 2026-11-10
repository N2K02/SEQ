package com.n2k02.seq.audio.dsp

import org.junit.Assert.*
import org.junit.Test

class SeqSessionDumpParserTest {
  private val audio = """
    playback activity as reported through PlayerBase:
      ID:31 -- type:android.media.AudioTrack -- u/pid:10187/5612 -- state:started -- attr:AudioAttributes: usage=USAGE_MEDIA content=CONTENT_TYPE_MUSIC flags=0x800 tags= bundle=null -- session:1497 -- mutedBy:
      ID:39 -- type:android.media.AudioTrack -- u/pid:10200/7000 -- state:paused -- attr:AudioAttributes: usage=USAGE_MEDIA content=CONTENT_TYPE_MOVIE flags=0xC00 tags= bundle=null -- session:2001
      ID:7 -- type:android.media.SoundPool -- u/pid:1000/1477 -- state:idle -- attr:AudioAttributes: usage=USAGE_ASSISTANCE_SONIFICATION content=CONTENT_TYPE_SONIFICATION flags=0x800 tags= bundle=null -- session:1185
      ID:40 -- type:android.media.AudioTrack -- u/pid:10187/5612 -- state:stopped -- attr:AudioAttributes: usage=USAGE_MEDIA content=CONTENT_TYPE_MUSIC flags=0x0 tags= bundle=null -- session:1497
    11-10 18:00:00.000 new player piid:31 uid/pid:10187/5612 type:android.media.AudioTrack u/pid:1/2 usage=USAGE_MEDIA session:999
  """.trimIndent()

  @Test fun parsesLivePlayersOnlyAndMergesSessions() {
    val players = SeqSessionDumpParser.parseAudioService(audio)
    assertEquals(listOf(1497, 2001, 1185), players.map { it.sessionId })
    val music = players[0]
    assertEquals(10187, music.uid)
    assertTrue(music.started)
    assertTrue(music.mediaUsage)
    assertFalse(music.captureBlockedByPlayer)
    assertTrue(players[1].captureBlockedByPlayer)
    assertFalse(players[1].started)
    assertFalse(players[2].mediaUsage)
  }

  @Test fun parsesCaptureAllowPolicy() {
    val dump = """
      {uid: 10200, allowPlaybackCapture=false, isSystemApp=false, packageName=com.example.protected}
      {uid: 10187, allowPlaybackCapture=true, isSystemApp=false, packageName=com.example.music}
    """.trimIndent()
    val policy = SeqSessionDumpParser.parseCapturePolicy(dump)
    assertEquals(false, policy["com.example.protected"])
    assertEquals(true, policy["com.example.music"])
  }

  @Test fun malformedDumpsAreIgnored() {
    assertTrue(SeqSessionDumpParser.parseAudioService("").isEmpty())
    assertTrue(SeqSessionDumpParser.parseAudioService("ID:1 -- u/pid:x/y -- usage=USAGE_MEDIA").isEmpty())
    assertTrue(SeqSessionDumpParser.parseCapturePolicy("nothing").isEmpty())
  }
}
