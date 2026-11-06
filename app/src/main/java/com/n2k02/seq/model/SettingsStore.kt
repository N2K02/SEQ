package com.n2k02.seq.model

import android.content.Context

class SettingsStore(context: Context) {
  private val prefs = context.applicationContext.getSharedPreferences("seq_settings", Context.MODE_PRIVATE)
  fun read(): EngineSettings {
    val profile = AudioProfile.entries.firstOrNull { it.name == prefs.getString("profile", "") }
      ?: AudioProfile.REFERENCE
    return EngineSettings(
      enabled = prefs.getBoolean("enabled", false),
      profile = profile,
      customTone = Tone(prefs.getFloat("bass", 0f), prefs.getFloat("mid", 0f), prefs.getFloat("treble", 0f)),
      carTone = Tone(prefs.getFloat("car_bass", 0f), prefs.getFloat("car_mid", 0f), prefs.getFloat("car_treble", 0f)),
      automaticCarProfile = prefs.getBoolean("car_auto", true),
      carManual = prefs.getBoolean("car_manual", false),
      carDeviceName = prefs.getString("car_device", "") ?: "",
      aiEnabled = prefs.getBoolean("ai", false),
      headphoneCorrection = prefs.getBoolean("headphone_correction", false),
      convolution = prefs.getBoolean("convolution", false),
      peakGuard = prefs.getBoolean("peak_guard", false),
      peakCeilingDb = prefs.getFloat("peak_ceiling", -1.0f).coerceIn(-12f, -0.1f),
      usbDirect = prefs.getBoolean("usb_direct", false)
    )
  }
  fun write(s: EngineSettings) {
    prefs.edit().putBoolean("enabled", s.enabled).putString("profile", s.profile.name)
      .putFloat("bass", s.customTone.bass).putFloat("mid", s.customTone.mid)
      .putFloat("treble", s.customTone.treble)
      .putFloat("car_bass", s.carTone.bass).putFloat("car_mid", s.carTone.mid)
      .putFloat("car_treble", s.carTone.treble)
      .putBoolean("car_auto", s.automaticCarProfile).putBoolean("car_manual", s.carManual)
      .putString("car_device", s.carDeviceName.take(100))
      .putBoolean("ai", s.aiEnabled).putBoolean("headphone_correction", s.headphoneCorrection)
      .putBoolean("convolution",s.convolution).putBoolean("peak_guard",s.peakGuard)
      .putFloat("peak_ceiling",s.peakCeilingDb.coerceIn(-12f,-0.1f))
      .putBoolean("usb_direct",s.usbDirect).apply()
    SeqAudioRevision.changed()
  }
}
