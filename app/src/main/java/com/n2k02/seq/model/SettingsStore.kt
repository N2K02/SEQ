package com.n2k02.seq.model

import android.content.Context
import org.json.JSONObject

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
      loudness = prefs.getBoolean("loudness", true),
      manualBands = SystemEqCurveBuilder.parseManual(prefs.getString("manual_bands", null))
    )
  }
  fun write(s: EngineSettings) {
    prefs.edit().putBoolean("enabled", s.enabled).putString("profile", s.profile.name)
      .putBoolean("adaptive", s.adaptive)
      .putBoolean("peak_guard",s.peakGuard)
      .putString("manual_bands", s.manualBands?.joinToString(","))
      .putBoolean("all_media",s.allMedia).putBoolean("loudness",s.loudness).apply()
  }
  fun presets(): Map<String, List<Float>> {
    val json = runCatching { JSONObject(prefs.getString("eq_presets", "{}") ?: "{}") }
      .getOrElse { JSONObject() }
    return json.keys().asSequence().sorted().mapNotNull { name ->
      SystemEqCurveBuilder.parseManual(json.optString(name))?.let { name to it }
    }.toMap()
  }

  fun savePreset(name: String, bands: List<Float>) {
    val presets = presets() + (name to bands)
    val json = JSONObject(presets.mapValues { (_, gains) -> gains.joinToString(",") })
    prefs.edit().putString("eq_presets", json.toString()).apply()
  }
}
