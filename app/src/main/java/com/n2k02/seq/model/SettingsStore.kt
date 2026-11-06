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
      peakGuard = prefs.getBoolean("peak_guard", true),
      allMedia = prefs.getBoolean("all_media", true),
      loudness = prefs.getBoolean("loudness", true)
    )
  }
  fun write(s: EngineSettings) {
    prefs.edit().putBoolean("enabled", s.enabled).putString("profile", s.profile.name)
      .putBoolean("adaptive", s.adaptive)
      .putBoolean("peak_guard",s.peakGuard)
      .putBoolean("all_media",s.allMedia).putBoolean("loudness",s.loudness).apply()
  }
}
