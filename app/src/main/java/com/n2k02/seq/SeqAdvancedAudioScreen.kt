package com.n2k02.seq

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.n2k02.seq.model.*
import com.n2k02.seq.adb.LocalAdbDiagnostics
import com.n2k02.seq.audio.SeqRuntime
import java.util.Locale

/** Live correction is computed into the 31-band platform response; not native sample-level PEQ. */
@Composable
internal fun AdvancedAudioScreen(route: RouteKind, active: Boolean, correction: HeadphoneProfile?,
  adb: LocalAdbDiagnostics, onSave: (HeadphoneProfile?) -> Unit) {
  var name by rememberSaveable(route) { mutableStateOf(correction?.name ?: "Custom headphone") }
  var text by rememberSaveable(route) { mutableStateOf("") }
  var error by rememberSaveable(route) { mutableStateOf("") }
  var hz by rememberSaveable(route) { mutableStateOf("1000") }
  var gain by rememberSaveable(route) { mutableStateOf("-2") }
  var q by rememberSaveable(route) { mutableStateOf("1.0") }
  var kind by rememberSaveable(route) { mutableStateOf(FilterKind.PEAK) }

  AudioCard {
    Text("Parametric equalization · ${route.title}", style = MaterialTheme.typography.titleMedium)
    Text("The 31-band platform EQ approximates the combined PEQ response. " +
      "Narrow filters may differ from a sample-level biquad.", style = MaterialTheme.typography.bodySmall)
    Text(correction?.let { "${it.name} · ${it.filters.size} filters · preamp ${it.preampDb} dB" }
      ?: "No headphone correction selected", style = MaterialTheme.typography.bodyMedium)
    if (correction != null) {
      correction.filters.forEach { filter ->
        Text(String.format(Locale.ROOT, "%s %.0f Hz  %+.1f dB  Q %.2f%s",
          filter.kind.name, filter.frequency, filter.gain, filter.q,
          if (filter.enabled) "" else " (off)"), style = MaterialTheme.typography.bodySmall)
      }
      TextButton(onClick = { onSave(null); error = "" }) { Text("Clear this route's correction") }
    }
    Text(if (active) "The active engine will be refreshed after applying."
      else "Engine is not currently processing audio.", style = MaterialTheme.typography.bodySmall)
  }
  AudioCard {
    Text("Add parametric filter", style = MaterialTheme.typography.titleMedium)
    OutlinedTextField(name, { name = it.take(100) }, label = { Text("Profile name") },
      singleLine = true, modifier = Modifier.fillMaxWidth())
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
      FilterKind.entries.forEach { type ->
        FilterChip(selected = kind == type, onClick = { kind = type },
          label = { Text(type.name.replace("_", " ")) })
      }
    }
    OutlinedTextField(hz, { hz = it.take(10) }, label = { Text("Frequency (Hz)") },
      singleLine = true, modifier = Modifier.fillMaxWidth())
    OutlinedTextField(gain, { gain = it.take(10) }, label = { Text("Gain (dB)") },
      singleLine = true, modifier = Modifier.fillMaxWidth())
    OutlinedTextField(q, { q = it.take(10) }, label = { Text("Q factor") },
      singleLine = true, modifier = Modifier.fillMaxWidth())
    Button(onClick = {
      val filter = EqFilter(kind, hz.toDoubleOrNull() ?: Double.NaN,
        gain.toDoubleOrNull() ?: Double.NaN, q.toDoubleOrNull() ?: Double.NaN)
      val profile = HeadphoneProfile(name.trim(), correction?.preampDb ?: -6.0,
        (correction?.filters ?: emptyList()) + filter)
      if (profile.valid()) { onSave(profile); error = "" }
      else error = "Use a valid name, 20–20000 Hz, ±12 dB, Q 0.15–12, and at most 12 filters."
    }, modifier = Modifier.fillMaxWidth()) { Text("Add filter and apply") }
  }
  AudioCard {
    Text("Import AutoEQ / Equalizer APO", style = MaterialTheme.typography.titleMedium)
    Text("Paste a valid Equalizer APO preset (Preamp and Filter lines). " +
      "Only local text is used; no preset is downloaded.", style = MaterialTheme.typography.bodySmall)
    OutlinedTextField(text, { text = it.take(50000) },
      label = { Text("AutoEQ text") }, minLines = 5, maxLines = 10, modifier = Modifier.fillMaxWidth())
    Button(onClick = {
      runCatching { AutoEqImport.parse(name.trim(), text) }
        .onSuccess { onSave(it); error = "" }
        .onFailure { error = it.message ?: "Invalid AutoEQ preset" }
    }, enabled = text.isNotBlank(), modifier = Modifier.fillMaxWidth()) {
      Text("Import and apply to ${route.title}")
    }
  }
  if (error.isNotBlank()) Text(error, color = MaterialTheme.colorScheme.error)
  val routingReport by adb.routingProbe.collectAsState()
  var logRevision by remember { mutableIntStateOf(0) }
  AudioCard {
    Text("Experimental routing lab", style = MaterialTheme.typography.titleMedium)
    Text("Read-only ADB probe; no audio routing changes, capture, or repeated polling.",
      style = MaterialTheme.typography.bodySmall)
    Button(onClick = { adb.inspectRouting(); logRevision++ },
      modifier = Modifier.fillMaxWidth()) { Text("Probe audio routing (ADB)") }
    Text(routingReport, style = MaterialTheme.typography.bodySmall)
    Text("A remote-submix hint is not proof of exclusive routing. PCM replay remains disabled for other apps.",
      style = MaterialTheme.typography.bodySmall)
    TextButton(onClick = { logRevision++ }) { Text("Refresh audio logs") }
    val logs = remember(logRevision, routingReport) { SeqRuntime.logs().takeLast(16) }
    logs.forEach { Text(it, style = MaterialTheme.typography.labelSmall) }
  }
  AudioCard {
    Text("Advanced PCM processing", style = MaterialTheme.typography.titleMedium)
    Text("Convolution and sample-accurate true-peak limiting require an actual PCM stream. " +
      "Android's output-mix AudioEffect does not expose PCM samples. " +
      "Third-party capture is restricted and can cause doubled playback, so it is not " +
      "silently substituted for this low-latency engine.",
      style = MaterialTheme.typography.bodySmall)
  }
}
