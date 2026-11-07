package com.n2k02.seq.audio

import android.content.Context
import com.n2k02.seq.model.SettingsStore

/**
 * Output-mix (session 0) effect for media routed through a compatible Android mixer.
 * Players do not need to broadcast session IDs.
 * Lives in the app process; the system-bound media listener and the engine service keep that
 * process alive. Every caller just invokes [sync]; it is idempotent and cheap.
 */
object SeqGlobalEffect {
  private const val OUTPUT_MIX = 0
  /** High priority so SEQ keeps control of the output mix when the user enabled it. */
  private const val PRIORITY = Int.MAX_VALUE
  private var effect: SeqSystemEffect? = null
  private var app: Context? = null

  @Synchronized fun sync(context: Context) {
    val ctx = context.applicationContext
    app = ctx
    val s = SettingsStore(ctx).read()
    if (!SessionPolicy.shouldProcessAllMedia(s)) {
      val reason = if (!s.enabled || !s.allMedia) null else "Pure bypass"
      release(reason)
      return
    }
    try {
      val car = SeqRoute.isCar(ctx, s)
      val tone = SeqRoute.tone(ctx, s, car)
      val current = effect ?: SeqSystemEffect.open(PRIORITY, OUTPUT_MIX, tone, s.peakGuard,
        s.peakCeilingDb, ::onControl)?.also {
        effect = it
        SeqRuntime.log("Output mix effect attached (" + it.engine + ")")
      }
      if (current == null) {
        publish(false, "", "Output mix effect unavailable", car, SeqRoute.describe(ctx, car))
        return
      }
      if (!current.hasControl) {
        publish(false, "", "Another audio effect app has control", car, SeqRoute.describe(ctx, car))
        return
      }
      current.apply(tone, s.peakGuard, s.peakCeilingDb)
      if (!current.enable()) {
        release("Output mix effect could not be enabled")
        return
      }
      publish(true, current.engine, "Output mix effect enabled", car, SeqRoute.describe(ctx, car))
    } catch (e: Exception) {
      SeqRuntime.log("All-media effect failed: " + e.javaClass.simpleName)
      release("All-media effect unavailable")
    }
  }

  @Synchronized fun release(reason: String? = null) {
    effect?.let {
      it.close()
      SeqRuntime.log("All-media effect released")
    }
    effect = null
    publish(false, "", reason ?: SeqRuntime.status.value.lastEvent)
  }

  /** Another app took (or returned) control of the output mix: re-apply when regained. */
  @Synchronized private fun onControl(source: SeqSystemEffect, granted: Boolean) {
    if (source !== effect) return
    source.invalidateTuning()
    SeqRuntime.log(if (granted) "All-media control regained" else "All-media control taken by another app")
    if (granted) app?.let(::sync)
    else publish(false, "", "Another audio effect app has control")
  }

  private fun publish(active: Boolean, engine: String, event: String,
    car: Boolean? = null, route: String? = null) {
    val s = SeqRuntime.status.value
    SeqRuntime.status.value = s.copy(globalActive = active, effectEngine = engine,
      lastEvent = event, carProfileActive = car ?: s.carProfileActive, route = route ?: s.route)
  }
}
