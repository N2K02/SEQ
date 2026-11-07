package com.n2k02.seq.audio

import android.media.AudioAttributes
import org.junit.Assert.*
import org.junit.Test

class PlaybackPolicyTest {
  @Test fun musicVideoAndGamesCountAsMedia() {
    assertTrue(PlaybackPolicy.mediaActive(listOf(AudioAttributes.USAGE_MEDIA)))
    assertTrue(PlaybackPolicy.mediaActive(listOf(AudioAttributes.USAGE_GAME)))
    assertTrue(PlaybackPolicy.mediaActive(listOf(AudioAttributes.USAGE_UNKNOWN)))
  }
  @Test fun callsNavigationAndAlertsDoNotTriggerTheEngine() {
    assertFalse(PlaybackPolicy.mediaActive(emptyList()))
    assertFalse(PlaybackPolicy.mediaActive(listOf(
      AudioAttributes.USAGE_VOICE_COMMUNICATION,
      AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE,
      AudioAttributes.USAGE_NOTIFICATION, AudioAttributes.USAGE_ALARM)))
  }
  @Test fun navigationOverMusicStillCountsAsMedia() {
    assertTrue(PlaybackPolicy.mediaActive(listOf(
      AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE, AudioAttributes.USAGE_MEDIA)))
  }
}
