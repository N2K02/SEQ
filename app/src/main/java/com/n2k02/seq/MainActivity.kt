package com.n2k02.seq

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import dev.n2k02.theme.N2K02Theme
import dev.n2k02.updater.N2K02UpdateBadge
import dev.n2k02.updater.N2K02UpdateBadgedIcon
import dev.n2k02.updater.N2K02UpdateScreen
import dev.n2k02.updater.hasPendingUpdate
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
import androidx.compose.ui.res.painterResource
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
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
import com.n2k02.seq.adb.SeqPairingAccessibilityService
import com.n2k02.seq.adb.SeqAdbRecoveryWorker
import com.n2k02.seq.SeqUpdater
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale
import java.text.SimpleDateFormat
import java.util.Date

// Shared mRadio-style Material 3 Expressive palette: dynamic color and motion.
private val accent: Color @Composable get() = MaterialTheme.colorScheme.primary
private val background: Color @Composable get() = MaterialTheme.colorScheme.background
private val panel: Color @Composable get() = MaterialTheme.colorScheme.surfaceContainerLow
private val quiet: Color @Composable get() = MaterialTheme.colorScheme.onSurfaceVariant
private enum class Page(val title: String) {
  HOME("Home"), SETTINGS("Settings"), EQ("Equalizer"), FIDELITY("Advanced Audio"), PLAYER("Music Library"), DEVICES("Device Profiles"),
  AUTO("Android Auto"), AI("AI Assistant"), DIAGNOSTICS("Diagnostics"),
  UPDATES("Updates")
}

private fun pageIcon(page:Page):ImageVector = when(page) {
  Page.HOME -> Icons.Rounded.Home
  Page.SETTINGS -> Icons.Rounded.Settings
  Page.EQ -> Icons.Rounded.Tune
  Page.FIDELITY -> Icons.Rounded.GraphicEq
  Page.PLAYER -> Icons.Rounded.MusicNote
  Page.DEVICES -> Icons.Rounded.Headphones
  Page.AUTO -> Icons.Rounded.DirectionsCar
  Page.AI -> Icons.Rounded.Memory
  Page.DIAGNOSTICS -> Icons.Rounded.MonitorHeart
  Page.UPDATES -> Icons.Rounded.SystemUpdate
}

