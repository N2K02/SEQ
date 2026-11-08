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

private val factoryPresets = linkedMapOf(
  "Flat" to List(10) { 0f },
  "Smooth" to listOf(0f, 0f, 0.5f, 1f, 0.5f, 0f, -0.5f, -1f, -1.5f, -1.5f),
  "Rock" to listOf(3f, 3f, 2f, 0f, -1f, -1f, 1f, 2f, 3f, 3f),
  "Club" to listOf(4f, 4f, 2f, 0f, -1f, 0f, 1f, 2f, 2f, 1f),
  "Cinematic" to listOf(3f, 3f, 1f, -1f, 0f, 1f, 2f, 2f, 1f, 0f),
  "Acoustic" to listOf(0f, 1f, 1f, 0f, 0f, 1f, 2f, 2f, 1f, 0f)
)

@Composable
internal fun EqualizerScreen(initial: List<Float>, manualActive: Boolean,
  settings: EngineSettings, positivePreamp: Boolean, compareEnabled: Boolean,
  onCompare: (Boolean) -> Boolean, onControls: (EngineSettings) -> Unit,
  onApply: (List<Float>) -> Unit, onSave: (String, List<Float>) -> Unit) {
  val compactCount = SystemEqCurveBuilder.COMPACT_BAND_COUNT
  val precisionCount = SystemEqCurveBuilder.BAND_COUNT
  var count by rememberSaveable { mutableIntStateOf(compactCount) }
  var compact by rememberSaveable {
    mutableStateOf(ArrayList(if (initial.size == compactCount) initial else SystemEqCurveBuilder.resample(initial, compactCount)))
  }
  var precision by rememberSaveable {
    mutableStateOf(if (initial.size == precisionCount) ArrayList(initial) else null)
  }
  val bands = if (count == compactCount) compact else checkNotNull(precision)
  fun updateBands(next: List<Float>) {
    if (count == compactCount) compact = ArrayList(next) else precision = ArrayList(next)
  }
  var showSave by rememberSaveable { mutableStateOf(false) }
  var name by rememberSaveable { mutableStateOf("") }
  val limit = SystemEqCurveBuilder.MANUAL_LIMIT_DB
  AudioCard {
    Text("Equalizer bands", style = MaterialTheme.typography.titleLarge)
    Text("10 easy controls, with 31-band processing. Advanced editing remains available.", style = MaterialTheme.typography.bodySmall)
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
      listOf(compactCount, precisionCount).forEach { choice ->
        FilterChip(selected = count == choice, onClick = {
          if (choice == precisionCount && precision == null) {
            precision = ArrayList(SystemEqCurveBuilder.resample(bands, precisionCount))
          }
          count = choice
        }, label = { Text("$choice bands") }, modifier = Modifier.weight(1f))
      }
    }
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
      factoryPresets.forEach { (name, gains) ->
        val draft = SystemEqCurveBuilder.resample(gains, count)
        FilterChip(selected = draft == bands, onClick = { updateBands(draft) }, label = { Text(name) })
      }
    }
    val compactMode = count == compactCount
    val scrollState = rememberScrollState()
    val scroll = if (compactMode) Modifier else Modifier.horizontalScroll(scrollState)
    Text(if (compactMode) "Manual tuning" else "Manual tuning • Swipe for more bands",
      style = MaterialTheme.typography.bodySmall)
    val centres = SystemEqCurveBuilder.centres(count)
    Row(Modifier.fillMaxWidth().then(scroll)) {
      bands.forEachIndexed { i, gain ->
        val centre = centres[i]
        val label = when {
          centre < 1000 -> centre.roundToInt().toString()
          compactMode -> "${(centre / 1000).roundToInt()}k"
          else -> String.format(Locale.ROOT, "%.1fk", centre / 1000)
        }
        val column = if (compactMode) Modifier.weight(1f) else Modifier.width(52.dp)
        Column(column, horizontalAlignment = Alignment.CenterHorizontally) {
          Slider(value = gain, onValueChange = { level ->
            updateBands(bands.toMutableList().also { it[i] = (level * 2).roundToInt() / 2f })
          }, valueRange = -limit..limit, modifier = Modifier.width(48.dp).height(140.dp)
            .semantics { contentDescription = "${centre.roundToInt()} Hz gain" }
            .graphicsLayer { rotationZ = -90f }
            .layout { measurable, constraints ->
              val placeable = measurable.measure(Constraints.fixed(constraints.maxHeight, constraints.maxWidth))
              layout(constraints.maxWidth, constraints.maxHeight) {
                placeable.place((constraints.maxWidth - placeable.width) / 2,
                  (constraints.maxHeight - placeable.height) / 2)
              }
            })
          Text(label, style = MaterialTheme.typography.labelSmall)
          Text(String.format(Locale.ROOT, "%+.1f", gain), style = MaterialTheme.typography.labelSmall, maxLines = 1)
        }
      }
    }
    ResponseGraph(bands, "Manual equalizer response. Drag to adjust or tap to reset a band.") { index, gain ->
      updateBands(bands.toMutableList().also { it[index] = gain })
    }
    Text("Gain in dB • OEM EQ uses its available bands.", style = MaterialTheme.typography.bodySmall)
  }
  Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
    OutlinedButton(onClick = { updateBands(List(bands.size) { 0f }) },
      modifier = Modifier.weight(1f)) { Text("Reset") }
    FilledTonalButton(onClick = { showSave = true }, modifier = Modifier.weight(1.4f)) { Text("Save preset") }
  }
  Button(onClick = { onApply(bands.toList()) }, enabled = !manualActive || bands != initial, modifier = Modifier.fillMaxWidth()) {
    Text(if (manualActive && bands == initial) "Manual tuning selected" else "Apply manual tuning")
  }
  val compare by rememberUpdatedState(onCompare)
  var held by remember { mutableStateOf(false) }
  DisposableEffect(Unit) { onDispose { compare(false) } }
  if (compareEnabled) {
    Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.secondaryContainer,
      modifier = Modifier.fillMaxWidth().pointerInput(Unit) {
        detectTapGestures(onPress = {
          held = compare(true)
          try { tryAwaitRelease() } finally {
            held = false
            compare(false)
          }
        })
      }) {
      Box(Modifier.fillMaxWidth().padding(14.dp), contentAlignment = Alignment.Center) {
        Text(if (held) "A/B comparison" else "Hold for A/B comparison")
      }
    }
  }
  AudioCard {
    OutputSlider("Bass Boost", settings.bassBoost, 0f..1f, { "${(it * 100).roundToInt()}%" }) {
      onControls(settings.copy(bassBoost = it))
    }
    OutputSlider("Preamp", settings.preampDb.coerceAtMost(if (positivePreamp) 6f else 0f),
      -12f..(if (positivePreamp) 6f else 0f), { String.format(Locale.ROOT, "%+.1f dB", it) }) {
      onControls(settings.copy(preampDb = it))
    }
    if (!positivePreamp) Text("Positive gain requires an active peak limiter.",
      style = MaterialTheme.typography.bodySmall)
  }
  AudioCard {
    Row(verticalAlignment = Alignment.CenterVertically) {
      Column(Modifier.weight(1f)) {
        Text("Smart Dynamic Bass", style = MaterialTheme.typography.titleMedium)
        Text("Adds bass at lower volumes", style = MaterialTheme.typography.bodySmall)
      }
      Switch(checked = settings.dynamicBass, onCheckedChange = { onControls(settings.copy(dynamicBass = it)) })
    }
    if (settings.dynamicBass) {
      OutputSlider("Intensity", settings.bassIntensity, 0f..1f, { "${(it * 100).roundToInt()}%" }) {
        onControls(settings.copy(bassIntensity = it))
      }
    }
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
