package com.n2k02.seq.audio

import android.content.Context
import com.n2k02.seq.model.SettingsStore
import com.n2k02.seq.model.SystemEqCurveBuilder

/**
 * Output-mix (session 0) effect so SEQ processes media from any app — including playback
 * projected to Android Auto — without requiring players to broadcast their session IDs.
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
    val current = effect ?: SeqSystemEffect.create(PRIORITY, OUTPUT_MIX, ::onControl)?.also {
      effect = it
      SeqRuntime.log("All-media effect attached (" + it.engine + ")")
    }
    if (current == null) {
      publish(false, "", "All-media effect unavailable on this device")
      return
    }
    try {
      val car = SeqRoute.isCar(ctx, s)
      current.apply(SystemEqCurveBuilder.build(SeqRoute.tone(ctx, s, car), null,
        limiter = s.peakGuard), s.peakGuard, s.peakCeilingDb)
      if (!current.setEnabled(true)) {
        release("All-media effect could not be enabled")
        return
      }
      publish(true, current.engine, if (car) "Processing all media • car profile"
        else "Processing all media", car, SeqRoute.describe(ctx, car))
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
  private fun onControl(granted: Boolean) {
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