class MainActivity : ComponentActivity() {
  private lateinit var store: SettingsStore
  private lateinit var adb: LocalAdbDiagnostics
  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    store = SettingsStore(this)
    adb = LocalAdbDiagnostics(applicationContext)
    SeqAdbRecoveryWorker.reconcile(applicationContext)
    SeqUpdater.init(applicationContext)
    setContent {
      var settings by remember { mutableStateOf(store.read()) }
      var page by remember { mutableStateOf(Page.HOME) }
      BackHandler(enabled=page != Page.HOME && page != Page.SETTINGS) { page=Page.SETTINGS }
      DisposableEffect(page) {
        if(page==Page.DIAGNOSTICS) adb.enterPage()
        onDispose { if(page==Page.DIAGNOSTICS) adb.leavePage() }
      }
      val updateStatus by SeqUpdater.controller.status.collectAsState()
      val experimentalEnabled by SeqUpdater.experimentalEnabled.collectAsState()
      val status by SeqRuntime.status.collectAsState()
      val playerStatus by SeqRuntime.player.collectAsState()
      val mediaStatus by SeqRuntime.media.collectAsState()
      val nativeStatus by SeqRuntime.mediaDsp.collectAsState()
      val musicStore = remember { LocalMusicLibrary(this@MainActivity) }
      val headphoneStore = remember { HeadphoneProfileStore(this@MainActivity) }
      val impulseStore = remember { MeasuredImpulseStore(this@MainActivity) }
      var impulse by remember { mutableStateOf(impulseStore.read()) }
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
      val pickImpulse = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if(uri!=null) {
          runCatching {
            val name=contentResolver.query(uri,arrayOf(OpenableColumns.DISPLAY_NAME),null,null,null)?.use { c ->
              if(c.moveToFirst()) c.getString(0) else null
            } ?: "Measured impulse response"
            val response=contentResolver.openInputStream(uri)?.use { stream ->
              MeasuredImpulseImport.parse(name,stream)
            } ?: error("Unable to read impulse response")
            impulseStore.save(response)
            impulse=response
            importMessage="FIR imported: "+response.name
          }.onFailure { importMessage="IR import failed: "+(it.message?:it.javaClass.simpleName) }
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
      N2K02Theme {
        Scaffold(containerColor = background,
          topBar = { Header(page.title, pageIcon(page)) },
          bottomBar = {
            NavigationBar(containerColor = MaterialTheme.colorScheme.surface, tonalElevation = 0.dp) {
              NavigationBarItem(selected = page == Page.HOME, onClick = { page = Page.HOME },
                icon = { Icon(Icons.Rounded.Home, null) }, label = { Text("Home") })
              NavigationBarItem(selected = page != Page.HOME, onClick = { page = Page.SETTINGS },
                icon = {
                  N2K02UpdateBadgedIcon(updateStatus) {
                    Icon(Icons.Rounded.Settings, contentDescription = "Settings")
                  }
                }, label = { Text("Settings") })
              NavigationBarItem(selected = false, onClick = {
                update(settings.copy(enabled = false))
                adb.shutdown()
                SeqUpdater.controller.cancel()
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
                      Text(if(nativeStatus=="Native EQ active") "SEQ native processing • active"
                        else if(status.effectAttached) "Platform EQ • "+status.sessions+" session(s)"
                        else if(settings.profile==AudioProfile.PURE) "Pure bypass"
                        else "No external session effect attached", color=quiet, fontSize=12.sp)
                    }
                  }
                }
              }
              Page.SETTINGS -> {
                MenuRow("Equalizer", "Precision tone controls", Icons.Rounded.Tune) { page = Page.EQ }
                MenuRow("Advanced Audio", "FIR • peak guard • USB direct", Icons.Rounded.GraphicEq) { page = Page.FIDELITY }
                MenuRow("Device Profiles", "Headphones and output routes", Icons.Rounded.Headphones) { page = Page.DEVICES }
                MenuRow("Music Library", "Lossless player • Android Auto", Icons.Rounded.PlayCircle) { page = Page.PLAYER }
                MenuRow("Android Auto", "Car profile and compatibility", Icons.Rounded.DirectionsCar) { page = Page.AUTO }
                MenuRow("AI Assistant", if (settings.aiEnabled) "On-device • optional" else "Optional • Off", Icons.Rounded.Memory) { page = Page.AI }
                HorizontalDivider(Modifier.padding(vertical = 10.dp), color = quiet.copy(alpha = 0.25f))
                MenuRow("Diagnostics", "Direct ADB • Audio sessions", Icons.Rounded.MonitorHeart) { page = Page.DIAGNOSTICS }
                MenuRow("Updates", if(updateStatus.hasPendingUpdate()) "New update available" else "Version and update checks",
                  Icons.Rounded.SystemUpdate, showUpdateBadge=true, updateStatus=updateStatus) { page = Page.UPDATES }
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
                headphones?.let { current ->
                  AdvancedFilters(current) { edited ->
                    headphoneStore.save(edited)
                    headphones=edited
                    update(settings.copy(headphoneCorrection=true))
                  }
                }
                Text("Android-compatible fixed-band EQ. Native precise DSP is not injected into other apps.",
                  color = quiet, fontSize = 12.sp)
                TextButton(onClick = { update(settings.copy(customTone = Tone())) }) { Text("Reset to neutral") }
              }
              Page.PLAYER -> {
                Text("Native SEQ DSP playback and Android Auto music browsing.",color=quiet)
                Text("Local files • FLAC, WAV, AAC, MP3, Ogg and device-supported codecs.",color=quiet,fontSize=12.sp)
                Text("Playback: "+mediaStatus,color=accent)
                Text("Audio path: "+nativeStatus,color=quiet,fontSize=12.sp)
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
              Page.FIDELITY -> {
                Text("Optional measured processing",style=MaterialTheme.typography.titleMedium)
                Text("Reference remains neutral. Enable only the processing your equipment needs.",
                  color=quiet,style=MaterialTheme.typography.bodySmall)
                SurfaceCard {
                  ToggleRow("4× intersample peak guard",settings.peakGuard) {
                    update(settings.copy(peakGuard=it))
                  }
                  Text("Experimental interpolated peak control on SEQ-owned PCM. Not a certified ITU true-peak limiter.",
                    color=quiet,style=MaterialTheme.typography.bodySmall)
                  if(settings.peakGuard) {
                    Text("Ceiling: "+String.format(Locale.US,"%.1f dB",settings.peakCeilingDb))
                    Slider(value=settings.peakCeilingDb,valueRange=-6f..-0.5f,
                      onValueChange={ update(settings.copy(peakCeilingDb=it)) })
                  }
                }
                SurfaceCard {
                  ToggleRow("Measured FIR convolution",settings.convolution) {
                    update(settings.copy(convolution=it))
                  }
                  Text(impulse?.let { it.name+" • "+it.sampleRate+" Hz • "+it.left.size+" taps" }
                    ?: "No measured response imported",color=quiet,style=MaterialTheme.typography.bodySmall)
                  Text("Import a measured stereo 16-bit PCM WAV response, 256 taps maximum. Sample rate must match the source; CPU usage rises when enabled.",
                    color=quiet,style=MaterialTheme.typography.bodySmall)
                  Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick={pickImpulse.launch(arrayOf("audio/wav","audio/x-wav","audio/*"))}) {
                      Text("Import IR")
                    }
                    OutlinedButton(onClick={impulseStore.clear();impulse=null
                      update(settings.copy(convolution=false))}) { Text("Clear") }
                  }
                }
                SurfaceCard {
                  ToggleRow("USB direct PCM16 WAV",settings.usbDirect) {
                    update(settings.copy(usbDirect=it))
                  }
                  Text("Android 14+ only. Requires Pure profile and a compatible USB DAC with a BIT_PERFECT mixer mode. No EQ, FIR, peak guard or conversion is applied.",
                    color=quiet,style=MaterialTheme.typography.bodySmall)
                  Text("Open a WAV with Reference Player controls under Music Library. If hardware support or routing cannot be verified, playback stops instead of silently falling back.",
                    color=quiet,style=MaterialTheme.typography.bodySmall)
                }
                if(importMessage.isNotBlank()) Text(importMessage,color=quiet)
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
                val adbStatus by adb.state.collectAsState()
                val adbPrefs=adb.preferences
                var autoLink by remember { mutableStateOf(adbPrefs.automatic) }
                var autoDiscovery by remember { mutableStateOf(adbPrefs.discoverWireless) }
                var autoPair by remember { mutableStateOf(adbPrefs.autoPairCode) }
                var watchdog by remember { mutableStateOf(adbPrefs.watchdog) }
                var boot by remember { mutableStateOf(adbPrefs.startOnBoot) }
                var restore by remember { mutableStateOf(adbPrefs.restoreAfterRestart) }
                var tcp by remember { mutableStateOf(adbPrefs.tcpMode) }
                val accessible=SeqPairingAccessibilityService.isEnabled(this@MainActivity)
                SurfaceCard {
                  Row(verticalAlignment=Alignment.CenterVertically) {
                    Icon(Icons.Rounded.Terminal,null,tint=accent)
                    Spacer(Modifier.width(10.dp))
                    Text("SEQ ADB Details",style=MaterialTheme.typography.titleLarge,
                      fontWeight=FontWeight.Bold)
                  }
                  Text(adbStatus.message, color=if(adbStatus.uidVerified) accent else quiet)
                  HorizontalDivider(color=quiet.copy(alpha=0.25f))
                  Text("Shell UID   "+if(adbStatus.uidVerified) "2000 (last verified)" else "Not verified")
                  Text("Connection   "+if(adbStatus.busy) "Checking…" else "Disconnected (idle)")
                  Text("Transport   "+adbStatus.transport)
                  Text("Pairing   "+if(adbPrefs.wasPaired) "Previously paired" else "Not paired with SEQ")
                  Text("Discovery   "+if(adbStatus.scanning) "Searching" else "Idle")
                  Text("Last verified   "+if(adbStatus.verifiedAt>0L)
                    SimpleDateFormat("dd MMM, HH:mm",Locale.getDefault())
                      .format(Date(adbStatus.verifiedAt)) else "Never",color=quiet,
                    style=MaterialTheme.typography.bodySmall)
                  Text("Pairing port   "+(adbStatus.pairingPort?.toString() ?: "Not detected")+
                    "    TLS port   "+(adbStatus.connectPort?.toString() ?: "Not detected"),
                    color=quiet,style=MaterialTheme.typography.bodySmall)
                }
                SurfaceCard {
                  Text("Automatic connection",style=MaterialTheme.typography.titleMedium,
                    fontWeight=FontWeight.SemiBold)
                  ToggleRow("Automatic connection and recovery",autoLink) {
                    autoLink=it
                    adb.setAutomatic(it)
                  }
                  ToggleRow("Discover Wireless debugging",autoDiscovery) {
                    autoDiscovery=it
                    adb.setDiscovery(it)
                  }
                  Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                    Button(enabled=!adbStatus.busy,onClick={
                      adb.discover(true)
                      adb.connect()
                    }) { Text("Retry") }
                    OutlinedButton(enabled=!adbStatus.busy,onClick={
                      adb.inspectAutomatic()
                    }) { Text("Inspect audio") }
                  }
                  Text("Automatically finds this phone's ADB ports, authenticates SEQ's own key and verifies UID 2000. Shell connections close after each check.",
                    color=quiet,style=MaterialTheme.typography.bodySmall)
                }
                SurfaceCard {
                  Text("Pairing & recovery",style=MaterialTheme.typography.titleMedium,
                    fontWeight=FontWeight.SemiBold)
                  ToggleRow("Auto-detect pairing code",autoPair) {
                    autoPair=it
                    adb.setAutoPairCode(it)
                  }
                  Text("Reads the pairing code from Android's Wireless debugging screen using Accessibility, like VRec. There are no manual pairing fields.",
                    color=quiet,style=MaterialTheme.typography.bodySmall)
                  if(!accessible) {
                    Text("Enable the SEQ automatic ADB pairing Accessibility service first.",
                      color=MaterialTheme.colorScheme.error,style=MaterialTheme.typography.bodySmall)
                    OutlinedButton(onClick={
                      startActivity(Intent(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS))
                    }) { Text("Enable Accessibility") }
                  } else {
                    Text("Accessibility enabled",color=accent,style=MaterialTheme.typography.bodySmall)
                  }
                  OutlinedButton(onClick={
                    val args=Bundle().apply {
                      putString(":settings:fragment_args_key","toggle_adb_wireless")
                    }
                    val intent=Intent(android.provider.Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS)
                      .putExtra(":settings:fragment_args_key","toggle_adb_wireless")
                      .putExtra(":settings:show_fragment_args",args)
                    startActivity(intent)
                    adb.discover(true)
                  }) { Text("Open Wireless debugging") }
                  HorizontalDivider(color=quiet.copy(alpha=0.2f))
                  ToggleRow("Connection watchdog",watchdog) {
                    watchdog=it
                    adb.setWatchdog(it)
                  }
                  ToggleRow("Start on boot",boot) {
                    boot=it
                    adb.setStartOnBoot(it)
                  }
                }
                SurfaceCard {
                  Text("Connection & security",style=MaterialTheme.typography.titleMedium,
                    fontWeight=FontWeight.SemiBold)
                  ToggleRow("Restore after restart",restore) {
                    restore=it
                    adb.setRestoreAfterRestart(it)
                  }
                  Text("Opt-in restoration only. Requires verified ADB-granted secure settings permission and a new boot; never overrides a same-boot manual off.",
                    color=quiet,style=MaterialTheme.typography.bodySmall)
                  HorizontalDivider(color=quiet.copy(alpha=0.2f))
                  ToggleRow("Keep ADB without Wi-Fi (TCP/5555)",tcp) {
                    tcp=it
                    adb.setTcpMode(it)
                  }
                  Text("After verified TLS UID 2000, switches to TCP/5555 and re-verifies. This port can be reachable from your network; disable it when not needed.",
                    color=quiet,style=MaterialTheme.typography.bodySmall)
                  Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(enabled=!adbStatus.busy,onClick={adb.disconnectTcp()}) {
                      Text("Disable TCP/5555")
                    }
                    OutlinedButton(enabled=!adbStatus.busy,onClick={adb.disconnect()}) {
                      Text("Disconnect")
                    }
                  }
                }
                SurfaceCard {
                  Text("SEQ engine diagnostics",style=MaterialTheme.typography.titleMedium,
                    fontWeight=FontWeight.SemiBold)
                  Text("Audio service   "+if(status.running) "Running" else "Stopped")
                  Text("Local player   "+mediaStatus)
                  Text("Native DSP   "+nativeStatus)
                  Text("Sessions   "+status.sessions)
                  Text("Output   "+status.route)
                  Text(status.lastEvent,color=quiet,style=MaterialTheme.typography.bodySmall)
                  if(adbStatus.details.isNotBlank()) SelectionText(adbStatus.details)
                }
              }
              Page.UPDATES -> {
                N2K02UpdateScreen(
                  controller = SeqUpdater.controller,
                  installGuard = SeqUpdater.installGuard,
                  experimentalEnabled = experimentalEnabled,
                  onExperimentalEnabledChange = SeqUpdater::setExperimentalEnabled,
                )
              }

            }
          }
        }
      }
    }
  }
  override fun onResume() {
    super.onResume()
    adb.resumed()
    SeqUpdater.onResume(this)
  }
  override fun onDestroy() {
    adb.leavePage()
    super.onDestroy()
  }
}
@Composable private fun Header(title: String, icon: ImageVector) {
  Row(Modifier.fillMaxWidth().background(background).padding(horizontal = 18.dp, vertical = 16.dp),
    verticalAlignment = Alignment.CenterVertically) {
    Icon(icon, null, tint = MaterialTheme.colorScheme.onSurface)
    Spacer(Modifier.width(10.dp))
    Text(title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold,
      maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
    Icon(painter = painterResource(R.drawable.ic_seq), contentDescription="SEQ",
      modifier=Modifier.size(28.dp), tint=Color.Unspecified)
    Spacer(Modifier.width(6.dp))
    Text("SEQ", style=MaterialTheme.typography.titleMedium, fontWeight=FontWeight.Bold)
  }
}
@Composable private fun SurfaceCard(content: @Composable ColumnScope.() -> Unit) {
  Surface(shape = MaterialTheme.shapes.large, color = panel, modifier = Modifier.fillMaxWidth()) {
    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp), content = content)
  }
}
@Composable private fun MenuRow(
  title: String, subtitle: String, icon: ImageVector,
  showUpdateBadge: Boolean = false,
  updateStatus: dev.n2k02.updater.UpdateStatus = dev.n2k02.updater.UpdateStatus.Idle,
  onClick: () -> Unit
) {
  ListItem(
    modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
    headlineContent = { Text(title, style=MaterialTheme.typography.titleMedium) },
    supportingContent = { Text(subtitle, maxLines=2, style=MaterialTheme.typography.bodySmall) },
    leadingContent = { Icon(icon, contentDescription=null, tint=MaterialTheme.colorScheme.onSurface) },
    trailingContent = {
      Row(verticalAlignment=Alignment.CenterVertically) {
        if(showUpdateBadge) N2K02UpdateBadge(updateStatus)
        Icon(Icons.Rounded.ChevronRight, contentDescription=null, tint=quiet)
      }
    },
    colors=ListItemDefaults.colors(containerColor=MaterialTheme.colorScheme.background)
  )
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

// Advanced parametric editor is collapsed by default to preserve the compact UI.
@Composable private fun AdvancedFilters(
  profile: HeadphoneProfile,
  onSave: (HeadphoneProfile)->Unit
) {
  var expanded by remember { mutableStateOf(false) }
  var draft by remember(profile) { mutableStateOf(profile) }
  TextButton(onClick = { expanded=!expanded }) {
    Text(if(expanded) "Hide advanced filters" else "Edit parametric filters ("+profile.filters.size+")")
  }
  if(!expanded) return
  Text("Individual frequencies, gains and Q values • native playback only.",color=quiet,fontSize=12.sp)
  draft.filters.forEachIndexed { i, band ->
    SurfaceCard {
      Row(verticalAlignment=Alignment.CenterVertically) {
        Text("Filter "+(i+1),Modifier.weight(1f),fontWeight=FontWeight.SemiBold)
        Switch(checked=band.enabled,onCheckedChange={ value ->
          draft=draft.copy(filters=draft.filters.toMutableList().also { it[i]=band.copy(enabled=value) })
        })
      }
      Row(horizontalArrangement=Arrangement.spacedBy(6.dp)) {
        FilterKind.entries.forEach { kind ->
          FilterChip(selected=band.kind==kind,onClick={
            draft=draft.copy(filters=draft.filters.toMutableList().also { it[i]=band.copy(kind=kind) })
          },label={Text(kind.name.replace("_"," "),fontSize=10.sp)})
        }
      }
      Text("Frequency: "+band.frequency.toInt()+" Hz",color=quiet,fontSize=12.sp)
      val value=(kotlin.math.log10(band.frequency/20.0)/3.0).toFloat().coerceIn(0f,1f)
      Slider(value=value,onValueChange={ x ->
        val freq=20.0*Math.pow(1000.0,x.toDouble())
        draft=draft.copy(filters=draft.filters.toMutableList().also { it[i]=band.copy(frequency=freq) })
      })
      Text("Gain: "+String.format(Locale.US,"%+.1f dB",band.gain),color=quiet,fontSize=12.sp)
      Slider(value=band.gain.toFloat(),valueRange=-12f..12f,steps=47,onValueChange={ x ->
        draft=draft.copy(filters=draft.filters.toMutableList().also { it[i]=band.copy(gain=x.toDouble()) })
      })
      Text("Q: "+String.format(Locale.US,"%.2f",band.q),color=quiet,fontSize=12.sp)
      Slider(value=band.q.toFloat(),valueRange=0.15f..12f,onValueChange={ x ->
        draft=draft.copy(filters=draft.filters.toMutableList().also { it[i]=band.copy(q=x.toDouble()) })
      })
    }
  }
  OutlinedButton(enabled=draft.valid(),onClick={ onSave(draft);expanded=false }) {
    Text("Save precise filters")
  }
}
