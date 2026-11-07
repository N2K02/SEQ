package com.n2k02.seq

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.layout
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import com.n2k02.seq.audio.EngineStatus
import com.n2k02.seq.model.*
import java.util.Locale
import kotlin.math.roundToInt
import kotlin.math.sqrt

@Composable
internal fun ResponseGraph(gains: List<Float>, description: String) {
  val accent = MaterialTheme.colorScheme.primary
  val grid = MaterialTheme.colorScheme.outlineVariant
  Canvas(Modifier.fillMaxWidth().height(104.dp).semantics { contentDescription = description }) {
    val middle = size.height / 2
    for (line in 0..8) {
      val x = size.width * line / 8
      drawLine(grid, Offset(x, 0f), Offset(x, size.height))
    }
    drawLine(grid, Offset(0f, middle), Offset(size.width, middle))
    val curve = Path()
    gains.forEachIndexed { i, gain ->
      val x = size.width * i / (gains.size - 1)
      val y = middle - gain.coerceIn(-12f, 12f) * middle / 12f
      if (i == 0) curve.moveTo(x, y) else curve.lineTo(x, y)
    }
    val fill = Path().apply {
      addPath(curve)
      lineTo(size.width, size.height)
      lineTo(0f, size.height)
      close()
    }
    drawPath(fill, Brush.verticalGradient(listOf(accent.copy(alpha = 0.3f), accent.copy(alpha = 0.04f))))
    drawPath(curve, accent, style = Stroke(width = 2.dp.toPx()))
  }
}

@Composable
internal fun EqualizerScreen(initial: List<Float>, manualActive: Boolean,
  onApply: (List<Float>) -> Unit, onSave: (String, List<Float>) -> Unit) {
  var bands by rememberSaveable { mutableStateOf(ArrayList(initial)) }
  var showSave by rememberSaveable { mutableStateOf(false) }
  var name by rememberSaveable { mutableStateOf("") }
  val limit = SystemEqCurveBuilder.MANUAL_LIMIT_DB
  AudioCard {
    Text("31-band EQ", style = MaterialTheme.typography.titleLarge)
    Text("Manual tuning • Swipe for more bands", style = MaterialTheme.typography.bodySmall)
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())) {
      bands.forEachIndexed { i, gain ->
        val low = if (i == 0) 20.0 else SystemEqCurveBuilder.cutoffHz(i - 1)
        val centre = sqrt(low * SystemEqCurveBuilder.cutoffHz(i))
        val label = if (centre < 1000) "${centre.roundToInt()}"
          else String.format(Locale.ROOT, "%.1fk", centre / 1000)
        Column(Modifier.width(52.dp), horizontalAlignment = Alignment.CenterHorizontally) {
          Slider(value = gain, onValueChange = { level ->
            bands = ArrayList(bands).also { it[i] = (level * 2).roundToInt() / 2f }
          }, valueRange = -limit..limit, modifier = Modifier.width(48.dp).height(140.dp)
            .semantics { contentDescription = "$label Hz gain" }
            .graphicsLayer { rotationZ = -90f }
            .layout { measurable, constraints ->
              val placeable = measurable.measure(Constraints.fixed(constraints.maxHeight, constraints.maxWidth))
              layout(constraints.maxWidth, constraints.maxHeight) {
                placeable.place((constraints.maxWidth - placeable.width) / 2,
                  (constraints.maxHeight - placeable.height) / 2)
              }
            })
          Text(label, style = MaterialTheme.typography.labelSmall)
          Text(String.format(Locale.ROOT, "%+.1f", gain), style = MaterialTheme.typography.labelSmall)
        }
      }
    }
    ResponseGraph(bands, "Manual equalizer response preview")
    Text("Gain in dB • OEM EQ uses its available bands.", style = MaterialTheme.typography.bodySmall)
  }
  Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
    OutlinedButton(onClick = { bands = ArrayList(List(bands.size) { 0f }) },
      modifier = Modifier.weight(1f)) { Text("Reset") }
    FilledTonalButton(onClick = { showSave = true }, modifier = Modifier.weight(1.4f)) { Text("Save preset") }
  }
  Button(onClick = { onApply(bands.toList()) }, enabled = !manualActive || bands != initial, modifier = Modifier.fillMaxWidth()) {
    Text(if (manualActive && bands == initial) "Manual tuning selected" else "Apply manual tuning")
  }
  if (showSave) AlertDialog(
    onDismissRequest = { showSave = false },
    title = { Text("Save preset") },
    text = { OutlinedTextField(value = name, onValueChange = { name = it.take(40) },
      label = { Text("Preset name") }, singleLine = true) },
    confirmButton = {
      TextButton(enabled = name.isNotBlank(), onClick = {
        onSave(name.trim(), bands.toList())
        showSave = false
        name = ""
      }) { Text("Save") }
    },
    dismissButton = { TextButton(onClick = { showSave = false }) { Text("Cancel") } }
  )
}

