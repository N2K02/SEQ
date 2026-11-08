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
      manualBands = SystemEqCurveBuilder.parseManual(prefs.getString("manual_bands", null)),
      bassBoost = readLevel("bass_boost", 0f, 0f..1f),
      preampDb = readLevel("preamp_db", 0f, -12f..6f),
      soundMode = runCatching { SoundMode.valueOf(prefs.getString("sound_mode", SoundMode.NATURAL.name) ?: "") }.getOrDefault(SoundMode.NATURAL),
      dynamicBass = prefs.getBoolean("dynamic_bass", false),
      bassIntensity = readLevel("bass_intensity", 0.35f, 0f..1f)
    )
  }
  fun write(s: EngineSettings) {
    prefs.edit().putBoolean("enabled", s.enabled).putString("profile", s.profile.name)
      .putBoolean("adaptive", s.adaptive)
      .putBoolean("peak_guard",s.peakGuard)
      .putString("manual_bands", s.manualBands?.joinToString(","))
      .putFloat("bass_boost", s.bassBoost).putFloat("preamp_db", s.preampDb)
      .putString("sound_mode", s.soundMode.name)
      .putBoolean("dynamic_bass", s.dynamicBass).putFloat("bass_intensity", s.bassIntensity)
      .putBoolean("all_media",s.allMedia).putBoolean("loudness",s.loudness).apply()
  }
  private fun readLevel(key: String, default: Float, range: ClosedFloatingPointRange<Float>) =
    prefs.getFloat(key, default).takeIf { it in range } ?: default

  fun routeBands(route: RouteKind): List<Float>? =
    SystemEqCurveBuilder.parseManual(prefs.getString("route_eq_${route.name}", null))

  fun saveRouteBands(route: RouteKind, bands: List<Float>?) {
    prefs.edit().putString("route_eq_${route.name}", bands?.joinToString(",")).apply()
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
