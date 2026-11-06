package com.n2k02.seq.model

import android.content.Context

class SettingsStore(context: Context) {
  private val prefs = context.applicationContext.getSharedPreferences("seq_settings", Context.MODE_PRIVATE)
  fun read(): EngineSettings {
    // Older Reference/Refined choices migrate to the automatic engine.
    val profile = if (prefs.getString("profile", "") == AudioProfile.PURE.name) AudioProfile.PURE
      else AudioProfile.AUTO
    return EngineSettings(
      enabled = prefs.getBoolean("enabled", false),
      profile = profile,
      adaptive = prefs.getBoolean("adaptive", true),
      headphoneCorrection = prefs.getBoolean("headphone_correction", false),
      convolution = prefs.getBoolean("convolution", false),
      peakGuard = prefs.getBoolean("peak_guard", true),
      peakCeilingDb = prefs.getFloat("peak_ceiling", -1.0f).coerceIn(-12f, -0.1f),
      usbDirect = prefs.getBoolean("usb_direct", false),
      allMedia = prefs.getBoolean("all_media", true),
      loudness = prefs.getBoolean("loudness", true)
    )
  }
  fun write(s: EngineSettings) {
    prefs.edit().putBoolean("enabled", s.enabled).putString("profile", s.profile.name)
      .putBoolean("adaptive", s.adaptive)
      .putBoolean("headphone_correction", s.headphoneCorrection)
      .putBoolean("convolution",s.convolution).putBoolean("peak_guard",s.peakGuard)
      .putFloat("peak_ceiling",s.peakCeilingDb.coerceIn(-12f,-0.1f))
      .putBoolean("usb_direct",s.usbDirect)
      .putBoolean("all_media",s.allMedia).putBoolean("loudness",s.loudness).apply()
    SeqAudioRevision.changed()
  }
}