@Composable
internal fun DeviceProfilesScreen(active: RouteKind, output: String, mode: String,
  presets: Map<String, List<Float>>, selectedPreset: List<Float>?, onPreset: (List<Float>) -> Unit) {
  RouteKind.entries.forEach { route ->
    val selected = route == active
    Surface(shape = MaterialTheme.shapes.large,
      color = if (selected) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceContainerLow) {
      ListItem(headlineContent = { Text(if (route == RouteKind.CAR) "Android Auto" else route.title) },
        supportingContent = { Text(if (selected) "$mode • Current route" else "Automatic route") },
        leadingContent = { Icon(routeIcon(route), null) },
        trailingContent = { if (selected) Icon(Icons.Rounded.CheckCircle, "Current route") },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent))
    }
  }
  AudioCard {
    Text("Device details", style = MaterialTheme.typography.titleMedium)
    Text(output)
    if (active == RouteKind.BLUETOOTH) Text("Codec details unavailable", style = MaterialTheme.typography.bodySmall)
  }
  AudioCard {
    Text("Saved presets", style = MaterialTheme.typography.titleMedium)
    if (presets.isEmpty()) Text("Save a preset from Equalizer to use it here.", style = MaterialTheme.typography.bodySmall)
    presets.forEach { (name, bands) ->
      FilledTonalButton(onClick = { onPreset(bands) }, modifier = Modifier.fillMaxWidth()) {
        Icon(if (bands == selectedPreset) Icons.Rounded.Check else Icons.Rounded.BookmarkBorder, null)
        Spacer(Modifier.width(8.dp))
        Text(name)
      }
    }
  }
}

internal fun routeIcon(route: RouteKind) = when (route) {
  RouteKind.CAR -> Icons.Rounded.DirectionsCar
  RouteKind.SPEAKER -> Icons.Rounded.Speaker
  RouteKind.WIRED -> Icons.Rounded.Headphones
  RouteKind.BLUETOOTH -> Icons.Rounded.Bluetooth
}

@Composable
internal fun SignalPathScreen(status: EngineStatus, output: String, settings: EngineSettings,
  onLimiter: (Boolean) -> Unit) {
  val oem = status.globalActive && !status.limiterAvailable
  val stages = buildList {
    add(if (settings.allMedia) "Android output mix" else "Compatible app session")
    if (settings.enabled && settings.profile != AudioProfile.PURE) {
      if (!oem) add("Input headroom")
      add(if (oem) "OEM equalizer" else "31-band EQ")
      if (!oem && settings.peakGuard) add("Peak limiter")
    }
    add("Output")
  }
  AudioCard {
    Text(output, style = MaterialTheme.typography.titleMedium)
    Text(when {
      !settings.enabled -> "Engine off"
      settings.profile == AudioProfile.PURE -> "Pure bypass"
      status.processing -> status.effectEngine.ifEmpty { "Session effect attached" }
      else -> status.lastEvent
    }, style = MaterialTheme.typography.bodySmall)
  }
  val accent = MaterialTheme.colorScheme.primary
  Column(Modifier.fillMaxWidth()) {
    stages.forEachIndexed { index, stage ->
      Row(Modifier.fillMaxWidth().drawBehind {
        val x = 6.dp.toPx()
        val middle = size.height / 2
        drawLine(accent, Offset(x, if (index == 0) middle else 0f),
          Offset(x, if (index == stages.lastIndex) middle else size.height), 2.dp.toPx())
        drawCircle(accent, radius = 4.dp.toPx(), center = Offset(x, middle))
      }.padding(start = 22.dp, top = 4.dp, bottom = 4.dp)) {
        AudioCard { Text(stage, style = MaterialTheme.typography.titleMedium) }
      }
    }
  }
  AudioCard {
    Row(verticalAlignment = Alignment.CenterVertically) {
      Column(Modifier.weight(1f)) {
        Text("Peak limiter", style = MaterialTheme.typography.titleMedium)
        Text(if (oem) "Unavailable with OEM EQ" else "Ceiling −1 dBFS",
          style = MaterialTheme.typography.bodySmall)
      }
      Switch(checked = settings.peakGuard && !oem, enabled = !oem,
        onCheckedChange = onLimiter, modifier = Modifier.semantics { contentDescription = "Peak limiter" })
    }
  }
  AudioCard {
    Text("Multiband compressor", style = MaterialTheme.typography.titleMedium)
    Text("Concept", style = MaterialTheme.typography.bodySmall)
  }
}

@Composable
internal fun AudioCard(content: @Composable ColumnScope.() -> Unit) {
  Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surfaceContainerLow,
    modifier = Modifier.fillMaxWidth()) {
    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp), content = content)
  }
}
