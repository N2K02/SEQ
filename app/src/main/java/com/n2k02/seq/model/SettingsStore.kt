package com.n2k02.seq.model

import android.content.Context
import org.json.JSONObject

class SettingsStore(context: Context) {
  private val prefs = context.applicationContext.getSharedPreferences("seq_settings", Context.MODE_PRIVATE)
  fun read(): EngineSettings {
    // This simple equalizer has one processing path. Migrate older Pure/per-session
    // preferences to automatic all-media mode instead of preserving hidden options.
    val profile = AudioProfile.AUTO
    return EngineSettings(
      enabled = prefs.getBoolean("enabled", false),
      profile = profile,
      adaptive = prefs.getBoolean("adaptive", true),
      peakGuard = prefs.getBoolean("peak_guard", true),
      allMedia = true,
      loudness = prefs.getBoolean("loudness", true),
      manualBands = SystemEqCurveBuilder.parseManual(prefs.getString("manual_bands", null)),
      bassBoost = readLevel("bass_boost", 0f, 0f..1f),
      spatialMode = runCatching { SpatialMode.valueOf(prefs.getString("spatial_mode", "OFF") ?: "OFF") }.getOrDefault(SpatialMode.OFF),
      spatialIntensity = readLevel("spatial_intensity", 0.6f, 0f..1f),
      preampDb = readLevel("preamp_db", 0f, -12f..6f),
      soundMode = runCatching { SoundMode.valueOf(prefs.getString("sound_mode", SoundMode.NATURAL.name) ?: "") }.getOrDefault(SoundMode.NATURAL),
      compressorEnabled = prefs.getBoolean("compressor_enabled", false),
      compressorRatio = readLevel("compressor_ratio", 1.5f, 1f..4f),
      compressorThresholdDb = readLevel("compressor_threshold", -18f, -36f..-6f),
      dynamicBass = prefs.getBoolean("dynamic_bass", false),
      bassIntensity = readLevel("bass_intensity", 0.35f, 0f..1f),
      dspEngine = prefs.getBoolean("dsp_engine", true),
      leveler = prefs.getBoolean("leveler", false)
    )
  }
  fun write(s: EngineSettings) {
    prefs.edit().putBoolean("enabled", s.enabled).putString("profile", s.profile.name)
      .putBoolean("adaptive", s.adaptive)
      .putBoolean("peak_guard",s.peakGuard)
      .putString("manual_bands", s.manualBands?.joinToString(","))
      .putFloat("bass_boost", s.bassBoost)
      .putString("spatial_mode", s.spatialMode.name)
      .putFloat("spatial_intensity", s.spatialIntensity)
      .putFloat("preamp_db", s.preampDb)
      .putString("sound_mode", s.soundMode.name)
      .putBoolean("compressor_enabled", s.compressorEnabled)
      .putFloat("compressor_ratio", s.compressorRatio)
      .putFloat("compressor_threshold", s.compressorThresholdDb)
      .putBoolean("dynamic_bass", s.dynamicBass).putFloat("bass_intensity", s.bassIntensity)
      .putBoolean("dsp_engine", s.dspEngine).putBoolean("leveler", s.leveler)
      .putBoolean("all_media",s.allMedia).putBoolean("loudness",s.loudness).apply()
  }
  /**
   * Reset sound only. Preserve the on/off switch so music is not interrupted.
   * ADB identity and pairing are stored separately and are not touched.
   */
  fun resetSound(activeRoute: RouteKind? = null) {
    val enabled = prefs.getBoolean("enabled", false)
    val dsp = prefs.getBoolean("dsp_engine", true)
    val editor = prefs.edit().clear().putBoolean("enabled", enabled).putBoolean("dsp_engine", dsp)
    // Write flat 10-band gains in the same transaction as the reset.
    // Otherwise automatic tone reconstruction redraws nonzero sliders.
    if (activeRoute != null) {
      editor.putString("route_eq_${activeRoute.name}",
        List(SystemEqCurveBuilder.BAND_COUNT) { 0f }.joinToString(","))
    }
    editor.apply()
  }

  /** User cancelled Android's capture prompt: do not auto-ask again until they opt in. */
  var dspDeclined: Boolean
    get() = prefs.getBoolean("dsp_declined", false)
    set(value) { prefs.edit().putBoolean("dsp_declined", value).apply() }

  private fun readLevel(key: String, default: Float, range: ClosedFloatingPointRange<Float>) =
    prefs.getFloat(key, default).takeIf { it in range } ?: default

  /** Per-route correction, validated before use. No network or background lookup. */
  fun routeCorrection(route: RouteKind): HeadphoneProfile? {
    val raw = prefs.getString("correction_${route.name}", null) ?: return null
    return runCatching {
      val json = JSONObject(raw)
      val filters = json.getJSONArray("filters")
      val result = HeadphoneProfile(json.getString("name"), json.getDouble("preamp"),
        (0 until filters.length()).map { i ->
          val item = filters.getJSONObject(i)
          EqFilter(FilterKind.valueOf(item.getString("kind")), item.getDouble("hz"),
            item.getDouble("gain"), item.getDouble("q"), item.optBoolean("enabled", true))
        })
      result.takeIf { it.valid() }
    }.getOrNull()
  }

  fun saveRouteCorrection(route: RouteKind, profile: HeadphoneProfile?) {
    require(profile == null || profile.valid())
    val raw = profile?.let { preset ->
      JSONObject().put("name", preset.name).put("preamp", preset.preampDb)
        .put("filters", org.json.JSONArray().apply {
          preset.filters.forEach { filter ->
            put(JSONObject().put("kind", filter.kind.name).put("hz", filter.frequency)
              .put("gain", filter.gain).put("q", filter.q).put("enabled", filter.enabled))
          }
        }).toString()
    }
    prefs.edit().putString("correction_${route.name}", raw).apply()
  }

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
