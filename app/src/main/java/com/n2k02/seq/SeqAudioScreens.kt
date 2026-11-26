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
import kotlin.math.pow
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

private fun bandAt(x: Float, width: Float, count: Int) =
  (x / width * count).toInt().coerceIn(0, count - 1)

private fun bandLabel(hz: Double): String =
  if (hz < 1000.0) "${hz.roundToInt()} Hz"
  else String.format(Locale.ROOT, "%.1f kHz", hz / 1000.0).replace(".0 kHz", " kHz")

/**
 * The equalizer curve as the DSP renders it: the smooth line is the real combined response of
 * the solved filters (not a polyline through the sliders), the dots are the 10 bands.
 * Drag a dot to change it, tap to select, double-tap to flatten one band.
 */
@Composable
private fun EqualizerCurve(gains: List<Float>, enabled: Boolean, selected: Int,
  onSelect: (Int) -> Unit, onGain: (Int, Float) -> Unit) {
  val limit = SystemEqCurveBuilder.MANUAL_LIMIT_DB
  val filters = remember(gains) { GraphicEq.filters(gains) }
  val curve = remember(filters) {
    val first = GraphicEq.centres.first()
    List(97) { k ->
      val octaves = k / 96.0 * gains.size - 0.5
      filters.sumOf { EqResponse.db(first * 2.0.pow(octaves), it) }.toFloat()
    }
  }
  val latestSelect by rememberUpdatedState(onSelect)
  val latestGain by rememberUpdatedState(onGain)
  val accent = MaterialTheme.colorScheme.primary
  val grid = MaterialTheme.colorScheme.outlineVariant
  val zero = MaterialTheme.colorScheme.outline
  val surface = MaterialTheme.colorScheme.surfaceContainerLow
  val gesture = Modifier
    .pointerInput(enabled) {
      if (enabled) detectTapGestures(
        onTap = { p -> latestSelect(bandAt(p.x, size.width.toFloat(), gains.size)) },
        onDoubleTap = { p ->
          val index = bandAt(p.x, size.width.toFloat(), gains.size)
          latestSelect(index)
          latestGain(index, 0f)
        })
    }
    .pointerInput(enabled) {
      if (enabled) {
        var index = 0
        detectDragGestures(onDragStart = { p ->
          index = bandAt(p.x, size.width.toFloat(), gains.size)
          latestSelect(index)
        }) { change, _ ->
          change.consume()
          val gain = ((1f - 2f * change.position.y / size.height) * limit).coerceIn(-limit, limit)
          latestGain(index, (gain * 2f).roundToInt() / 2f)
        }
      }
    }
  Canvas(Modifier.fillMaxWidth().height(200.dp).then(gesture).semantics {
    contentDescription = "Equalizer response graph, drag a point to change a band"
  }) {
    val middle = size.height / 2
    fun y(db: Float) = middle - db.coerceIn(-limit, limit) * middle / limit
    for (db in listOf(-12f, -6f, 6f, 12f)) drawLine(grid, Offset(0f, y(db)), Offset(size.width, y(db)))
    for (i in 0 until gains.size) {
      val x = size.width * (i + 0.5f) / gains.size
      drawLine(grid.copy(alpha = if (i == selected) 0.9f else 0.35f), Offset(x, 0f), Offset(x, size.height))
    }
    drawLine(zero, Offset(0f, middle), Offset(size.width, middle), 1.5.dp.toPx())
    val line = Path()
    curve.forEachIndexed { k, db ->
      val x = size.width * k / (curve.size - 1)
      if (k == 0) line.moveTo(x, y(db)) else line.lineTo(x, y(db))
    }
    val fill = Path().apply {
      addPath(line)
      lineTo(size.width, middle)
      lineTo(0f, middle)
      close()
    }
    drawPath(fill, Brush.verticalGradient(listOf(accent.copy(alpha = 0.28f), accent.copy(alpha = 0.04f))))
    drawPath(line, accent, style = Stroke(width = 2.5.dp.toPx()))
    gains.forEachIndexed { index, gain ->
      val centre = Offset(size.width * (index + 0.5f) / gains.size, y(gain))
      if (index == selected) drawCircle(accent.copy(alpha = 0.25f), 14.dp.toPx(), centre)
      drawCircle(surface, 6.dp.toPx(), centre)
      drawCircle(accent, 4.5.dp.toPx(), centre)
    }
  }
  Row(Modifier.fillMaxWidth()) {
    GraphicEq.centres.forEachIndexed { index, hz ->
      Text(if (hz < 1000.0) "${hz.roundToInt()}" else "${(hz / 1000.0).roundToInt()}k",
        modifier = Modifier.weight(1f), textAlign = androidx.compose.ui.text.style.TextAlign.Center,
        style = MaterialTheme.typography.labelSmall,
        color = if (index == selected) accent else MaterialTheme.colorScheme.onSurfaceVariant)
    }
  }
}

