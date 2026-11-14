package com.n2k02.seq.audio

import org.junit.Assert.*
import org.junit.Test
import java.io.File

class SeqPcmCaptureProbeContractTest {
  private fun source(relativePath: String): String =
    generateSequence(File(System.getProperty("user.dir")).canonicalFile) { it.parentFile }
      .first { it.resolve(relativePath).isFile }.resolve(relativePath).readText()

  @Test fun captureRequiresFreshUserConsentAndMediaProjectionForegroundService() {
    val probe = source("app/src/main/java/com/n2k02/seq/audio/SeqPcmCaptureProbeService.kt")
    val ui = source("app/src/main/java/com/n2k02/seq/SeqAdbDiagnosticsScreen.kt")
    val manifest = source("app/src/main/AndroidManifest.xml")
    assertTrue(probe.contains("getMediaProjection(Activity.RESULT_OK, result)"))
    assertTrue(probe.contains("AudioPlaybackCaptureConfiguration.Builder(ownedProjection)"))
    assertTrue(probe.contains("registerCallback(it, Handler(Looper.getMainLooper()))"))
    assertTrue(probe.contains("FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION"))
    assertTrue(ui.contains("AudioResultContractsNeverUsed").not())
    assertTrue(ui.contains("ActivityResultContracts.StartActivityForResult()"))
    assertTrue(ui.contains("ActivityResultContracts.RequestPermission()"))
    assertTrue(ui.contains("createScreenCaptureIntent()"))
    assertTrue(manifest.contains("android.permission.FOREGROUND_SERVICE_MEDIA_PROJECTION"))
    assertTrue(manifest.contains("android:foregroundServiceType=\"mediaProjection\""))
  }

  @Test fun readOnlyProbeMustNotReplayOrWriteCapturedSamples() {
    val service = source("app/src/main/java/com/n2k02/seq/audio/SeqPcmCaptureProbeService.kt")
    assertFalse(service.contains("AudioTrack.Builder"))
    assertFalse(service.contains("AudioTrack("))
    assertFalse(service.contains("FileOutputStream"))
    assertFalse(service.contains("setAllowedCapturePolicy"))
    assertFalse(service.contains("setAudioAttributes(AudioAttributes.USAGE_VOICE_COMMUNICATION"))
    assertTrue(service.contains("record.read(samples"))
    assertTrue(service.contains("original playback NOT suppressed"))
    assertTrue(service.contains("stopSelf()"))
    assertTrue(service.contains("recorder?.release()"))
    val main = source("app/src/main/java/com/n2k02/seq/MainActivity.kt")
    assertTrue(main.contains("SeqPcmCaptureProbeService::class.java"))
  }
}
