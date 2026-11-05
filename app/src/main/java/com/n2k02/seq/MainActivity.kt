package com.n2k02.seq

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.n2k02.seq.ai.OnDeviceTuner
import com.n2k02.seq.ai.TuningSuggestion
import com.n2k02.seq.audio.SeqEngineService
import com.n2k02.seq.audio.SeqRuntime
import com.n2k02.seq.model.*
import com.n2k02.seq.shizuku.ShizukuBridge
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

private val accent = Color(0xff77dad6)
private val background = Color(0xff101316)
private val panel = Color(0xff202529)
private val quiet = Color(0xffb0b8bf)
private enum class Page(val title: String) {
  HOME("Home"), SETTINGS("Settings"), EQ("Equalizer"), DEVICES("Device Profiles"),
  AUTO("Android Auto"), AI("AI Assistant"), DIAGNOSTICS("Diagnostics"),
  LOGS("Logs"), UPDATES("Updates"), ABOUT("About")
}

class MainActivity : ComponentActivity() {
  private lateinit var store: SettingsStore
  private lateinit var shizuku: ShizukuBridge
  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    store = SettingsStore(this)
    shizuku = ShizukuBridge(this)
    setContent {
      var settings by remember { mutableStateOf(store.read()) }
      var page by remember { mutableStateOf(Page.HOME) }
      val status by SeqRuntime.status.collectAsState()
      val scope = rememberCoroutineScope()
      fun update(next: EngineSettings) {
        settings = next
        store.write(next)
        if (!next.enabled) stopService(SeqEngineService.intent(this, SeqEngineService.STOP))
        else {
          val command = if (status.running) SeqEngineService.REFRESH else SeqEngineService.START
          runCatching { ContextCompat.startForegroundService(this, SeqEngineService.intent(this, command)) }
            .onFailure { SeqRuntime.log("Start failed: " + it.javaClass.simpleName) }
        }
      }
      MaterialTheme(colorScheme = darkColorScheme(
        primary = accent, secondary = accent, background = background,
        surface = panel, onBackground = Color.White, onSurface = Color.White
      )) {
        Scaffold(containerColor = background,
          topBar = { Header(page.title, if (page == Page.HOME) Icons.Rounded.Home else Icons.Rounded.Settings) },
          bottomBar = {
            NavigationBar(containerColor = Color(0xff171b1e)) {
              NavigationBarItem(selected = page == Page.HOME, onClick = { page = Page.HOME },
                icon = { Icon(Icons.Rounded.Home, null) }, label = { Text("Home") })
              NavigationBarItem(selected = page != Page.HOME, onClick = { page = Page.SETTINGS },
                icon = { Icon(Icons.Rounded.Settings, null) }, label = { Text("Settings") })
              NavigationBarItem(selected = false, onClick = {
                update(settings.copy(enabled = false))
                shizuku.close()
                finishAffinity()
              }, icon = { Icon(Icons.Rounded.PowerSettingsNew, null) }, label = { Text("Quit") })
            }
          }
        ) { padding ->
          Column(Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            when (page) {
              Page.HOME -> {
                SurfaceCard {
                  Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.weight(1f)) {
                      Text("Audio Engine", fontWeight = FontWeight.Bold, fontSize = 18.sp)
                      Text(if (status.effectAttached) "● Effect attached" else if (status.running) "Waiting for compatible player" else "Off",
                        color = if (status.effectAttached) accent else quiet)
                    }
                    Switch(checked = settings.enabled, onCheckedChange = {
                      if (it && Build.VERSION.SDK_INT >= 33 &&
                        checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
                        requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 109)
                      update(settings.copy(enabled = it))
                    })
                  }
                }
                SurfaceCard {
                  Text("Listening Profile", color = quiet, fontSize = 13.sp)
                  Spacer(Modifier.height(6.dp))
                  Text(settings.profile.title, fontWeight = FontWeight.Bold, fontSize = 25.sp)
                  Text(settings.profile.subtitle, color = quiet, fontSize = 13.sp)
                  Spacer(Modifier.height(12.dp))
                  Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                    AudioProfile.entries.forEach { p ->
                      FilterChip(selected = settings.profile == p,
                        onClick = { update(settings.copy(profile = p)) },
                        label = { Text(p.name.lowercase().replaceFirstChar { it.uppercase() }, fontSize = 12.sp) },
                        modifier = Modifier.weight(1f))
                    }
                  }
                }
                SurfaceCard {
                  Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Rounded.Headphones, null, tint = accent)
                    Spacer(Modifier.width(12.dp))
                    Column {
                      Text("Output Device", color = quiet, fontSize = 12.sp)
                      Text(status.route, fontWeight = FontWeight.SemiBold)
                      Text(if (status.carProfileActive) "Car profile selected" else "Default device profile",
                        color = quiet, fontSize = 12.sp)
                    }
                  }
                }
                SurfaceCard {
                  Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Rounded.GraphicEq, null)
                    Spacer(Modifier.width(12.dp))
                    Column {
                      Text("Signal Path", fontWeight = FontWeight.SemiBold)
                      Text(if (status.effectAttached) "Platform EQ • " + status.sessions + " session(s)"
                        else if (settings.profile == AudioProfile.PURE) "Pure bypass"
                        else "No verified effect attachment", color = quiet, fontSize = 12.sp)
                    }
                  }
                }
              }
              Page.SETTINGS -> {
                MenuRow("Equalizer", "Precision tone controls", Icons.Rounded.Tune) { page = Page.EQ }
                MenuRow("Device Profiles", "Headphones and output routes", Icons.Rounded.Headphones) { page = Page.DEVICES }
                MenuRow("Android Auto", "Car profile and compatibility", Icons.Rounded.DirectionsCar) { page = Page.AUTO }
                MenuRow("AI Assistant", if (settings.aiEnabled) "On-device • optional" else "Optional • Off", Icons.Rounded.Memory) { page = Page.AI }
                HorizontalDivider(Modifier.padding(vertical = 10.dp), color = quiet.copy(alpha = 0.25f))
                MenuRow("Diagnostics", "Shizuku • Audio sessions", Icons.Rounded.MonitorHeart) { page = Page.DIAGNOSTICS }
                MenuRow("Logs", "Recent engine events", Icons.Rounded.Subject) { page = Page.LOGS }
                MenuRow("Updates", "Build information", Icons.Rounded.SystemUpdate) { page = Page.UPDATES }
                MenuRow("About", "SEQ sound principles", Icons.Rounded.Info) { page = Page.ABOUT }
              }
              Page.EQ -> {
                Text("Adjustments stay small to preserve the recording.", color = quiet)
                if (settings.profile == AudioProfile.PURE) Text("Pure bypass ignores EQ values.", color = accent)
                ToneControls(settings.customTone) { update(settings.copy(customTone = it)) }
                Text("Android-compatible fixed-band EQ. Native 12-filter reference DSP is not injected into other apps.",
                  color = quiet, fontSize = 12.sp)
                TextButton(onClick = { update(settings.copy(customTone = Tone())) }) { Text("Reset to neutral") }
              }
              Page.DEVICES -> {
                Text("Profiles are stored locally; system EQ support depends on the source music app.", color = quiet)
                MenuRow("Audio route", status.route, Icons.Rounded.Headphones, onClick = {})
                OutlinedTextField(value = settings.carDeviceName, onValueChange = {
                  update(settings.copy(carDeviceName = it.take(100))) },
                  label = { Text("Car Bluetooth device name (exact match)") }, modifier = Modifier.fillMaxWidth())
                Text("Only a match among connected outputs is detected, not a verified playback route.", color = quiet, fontSize = 12.sp)
              }
              Page.AUTO -> {
                Text("SEQ can process compatible music sessions while connected to Android Auto. It does not appear as a separate Android Auto media player.", color = quiet)
                ToggleRow("Automatic car profile", settings.automaticCarProfile) { update(settings.copy(automaticCarProfile = it)) }
                ToggleRow("Car profile manually selected", settings.carManual) { update(settings.copy(carManual = it)) }
                Text("To use automatic switching, enter the exact Bluetooth device name under Device Profiles.", color = quiet, fontSize = 12.sp)
                Text("Car EQ adjustment", fontWeight = FontWeight.SemiBold)
                ToneControls(settings.carTone) { update(settings.copy(carTone = it)) }
              }
              Page.AI -> {
                ToggleRow("Enable on-device assistant", settings.aiEnabled) { update(settings.copy(aiEnabled = it)) }
                Text("Uses Android AICore's installed Gemini Nano when available. Runs only on request and never listens to music.", color = quiet, fontSize = 13.sp)
                if (settings.aiEnabled) {
                  var prompt by remember { mutableStateOf("") }
                  var aiMessage by remember { mutableStateOf("") }
                  var proposal by remember { mutableStateOf<TuningSuggestion?>(null) }
                  var busy by remember { mutableStateOf(false) }
                  OutlinedTextField(value = prompt, onValueChange = { prompt = it.take(300) },
                    label = { Text("Describe desired tuning") }, modifier = Modifier.fillMaxWidth(), minLines = 2)
                  Button(enabled = !busy && prompt.isNotBlank(), onClick = {
                    busy = true
                    proposal = null
                    scope.launch {
                      val result = withContext(Dispatchers.Default) { OnDeviceTuner.suggest(prompt, settings.customTone) }
                      proposal = result.getOrNull()
                      aiMessage = result.fold({ it.explanation }, { it.message ?: "AI unavailable" })
                      busy = false
                    }
                  }) { Text(if (busy) "Analysing…" else "Suggest EQ adjustment") }
                  if (aiMessage.isNotBlank()) Text(aiMessage, color = quiet)
                  proposal?.let { p ->
                    Button(onClick = { update(settings.copy(customTone = p.tone)); proposal = null }) {
                      Text("Apply suggested adjustment")
                    }
                  }
                }
              }
              Page.DIAGNOSTICS -> {
                Text("Live SEQ state", fontWeight = FontWeight.SemiBold)
                Text("Service: " + if (status.running) "Running" else "Stopped")
                Text("Attached sessions: " + status.sessions)
                Text("Output: " + status.route)
                Text("Last event: " + status.lastEvent, color = quiet)
                HorizontalDivider(color = quiet.copy(alpha = .2f))
                val shizukuStatus by shizuku.state.collectAsState()
                Text("Shizuku (optional)", fontWeight = FontWeight.SemiBold)
                Text(shizukuStatus, color = quiet)
                Button(onClick = { shizuku.connect() }) { Text("Connect / grant Shizuku") }
                var snapshot by remember { mutableStateOf("") }
                OutlinedButton(onClick = {
                  scope.launch { snapshot = withContext(Dispatchers.IO) { shizuku.snapshot() } }
                }) { Text("Read audio routing diagnostics") }
                if (snapshot.isNotBlank()) SelectionText(snapshot)
                Text("Only limited output routing diagnostics are requested. No microphone, audio capture, or root access.", color = quiet, fontSize = 12.sp)
              }
              Page.LOGS -> {
                Text("In-memory events; no automatic disk logging.", color = quiet)
                SelectionText(SeqRuntime.logs().joinToString("\n").ifBlank { "No events yet." })
                OutlinedButton(onClick = { SeqRuntime.clear() }) { Text("Clear logs") }
              }
              Page.UPDATES -> {
                Text("SEQ experimental 0.1.0", fontWeight = FontWeight.SemiBold)
                Text("Updates are not automatic in this experimental build.", color = quiet)
              }
              Page.ABOUT -> {
                Text("SEQ • high-fidelity sound", fontSize = 23.sp, fontWeight = FontWeight.Bold)
                Text("Accuracy over loudness. Avoid unnecessary compression and processing.", color = quiet)
                Text("External app effects are cooperative. Shizuku is diagnostics-only. No automatic DSP on unrelated or protected audio.", color = quiet)
              }
            }
          }
        }
      }
    }
  }
  override fun onDestroy() {
    shizuku.close()
    super.onDestroy()
  }
}
@Composable private fun Header(title: String, icon: ImageVector) {
  Row(Modifier.fillMaxWidth().background(background).padding(horizontal = 18.dp, vertical = 16.dp),
    verticalAlignment = Alignment.CenterVertically) {
    Icon(icon, null, tint = Color.White)
    Spacer(Modifier.width(10.dp))
    Text(title, fontSize = 22.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
    Icon(Icons.Rounded.GraphicEq, null, tint = accent)
    Spacer(Modifier.width(6.dp))
    Text("SEQ", fontSize = 19.sp, fontWeight = FontWeight.Bold)
  }
}
@Composable private fun SurfaceCard(content: @Composable ColumnScope.() -> Unit) {
  Surface(shape = RoundedCornerShape(17.dp), color = panel, modifier = Modifier.fillMaxWidth()) {
    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp), content = content)
  }
}
@Composable private fun MenuRow(title: String, subtitle: String, icon: ImageVector, onClick: () -> Unit) {
  Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 15.dp),
    verticalAlignment = Alignment.CenterVertically) {
    Icon(icon, null, tint = Color.White)
    Spacer(Modifier.width(16.dp))
    Column(Modifier.weight(1f)) {
      Text(title, fontWeight = FontWeight.Medium)
      Text(subtitle, color = quiet, fontSize = 12.sp, maxLines = 2)
    }
    Icon(Icons.Rounded.ChevronRight, null, tint = quiet)
  }
}
@Composable private fun ToggleRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
  Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
    Text(label, Modifier.weight(1f))
    Switch(checked = checked, onCheckedChange = onChange)
  }
}
@Composable private fun ToneControls(tone: Tone, update: (Tone) -> Unit) {
  Column {
    GainSlider("Bass", tone.bass) { update(tone.copy(bass = it)) }
    GainSlider("Mid", tone.mid) { update(tone.copy(mid = it)) }
    GainSlider("Treble", tone.treble) { update(tone.copy(treble = it)) }
  }
}
@Composable private fun GainSlider(label: String, gain: Float, update: (Float) -> Unit) {
  Row(verticalAlignment = Alignment.CenterVertically) {
    Text(label, modifier = Modifier.weight(1f), color = quiet)
    Text(String.format(Locale.US, "%+.1f dB", gain), color = accent)
  }
  Slider(value = gain, onValueChange = { update((it * 2).toInt() / 2f) },
    valueRange = -6f..6f, steps = 23, modifier = Modifier.fillMaxWidth())
}
@Composable private fun SelectionText(value: String) {
  Surface(shape = RoundedCornerShape(10.dp), color = panel) {
    Text(value, modifier = Modifier.fillMaxWidth().padding(12.dp), fontSize = 12.sp, color = quiet)
  }
}
