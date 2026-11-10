package com.n2k02.seq

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
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
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import com.n2k02.seq.audio.EngineStatus
import com.n2k02.seq.model.*
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

@Composable
internal fun ResponseGraph(gains: List<Float>, description: String, onBand: ((Int, Float) -> Unit)? = null) {
  val update by rememberUpdatedState(onBand)
  val limit = if (onBand == null) gains.maxOf { abs(it) }.coerceAtLeast(12f) else SystemEqCurveBuilder.MANUAL_LIMIT_DB
  val gesture = if (onBand == null) Modifier else Modifier
    .pointerInput(gains.size) {
      detectTapGestures { point ->
        val index = graphBand(point.x, size.width.toFloat(), gains.size)
        update?.invoke(index, 0f)
      }
    }
    .pointerInput(gains.size) {
      var index = 0
      detectDragGestures(onDragStart = { point ->
        index = graphBand(point.x, size.width.toFloat(), gains.size)
      }) { change, _ ->
        change.consume()
        val gain = ((1f - 2f * change.position.y / size.height) * limit).coerceIn(-limit, limit)
        update?.invoke(index, (gain * 2f).roundToInt() / 2f)
      }
    }
  val accent = MaterialTheme.colorScheme.primary
  val grid = MaterialTheme.colorScheme.outlineVariant
  Canvas(Modifier.fillMaxWidth().height(if (onBand == null) 104.dp else 132.dp).then(gesture).semantics { contentDescription = description }) {
    val middle = size.height / 2
    for (line in 0..8) {
      val x = size.width * line / 8
      drawLine(grid, Offset(x, 0f), Offset(x, size.height))
    }
    drawLine(grid, Offset(0f, middle), Offset(size.width, middle))
    val curve = Path()
    gains.forEachIndexed { i, gain ->
      val x = size.width * i / (gains.size - 1).coerceAtLeast(1)
      val y = middle - gain.coerceIn(-limit, limit) * middle / limit
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
    if (onBand != null) gains.forEachIndexed { index, gain ->
      val x = size.width * index / (gains.size - 1)
      val y = middle - gain.coerceIn(-limit, limit) * middle / limit
      drawCircle(accent, 4.dp.toPx(), Offset(x, y))
    }
  }
}

private fun graphBand(x: Float, width: Float, count: Int) =
  (x / width * (count - 1)).roundToInt().coerceIn(0, count - 1)

@Composable
private fun OutputSlider(title: String, value: Float, range: ClosedFloatingPointRange<Float>,
  format: (Float) -> String, onApply: (Float) -> Unit) {
  var draft by remember(value) { mutableFloatStateOf(value) }
  Text("$title • ${format(draft)}", style = MaterialTheme.typography.titleMedium)
  Slider(value = draft, onValueChange = { draft = it }, valueRange = range,
    onValueChangeFinished = { onApply(draft) },
    modifier = Modifier.semantics { contentDescription = title })
}

@Composable
internal fun DeviceProfilesScreen(active: RouteKind, output: String, mode: String,
  presets: Map<String, List<Float>>, selectedPreset: List<Float>?, bound: Boolean,
  onBind: (List<Float>?) -> Unit, onPreset: (List<Float>) -> Unit) {
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
    if (selectedPreset != null || bound) {
      Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        if (selectedPreset != null && !bound) TextButton(onClick = { onBind(selectedPreset) }) {
          Text("Remember for this output")
        }
        if (bound) TextButton(onClick = { onBind(null) }) { Text("Clear output preset") }
      }
    }
    if (presets.isEmpty()) Text("Save a preset from Equalizer to use it here.", style = MaterialTheme.typography.bodySmall)
    presets.forEach { (name, bands) ->
      FilledTonalButton(onClick = { onPreset(bands) }, modifier = Modifier.fillMaxWidth()) {
        Icon(if (bands == selectedPreset) Icons.Rounded.Check else Icons.Rounded.BookmarkBorder, null)
        Spacer(Modifier.width(8.dp))
        Text("$name • ${bands.size} bands")
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
  onSettings: (EngineSettings) -> Unit) {
  val oem = status.globalActive && !status.limiterAvailable
  val stages = buildList {
    add(if (settings.allMedia) "Android output mix" else "Compatible app session")
    if (settings.enabled && settings.profile != AudioProfile.PURE) {
      if (!oem) add("Input headroom")
      add(if (oem) "OEM equalizer" else "10-band EQ")
      if (!oem && settings.compressorEnabled) add("3-band compressor")
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
        onCheckedChange = { onSettings(settings.copy(peakGuard = it)) },
        modifier = Modifier.semantics { contentDescription = "Peak limiter" })
    }
  }
  AudioCard {
    Row(verticalAlignment = Alignment.CenterVertically) {
      Column(Modifier.weight(1f)) {
        Text("Multiband compression", style = MaterialTheme.typography.titleMedium)
        Text(if (oem) "Unavailable with OEM EQ" else "Gentle 3-band dynamics; off by default",
          style = MaterialTheme.typography.bodySmall)
      }
      Switch(checked = settings.compressorEnabled && !oem, enabled = !oem,
        onCheckedChange = { onSettings(settings.copy(compressorEnabled = it)) })
    }
    if (settings.compressorEnabled && !oem) {
      OutputSlider("Ratio", settings.compressorRatio, 1f..4f,
        { String.format(Locale.ROOT, "%.1f:1", it) }) {
        onSettings(settings.copy(compressorRatio = it))
      }
      OutputSlider("Threshold", settings.compressorThresholdDb, -36f..-6f,
        { String.format(Locale.ROOT, "%.0f dBFS", it) }) {
        onSettings(settings.copy(compressorThresholdDb = it))
      }
    }
  }
}

@Composable
internal fun AudioCard(content: @Composable ColumnScope.() -> Unit) {
  Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surfaceContainerLow,
    modifier = Modifier.fillMaxWidth()) {
    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp), content = content)
  }
}

