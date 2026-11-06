package com.n2k02.seq.audio

import kotlinx.coroutines.flow.MutableStateFlow
import java.util.concurrent.CopyOnWriteArrayList

data class EngineStatus(
  val running: Boolean = false,
  val sessions: Int = 0,
  val lastEvent: String = "Engine off",
  val carProfileActive: Boolean = false,
  val route: String = "Unknown",
  val effectAttached: Boolean = false,
  /** Output-mix effect active: every app's media, incl. Android Auto, is processed. */
  val globalActive: Boolean = false,
  val effectEngine: String = ""
) {
  val processing: Boolean get() = effectAttached || globalActive
}
object SeqRuntime {
  val status = MutableStateFlow(EngineStatus())
  private val ring = CopyOnWriteArrayList<String>()
  fun log(message: String) {
    val safe = message.take(180).replace(Regex("[\\r\\n]"), " ")
    ring.add(safe)
    while (ring.size > 80) ring.removeAt(0)
  }
  fun logs(): List<String> = ring.toList()
  fun clear() { ring.clear() }
  /** Engine service stopped; the all-media effect has its own lifecycle and is preserved. */
  fun reset() {
    val s = status.value
    status.value = EngineStatus(globalActive = s.globalActive, effectEngine = s.effectEngine,
      carProfileActive = s.carProfileActive, route = s.route,
      lastEvent = if (s.globalActive) s.lastEvent else "Engine off")
    log("Session effects released")
  }
}
