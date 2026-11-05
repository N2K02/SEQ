package com.n2k02.seq.audio

import kotlinx.coroutines.flow.MutableStateFlow
import java.util.concurrent.CopyOnWriteArrayList

data class EngineStatus(
  val running: Boolean = false,
  val sessions: Int = 0,
  val lastEvent: String = "Engine off",
  val carProfileActive: Boolean = false,
  val route: String = "Unknown",
  val effectAttached: Boolean = false
)
object SeqRuntime {
  val status = MutableStateFlow(EngineStatus())
  val player = MutableStateFlow("Stopped")
  private val ring = CopyOnWriteArrayList<String>()
  fun log(message: String) {
    val safe = message.take(180).replace(Regex("[\\r\\n]"), " ")
    ring.add(safe)
    while (ring.size > 80) ring.removeAt(0)
  }
  fun logs(): List<String> = ring.toList()
  fun clear() { ring.clear() }
  fun reset() { status.value = EngineStatus(); log("All SEQ effects released") }
}