/** One-screen equalizer. Every edit is applied and saved after a short idle debounce. */
/** One-screen SEQ equalizer with mRadio's preset and 3D controls.
 * Ten bands remain the single editable and stored EQ format.
 */
@Composable
internal fun SimpleEqualizerScreen(
  initial: List<Float>,
  selectedMode: SoundMode,
  custom: Boolean,
  bassBoost: Float,
  enabled: Boolean,
  spatialMode: SpatialMode,
  spatialIntensity: Float,
  /** SEQ DSP running: every 3D mode, including hall and 8D, is available. */
  pcmEngine: Boolean = false,
  onBands: (List<Float>) -> Unit,
  onMode: (SoundMode) -> Unit,
  onBass: (Float) -> Unit,
  onPreset: (String) -> Unit,
  onSpatial: (SpatialMode, Float) -> Unit
) {
  val count = SystemEqCurveBuilder.BAND_COUNT
  var gains by remember(initial) {
    mutableStateOf(SystemEqCurveBuilder.resample(initial, count))
  }
  var edited by remember(initial) { mutableStateOf(false) }
  val latestOnBands by rememberUpdatedState(onBands)
  LaunchedEffect(gains, edited) {
    if (edited) {
      kotlinx.coroutines.delay(120)
      latestOnBands(gains.toList())
    }
  }
  AudioCard {
    Text("3D sound", style = MaterialTheme.typography.titleMedium)
    Row(Modifier.horizontalScroll(rememberScrollState()),
      horizontalArrangement = Arrangement.spacedBy(8.dp)) {
      SpatialMode.entries.forEach { mode ->
        FilterChip(selected = spatialMode == mode,
          enabled = enabled && (pcmEngine || mode.platformSupported),
          onClick = { onSpatial(mode, spatialIntensity) },
          label = { Text(mode.label) })
      }
    }
    Text(spatialMode.blurb, style = MaterialTheme.typography.bodySmall)
    if (spatialMode != SpatialMode.OFF && (pcmEngine || spatialMode.platformSupported)) {
      var draftStrength by remember(spatialIntensity) { mutableFloatStateOf(spatialIntensity) }
      val latestOnSpatial by rememberUpdatedState(onSpatial)
      LaunchedEffect(draftStrength, spatialMode) {
        if (draftStrength != spatialIntensity) {
          kotlinx.coroutines.delay(120)
          latestOnSpatial(spatialMode, draftStrength)
        }
      }
      Slider(value = draftStrength.coerceIn(0f, 1f),
        onValueChange = { draftStrength = it },
        valueRange = 0f..1f, enabled = enabled,
        modifier = Modifier.semantics { contentDescription = "3D sound strength" })
      Text("Strength • ${(draftStrength * 100).roundToInt()}%" +
        if (pcmEngine) " • SEQ DSP" else " • Android virtualization support varies by output",
        style = MaterialTheme.typography.bodySmall)
    }
    Text(if (pcmEngine) "All 3D modes run in the SEQ DSP engine."
      else "Concert hall and 8D orbit start automatically with the SEQ DSP engine.",
      style = MaterialTheme.typography.bodySmall)
  }
  AudioCard {
    Text("Presets", style = MaterialTheme.typography.titleMedium)
    val selectedPreset = if (edited) null else MRadioPresets.matching(if (custom) initial else null)
    Row(Modifier.horizontalScroll(rememberScrollState()),
      horizontalArrangement = Arrangement.spacedBy(8.dp)) {
      MRadioPresets.names.forEach { name ->
        FilterChip(selected = selectedPreset == name,
          enabled = enabled, onClick = { onPreset(name) }, label = { Text(name) })
      }
    }
    Text("mRadio's presets adapted to SEQ's 10-band range.", style = MaterialTheme.typography.bodySmall)
  }
  AudioCard {
    Text("Equalizer", style = MaterialTheme.typography.titleLarge)
    Text("Choose a sound style", style = MaterialTheme.typography.bodySmall)
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
      SoundMode.entries.forEach { mode ->
        FilterChip(selected = selectedMode == mode && !custom && !edited,
          enabled = enabled, onClick = { edited = false; onMode(mode) },
          label = { Text(mode.title) })
      }
    }
    Text(if (custom && gains.all { it == 0f }) "Flat • default sound"
      else if (custom) "Custom sound • saved for this output" else "Fine tune • 10 bands",
      style = MaterialTheme.typography.titleMedium)
    val centres = SystemEqCurveBuilder.centres(count)
    gains.forEachIndexed { index, value ->
      val frequency = centres[index]
      val label = if (frequency < 1000.0) "${frequency.roundToInt()} Hz"
        else "${(frequency / 1000.0).toFloat().let { String.format(Locale.ROOT, "%.1f", it) }} kHz"
      Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, modifier = Modifier.width(78.dp), style = MaterialTheme.typography.bodySmall)
        Slider(value = value, onValueChange = { level ->
          gains = gains.toMutableList().also { it[index] = (level * 2f).roundToInt() / 2f }
          edited = true
        }, enabled = enabled,
          valueRange = -SystemEqCurveBuilder.MANUAL_LIMIT_DB..SystemEqCurveBuilder.MANUAL_LIMIT_DB,
          modifier = Modifier.weight(1f).semantics { contentDescription = "$label equalizer" })
        Text(String.format(Locale.ROOT, "%+.1f dB", value), modifier = Modifier.width(65.dp),
          style = MaterialTheme.typography.bodySmall)
      }
    }
    Text("Every adjustment applies automatically and is saved.", style = MaterialTheme.typography.bodySmall)
  }
  AudioCard {
    Text("Bass boost", style = MaterialTheme.typography.titleMedium)
    var bass by remember(bassBoost) { mutableFloatStateOf(bassBoost) }
    val latestBass by rememberUpdatedState(onBass)
    LaunchedEffect(bass) {
      if (bass != bassBoost) {
        kotlinx.coroutines.delay(120)
        latestBass(bass)
      }
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
      Slider(value = bass, onValueChange = { bass = it }, valueRange = 0f..1f,
        enabled = enabled, modifier = Modifier.weight(1f).semantics { contentDescription = "Bass level" })
      Text("${(bass * 100).roundToInt()}%", style = MaterialTheme.typography.bodySmall,
        modifier = Modifier.width(50.dp))
    }
  }
}
