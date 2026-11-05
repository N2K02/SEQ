package com.n2k02.seq

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import com.n2k02.seq.audio.LocalMusicLibrary
import com.n2k02.seq.audio.LocalTrack
import com.n2k02.seq.audio.SeqMediaLibraryService
import com.n2k02.seq.audio.SeqEngineService
import com.n2k02.seq.audio.SeqReferencePlayerService
import com.n2k02.seq.audio.SeqRuntime
import com.n2k02.seq.model.*
import com.n2k02.seq.adb.LocalAdbDiagnostics
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

private val accent = Color(0xff77dad6)
private val background = Color(0xff101316)
private val panel = Color(0xff202529)
private val quiet = Color(0xffb0b8bf)
private enum class Page(val title: String) {
  HOME("Home"), SETTINGS("Settings"), EQ("Equalizer"), PLAYER("Music Library"), DEVICES("Device Profiles"),
  AUTO("Android Auto"), AI("AI Assistant"), DIAGNOSTICS("Diagnostics"),
  LOGS("Logs"), UPDATES("Updates"), ABOUT("About")
}

class MainActivity : ComponentActivity() {
  private lateinit var store: SettingsStore
  private lateinit var adb: LocalAdbDiagnostics
  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    store = SettingsStore(this)
    adb = LocalAdbDiagnostics(applicationContext)
    setContent {
      var settings by remember { mutableStateOf(store.read()) }
      var page by remember { mutableStateOf(Page.HOME) }
      val status by SeqRuntime.status.collectAsState()
      val playerStatus by SeqRuntime.player.collectAsState()
      val mediaStatus by SeqRuntime.media.collectAsState()
      val musicStore = remember { LocalMusicLibrary(this@MainActivity) }
      val headphoneStore = remember { HeadphoneProfileStore(this@MainActivity) }
      var music by remember { mutableStateOf(musicStore.items()) }
      var headphones by remember { mutableStateOf(headphoneStore.read()) }
      var importMessage by remember { mutableStateOf("") }
      val pickMusic = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { files ->
        if(files.isNotEmpty()) {
          runCatching {
            files.forEach { uri ->
              contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            music = musicStore.add(files)
            importMessage = "Music added: " + files.size
          }.onFailure { importMessage = "Import failed: " + (it.message ?: it.javaClass.simpleName) }
        }
      }
      val pickCorrection = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if(uri!=null) {
          runCatching {
            val name=contentResolver.query(uri,arrayOf(OpenableColumns.DISPLAY_NAME),null,null,null)?.use { c ->
              if(c.moveToFirst()) c.getString(0) else null
            } ?: "Headphone preset"
            val raw=contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
              ?: error("Cannot read preset")
            val p=AutoEqImport.parse(name,raw)
            headphoneStore.save(p)
            headphones=p
            val next=settings.copy(headphoneCorrection=true)
            settings=next
            store.write(next)
            if(next.enabled) startService(SeqEngineService.intent(this@MainActivity,SeqEngineService.REFRESH))
            importMessage="Correction imported: " + p.name
          }.onFailure { importMessage="Import failed: " + (it.message ?: it.javaClass.simpleName) }
        }
      }
      val pickWav = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
          runCatching { contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
          runCatching {
            val start = SeqReferencePlayerService.intent(this, SeqReferencePlayerService.PLAY).setData(uri)
            ContextCompat.startForegroundService(this, start)
          }.onFailure { SeqRuntime.player.value = "Failed: " + it.javaClass.simpleName }
        }
      }
      val scope = rememberCoroutineScope()
      fun update(next: EngineSettings) {
        settings = next
        store.write(next)
        if (!next.enabled) stopService(SeqEngineService.intent(this, SeqEngineService.STOP))
        else {
          val command = if (status.running) SeqEngineService.REFRESH else SeqEngineService.START
          runCatching { if (status.running) startService(SeqEngineService.intent(this, command)) else ContextCompat.startForegroundService(this, SeqEngineService.intent(this, command)) }
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
                adb.shutdown()
                stopService(SeqReferencePlayerService.intent(this@MainActivity, SeqReferencePlayerService.STOP))
                stopService(SeqMediaLibraryService.intent(this@MainActivity, SeqMediaLibraryService.STOP))
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
                MenuRow("Music Library", "Lossless player • Android Auto", Icons.Rounded.PlayCircle) { page = Page.PLAYER }
                MenuRow("Android Auto", "Car profile and compatibility", Icons.Rounded.DirectionsCar) { page = Page.AUTO }
                MenuRow("AI Assistant", if (settings.aiEnabled) "On-device • optional" else "Optional • Off", Icons.Rounded.Memory) { page = Page.AI }
                HorizontalDivider(Modifier.padding(vertical = 10.dp), color = quiet.copy(alpha = 0.25f))
                MenuRow("Diagnostics", "Direct ADB • Audio sessions", Icons.Rounded.MonitorHeart) { page = Page.DIAGNOSTICS }
                MenuRow("Logs", "Recent engine events", Icons.Rounded.Subject) { page = Page.LOGS }
                MenuRow("Updates", "Build information", Icons.Rounded.SystemUpdate) { page = Page.UPDATES }
                MenuRow("About", "SEQ sound principles", Icons.Rounded.Info) { page = Page.ABOUT }
              }
              Page.EQ -> {
                Text("Adjustments stay small to preserve the recording.", color = quiet)
                if (settings.profile == AudioProfile.PURE) Text("Pure bypass ignores EQ values.", color = accent)
                ToneControls(settings.customTone) { update(settings.copy(customTone = it)) }
                HorizontalDivider(color = quiet.copy(alpha = .2f))
                Text("Headphone correction", fontWeight = FontWeight.SemiBold)
                Text("Import AutoEq / Equalizer APO measured parametric preset. No large bundled database.",color=quiet,fontSize=12.sp)
                ToggleRow("Apply headphone correction",settings.headphoneCorrection) {
                  update(settings.copy(headphoneCorrection = it))
                }
                Text(headphones?.let { it.name + " • " + it.filters.size + " filters" } ?: "No imported correction",color=accent)
                Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                  OutlinedButton(onClick={ pickCorrection.launch(arrayOf("text/plain","*/*")) }) {
                    Text("Import preset")
                  }
                  OutlinedButton(onClick={
                    headphoneStore.clear(); headphones=null
                    update(settings.copy(headphoneCorrection=false))
                  }) { Text("Clear") }
                }
                Text("Native playback applies individual filters; external app EQ approximates correction using OEM bands.",color=quiet,fontSize=12.sp)
                if(importMessage.isNotBlank()) Text(importMessage,color=quiet,fontSize=12.sp)
                Text("Android-compatible fixed-band EQ. Native precise DSP is not injected into other apps.",
                  color = quiet, fontSize = 12.sp)
                TextButton(onClick = { update(settings.copy(customTone = Tone())) }) { Text("Reset to neutral") }
              }
              Page.PLAYER -> {
                Text("Native SEQ DSP playback and Android Auto music browsing.",color=quiet)
                Text("Local files • FLAC, WAV, AAC, MP3, Ogg and device-supported codecs.",color=quiet,fontSize=12.sp)
                Text("Playback: "+mediaStatus,color=accent)
                Button(onClick={pickMusic.launch(arrayOf("audio/*","application/ogg"))}) {
                  Text("Add music files")
                }
                Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                  OutlinedButton(onClick={
                    startService(SeqMediaLibraryService.intent(this@MainActivity,SeqMediaLibraryService.PAUSE))
                  }) { Text("Pause") }
                  OutlinedButton(onClick={
                    startService(SeqMediaLibraryService.intent(this@MainActivity,SeqMediaLibraryService.RESUME))
                  }) { Text("Resume") }
                  OutlinedButton(onClick={
                    stopService(SeqMediaLibraryService.intent(this@MainActivity,SeqMediaLibraryService.STOP))
                  }) { Text("Stop") }
                }
                if(music.isEmpty()) Text("Choose files to populate your Android Auto music library.",color=quiet)
                else {
                  Text("Library • "+music.size+" tracks",fontWeight=FontWeight.SemiBold)
                  music.forEach { track ->
                    MenuRow(track.title,"Tap to play with SEQ's PCM processing",Icons.Rounded.MusicNote) {
                      stopService(SeqReferencePlayerService.intent(this@MainActivity,SeqReferencePlayerService.STOP))
                      startService(SeqMediaLibraryService.intent(this@MainActivity,SeqMediaLibraryService.PLAY).setData(track.uri))
                    }
                  }
                }
                if(importMessage.isNotBlank()) Text(importMessage,color=quiet,fontSize=12.sp)
                HorizontalDivider(color=quiet.copy(alpha=.2f))
                Text("Reference WAV player",fontWeight=FontWeight.SemiBold)
                Text("Direct AudioTrack + C++ DSP comparison path for stereo PCM16 WAV.",color=quiet,fontSize=12.sp)
                OutlinedButton(onClick={pickWav.launch(arrayOf("audio/wav","audio/x-wav"))}) {
                  Text("Open reference WAV")
                }
                Text("Reference: "+playerStatus,color=quiet,fontSize=12.sp)
                OutlinedButton(onClick={
                  stopService(SeqReferencePlayerService.intent(this@MainActivity,SeqReferencePlayerService.STOP))
                }) { Text("Stop reference playback") }
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
                Text("SEQ now exposes a real local music library to compatible Android Auto media browsers. Add files under Music Library before connecting. Third-party EQ remains session dependent.", color = quiet)
                ToggleRow("Automatic car profile", settings.automaticCarProfile) { update(settings.copy(automaticCarProfile = it)) }
                ToggleRow("Car profile manually selected", settings.carManual) { update(settings.copy(carManual = it)) }
                Text("Android Auto displays the car-safe music library; no EQ controls are exposed while driving. Car profile selection is explicit or by matching a known connected Bluetooth device.", color = quiet, fontSize = 12.sp)
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
                Text("Local media: "+mediaStatus)
                Text("Attached sessions: " + status.sessions)
                Text("Output: " + status.route)
                Text("Last event: " + status.lastEvent, color = quiet)
                HorizontalDivider(color = quiet.copy(alpha = .2f))
                val adbStatus by adb.state.collectAsState()
                Text("Direct ADB (optional)", fontWeight = FontWeight.SemiBold)
                Text(adbStatus.message, color = quiet)
                var connectPort by remember { mutableStateOf("5555") }
                OutlinedTextField(value = connectPort, onValueChange = {
                  connectPort = it.filter { char -> char.isDigit() }.take(5)
                }, label = { Text("Local ADB connection port") }, modifier = Modifier.fillMaxWidth(),
                  singleLine = true)
                Button(enabled = !adbStatus.busy, onClick = { adb.inspect(connectPort) }) {
                  Text("Verify shell and inspect audio")
                }
                Text("When VRec already enabled TCP/5555, use port 5555. SEQ uses its own separately authorized key.",
                  color = quiet, fontSize = 12.sp)
                HorizontalDivider(color = quiet.copy(alpha = .2f))
                var pairingPort by remember { mutableStateOf("") }
                var pairingCode by remember { mutableStateOf("") }
                Text("First-time wireless pairing", fontWeight = FontWeight.SemiBold)
                OutlinedTextField(value = pairingPort, onValueChange = {
                  pairingPort = it.filter { char -> char.isDigit() }.take(5)
                }, label = { Text("Pairing port from Android Settings") },
                  modifier = Modifier.fillMaxWidth(), singleLine = true)
                OutlinedTextField(value = pairingCode, onValueChange = {
                  pairingCode = it.filter { char -> char.isDigit() }.take(6)
                }, label = { Text("Six-digit pairing code") },
                  modifier = Modifier.fillMaxWidth(), singleLine = true)
                OutlinedButton(enabled = !adbStatus.busy && pairingCode.length == 6,
                  onClick = {
                    val code = pairingCode
                    pairingCode = ""
                    adb.pair(pairingPort, code)
                  }) { Text("Pair with Android") }
                Text("Enable Wireless debugging → Pair device with pairing code. After pairing, use the separate main connection port (shown on the Wireless debugging page).",
                  color = quiet, fontSize = 12.sp)
                if (adbStatus.details.isNotBlank()) SelectionText(adbStatus.details)
                Text("No Shizuku, root, continuous ADB connection, TCP mode changes or microphone access.",
                  color = quiet, fontSize = 12.sp)
              }
              Page.LOGS -> {
                Text("In-memory events; no automatic disk logging.", color = quiet)
                SelectionText(SeqRuntime.logs().joinToString("\n").ifBlank { "No events yet." })
                OutlinedButton(onClick = { SeqRuntime.clear() }) { Text("Clear logs") }
              }
              Page.UPDATES -> {
                Text("SEQ experimental 0.2.0", fontWeight = FontWeight.SemiBold)
                Text("Experimental APKs are available from GitHub Actions once CI passes.", color = quiet)
              }
              Page.ABOUT -> {
                Text("SEQ • high-fidelity sound", fontSize = 23.sp, fontWeight = FontWeight.Bold)
                Text("Accuracy over loudness. Avoid unnecessary compression and processing.", color = quiet)
                Text("External app effects are cooperative. ADB is diagnostics-only. No automatic DSP on unrelated or protected audio.", color = quiet)
              }
            }
          }
        }
      }
    }
  }
  override fun onDestroy() {
    adb.shutdown()
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
  var localGain by remember(gain) { mutableFloatStateOf(gain) }
  Slider(value = localGain, onValueChange = { localGain = (it * 2).toInt() / 2f },
    onValueChangeFinished = { update(localGain) },
    valueRange = -6f..6f, steps = 23, modifier = Modifier.fillMaxWidth())
}
@Composable private fun SelectionText(value: String) {
  Surface(shape = RoundedCornerShape(10.dp), color = panel) {
    Text(value, modifier = Modifier.fillMaxWidth().padding(12.dp), fontSize = 12.sp, color = quiet)
  }
}