/** One-screen SEQ sound controls: styles and presets, a true-response equalizer, bass, 3D.
 * Ten bands remain the single editable and stored EQ format. Every edit is applied and saved
 * after a short idle debounce; there is no Apply or Save step.
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
  val limit = SystemEqCurveBuilder.MANUAL_LIMIT_DB
  var gains by remember(initial) {
    mutableStateOf(SystemEqCurveBuilder.resample(initial, count))
  }
  var edited by remember(initial) { mutableStateOf(false) }
  var selected by rememberSaveable { mutableStateOf(4) }
  val latestOnBands by rememberUpdatedState(onBands)
  LaunchedEffect(gains, edited) {
    if (edited) {
      kotlinx.coroutines.delay(120)
      latestOnBands(gains.toList())
    }
  }
  fun setBand(index: Int, value: Float) {
    val next = (value.coerceIn(-limit, limit) * 2f).roundToInt() / 2f
    if (gains[index] == next) return
    gains = gains.toMutableList().also { it[index] = next }
    edited = true
  }
  val selectedPreset = if (edited) null else MRadioPresets.matching(if (custom) initial else null)
  AudioCard {
    Text("Sound style", style = MaterialTheme.typography.titleMedium)
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
      SoundMode.entries.forEach { mode ->
        FilterChip(selected = selectedMode == mode && !custom && !edited,
          enabled = enabled, onClick = { edited = false; onMode(mode) },
          label = { Text(mode.title) })
      }
    }
    Text("Presets", style = MaterialTheme.typography.titleMedium)
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
      MRadioPresets.names.forEach { name ->
        FilterChip(selected = selectedPreset == name,
          enabled = enabled, onClick = { onPreset(name) }, label = { Text(name) })
      }
    }
  }
  AudioCard {
    Row(verticalAlignment = Alignment.CenterVertically) {
      Column(Modifier.weight(1f)) {
        Text("Equalizer", style = MaterialTheme.typography.titleMedium)
        Text(if (custom && gains.all { it == 0f }) "Flat • default sound"
          else if (custom || edited) "Custom • saved for this output" else "Automatic for this output",
          style = MaterialTheme.typography.bodySmall)
      }
      TextButton(enabled = enabled && gains.any { it != 0f },
        onClick = { gains = List(count) { 0f }; edited = true }) {
        Icon(Icons.Rounded.RestartAlt, null)
        Spacer(Modifier.width(6.dp))
        Text("Flat")
      }
    }
    EqualizerCurve(gains, enabled, selected, onSelect = { selected = it }, onGain = ::setBand)
    val hz = GraphicEq.centres[selected]
    Row(verticalAlignment = Alignment.CenterVertically) {
      Column(Modifier.weight(1f)) {
        Text(bandLabel(hz), style = MaterialTheme.typography.titleSmall)
        Text(String.format(Locale.ROOT, "%+.1f dB", gains[selected]), style = MaterialTheme.typography.bodySmall)
      }
      FilledTonalIconButton(enabled = enabled && gains[selected] > -limit,
        onClick = { setBand(selected, gains[selected] - 0.5f) }) {
        Icon(Icons.Rounded.Remove, "Lower ${bandLabel(hz)} by 0.5 dB")
      }
      Spacer(Modifier.width(8.dp))
      FilledTonalIconButton(enabled = enabled && gains[selected] < limit,
        onClick = { setBand(selected, gains[selected] + 0.5f) }) {
        Icon(Icons.Rounded.Add, "Raise ${bandLabel(hz)} by 0.5 dB")
      }
    }
    Text("Drag a point to shape the sound • double-tap flattens a band • range ±${limit.roundToInt()} dB. " +
      "The line is the real response: the sliders and the sound match.", style = MaterialTheme.typography.bodySmall)
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
}
