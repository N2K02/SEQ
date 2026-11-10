package com.n2k02.seq.audio

import android.content.Context
import com.n2k02.seq.model.SettingsStore
import com.n2k02.seq.model.SystemEqCurveBuilder

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
  private var lastRouteKey: Pair<Boolean, List<Int>>? = null

  @Volatile var comparisonGeneration = 0
    private set
  val comparing: Boolean get() = comparisonGeneration % 2 != 0

  @Synchronized fun compare(context: Context, held: Boolean): Boolean {
    if (held == comparing) return comparing
    if (held) {
      val current = effect ?: return false
      if (!current.hasControl || !current.bypass()) return false
    }
    comparisonGeneration++
    sync(context)
    return comparing
  }

  @Synchronized fun sync(context: Context) {
    val ctx = context.applicationContext
    app = ctx
    val store = SettingsStore(ctx)
    val s = store.read()
    if (!SessionPolicy.shouldProcessAllMedia(s)) {
      val reason = if (!s.enabled || !s.allMedia) null else "Pure bypass"
      release(reason)
      return
    }
    try {
      val car = SeqRoute.isCar()
      if (comparing) {
        val current = effect
        val bypassed = current != null && current.hasControl && current.bypass()
        val message = if (bypassed) "A/B comparison • bypass" else "A/B comparison unavailable"
        publish(false, "", message, car, SeqRoute.describe(ctx, car))
        return
      }
      val route = SeqRoute.kind(ctx, car)
      val routeKey = SeqRoute.outputKey(ctx, car)
      if (routeKey != lastRouteKey) {
        lastRouteKey = routeKey
        SeqRuntime.log("Effect route=${SeqRoute.describe(ctx, car)} engine=${effect?.engine ?: "not attached"} mix=0; Bluetooth offload path unverified")
      }
      val bands = store.routeBands(route) ?: s.manualBands
      val bassDb = SystemEqCurveBuilder.bassGain(s, route, SeqRoute.volumeFraction(ctx))
      val correction = store.routeCorrection(route)
      val tone = SeqRoute.tone(ctx, s, car)
      fun attach(allowDynamics: Boolean): SeqSystemEffect? =
        SeqSystemEffect.open(PRIORITY, OUTPUT_MIX, tone, s.peakGuard, s.peakCeilingDb,
          bands, bassDb, s.preampDb, correction, s.compressorEnabled,
          s.compressorRatio, s.compressorThresholdDb, allowDynamics, ::onControl)?.also {
          effect = it
          SeqRuntime.log("Output mix effect attached (" + it.engine + ")")
        }

      val alreadyAttached = effect != null
      var current = effect ?: attach(allowDynamics = true)
      if (current != null && !current.hasControl) {
        publish(false, "", "Another audio effect app has control", car, SeqRoute.describe(ctx, car))
        return
      }
      if (current != null) {
        val active = current
        val ready = runCatching {
          // A newly attached effect was configured and enabled in open().
          // Avoid immediately programming it a second time.
          if (alreadyAttached) active.apply(tone, s.peakGuard, s.peakCeilingDb, bands, bassDb, s.preampDb,
            correction, s.compressorEnabled, s.compressorRatio, s.compressorThresholdDb)
          active.enable()
        }.getOrDefault(false)
        if (!ready) {
          if (!active.hasControl) {
            active.invalidateTuning()
            publish(false, "", "Another audio effect app has control", car, SeqRoute.describe(ctx, car))
            return
          }
          release("Output mix effect rejected tuning")
          current = if (active.limiterAvailable) attach(allowDynamics = false) else null
        }
      }
      if (current == null) {
        publish(false, "", "Output mix effect unavailable", car, SeqRoute.describe(ctx, car))
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
      SeqRuntime.log("All-media effect released reason=${reason ?: "requested"}")
    }
    effect = null
    lastRouteKey = null
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
      limiterAvailable = active && effect?.limiterAvailable == true,
      eqResponse = if (active) effect?.response else null,
      lastEvent = event, carProfileActive = car ?: s.carProfileActive, route = route ?: s.route)
  }
}
