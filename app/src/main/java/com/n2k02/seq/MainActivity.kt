package com.n2k02.seq

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.ComponentActivity
import androidx.activity.enableEdgeToEdge
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
import androidx.compose.runtime.saveable.rememberSaveable
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
import com.n2k02.seq.audio.LocalMusicLibrary
import com.n2k02.seq.audio.LocalTrack
import com.n2k02.seq.audio.SeqMediaLibraryService
import com.n2k02.seq.audio.SeqMediaSessionListener
import com.n2k02.seq.audio.SeqEngineService
import com.n2k02.seq.audio.SeqGlobalEffect
import com.n2k02.seq.audio.SeqPlaybackMonitor
import com.n2k02.seq.audio.SeqAdaptiveAnalyzer
import com.n2k02.seq.audio.SeqReferencePlayerService
import com.n2k02.seq.audio.SeqRuntime
import com.n2k02.seq.model.*
import com.n2k02.seq.adb.LocalAdbDiagnostics
import com.n2k02.seq.adb.SeqAdbRecoveryWorker
import com.n2k02.seq.SeqUpdater
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

// Shared mRadio-style Material 3 Expressive palette: dynamic color and motion.
private val accent: Color @Composable get() = MaterialTheme.colorScheme.primary
private val background: Color @Composable get() = MaterialTheme.colorScheme.background
private val panel: Color @Composable get() = MaterialTheme.colorScheme.surfaceContainerLow
private val quiet: Color @Composable get() = MaterialTheme.colorScheme.onSurfaceVariant
private enum class Page(val title: String) {
  HOME("Home"), SETTINGS("Settings"), PLAYER("Music Library"), DIAGNOSTICS("Diagnostics"),
  UPDATES("Updates")
}

private fun pageIcon(page:Page):ImageVector = when(page) {
  Page.HOME -> Icons.Rounded.Home
  Page.SETTINGS -> Icons.Rounded.Settings
  Page.PLAYER -> Icons.Rounded.MusicNote
  Page.DIAGNOSTICS -> Icons.Rounded.MonitorHeart
  Page.UPDATES -> Icons.Rounded.SystemUpdate
}

class MainActivity : ComponentActivity() {
  private lateinit var store: SettingsStore
  private lateinit var adb: LocalAdbDiagnostics
  private var permissionRevision by mutableIntStateOf(0)
  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    SeqPlaybackMonitor.start(this)
    // Mirror mRadio: edge-to-edge window with explicit header and navigation insets.
    enableEdgeToEdge()
    store = SettingsStore(this)
    adb = LocalAdbDiagnostics(applicationContext)
    adb.preferences.markUserOpened()
    SeqAdbRecoveryWorker.reconcile(applicationContext)
    SeqUpdater.init(applicationContext)
    setContent {
      var settings by remember { mutableStateOf(store.read()) }
      var page by rememberSaveable { mutableStateOf(Page.HOME) }
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
      val externalPlayer by SeqMediaSessionListener.detectedPlayer.collectAsState()
      val mediaListenerConnected by SeqMediaSessionListener.connected.collectAsState()
      val mediaActive by SeqPlaybackMonitor.mediaActive.collectAsState()
      val carMode by SeqPlaybackMonitor.carMode.collectAsState()
      val adaptiveOn = remember(permissionRevision) { SeqAdaptiveAnalyzer.permitted(this@MainActivity) }
      val musicStore = remember { LocalMusicLibrary(this@MainActivity) }
      var music by remember { mutableStateOf(musicStore.items()) }
      var visibleTrackCount by rememberSaveable { mutableIntStateOf(20) }
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
        SeqGlobalEffect.sync(this)
        SeqAdaptiveAnalyzer.sync(this, SeqPlaybackMonitor.mediaActive.value)
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
            // Match mRadio: keep gesture/navigation-bar region opaque and below content.
            Surface(
              color = MaterialTheme.colorScheme.surface,
              modifier = Modifier.fillMaxWidth()
            ) {
              Column(Modifier.navigationBarsPadding()) {
                NavigationBar(
                  containerColor = MaterialTheme.colorScheme.surface,
                  tonalElevation = 0.dp,
                  windowInsets = WindowInsets(0, 0, 0, 0)
                ) {
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
                      Text(when {
                          status.globalActive -> "● Processing all media"
                          status.effectAttached -> "● Effect attached"
                          settings.enabled -> status.lastEvent
                          else -> "Off"
                        }, color = if (status.processing) accent else quiet)
                    }
                    Switch(checked = settings.enabled, onCheckedChange = {
                      if (it) {
                        // One prompt: notifications (engine) + output analysis for SEQ Auto.
                        val wanted = buildList {
                          if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
                          add(Manifest.permission.RECORD_AUDIO)
                        }.filter { p -> checkSelfPermission(p) != PackageManager.PERMISSION_GRANTED }
                        if (wanted.isNotEmpty()) requestPermissions(wanted.toTypedArray(), 109)
                      }
                      update(settings.copy(enabled = it))
                    })
                  }
                }
                SurfaceCard {
                  Text("Detected media",fontWeight=FontWeight.SemiBold)
                  Text(when {
                    externalPlayer!=null -> "Playing: "+externalPlayer
                    mediaActive -> "Media playing"
                    else -> "No media playing"
                  } + (if(carMode) " • Android Auto" else "") +
                    (if(mediaActive && status.globalActive) " • SEQ processing" else ""),
                    color=if(mediaActive && status.processing) accent else quiet,fontSize=12.sp)
                  if(!mediaListenerConnected) TextButton(onClick={
                    startActivity(Intent(android.provider.Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
                  }) { Text("Allow notification access (app names + background resume)") }
                }
                SurfaceCard {
                  Text("Sound", color = quiet, fontSize = 13.sp)
                  Spacer(Modifier.height(6.dp))
                  Text(settings.profile.title, fontWeight = FontWeight.Bold, fontSize = 25.sp)
                  Text(settings.profile.subtitle, color = quiet, fontSize = 13.sp)
                  if (settings.profile == AudioProfile.AUTO) {
                    Spacer(Modifier.height(6.dp))
                    Text("Tuned for " + status.route +
                      (if (mediaActive && adaptiveOn) " • adapting to your music" else ""),
                      color = accent, fontSize = 12.sp)
                    if (!adaptiveOn && settings.enabled) TextButton(onClick = {
                      requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 110)
                    }) { Text("Let SEQ adapt to your music") }
                  }
                  Spacer(Modifier.height(12.dp))
                  Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    AudioProfile.entries.forEach { p ->
                      FilterChip(selected = settings.profile == p,
                        onClick = { update(settings.copy(profile = p)) },
                        label = { Text(p.title, fontSize = 13.sp) },
                        modifier = Modifier.weight(1f))
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
                        else if(status.globalActive) "All media • "+status.effectEngine+" • "+status.route
                        else if(status.effectAttached) "Session EQ • "+status.sessions+" session(s)"
                        else if(settings.profile==AudioProfile.PURE) "Pure bypass"
                        else if(settings.enabled) status.lastEvent
                        else "Engine off", color=quiet, fontSize=12.sp)
                    }
                  }
                }
              }
              Page.SETTINGS -> {
                MenuRow("Music Library", "Lossless player • Android Auto", Icons.Rounded.PlayCircle) { page = Page.PLAYER }
                HorizontalDivider(Modifier.padding(vertical = 10.dp), color = quiet.copy(alpha = 0.25f))
                MenuRow("Diagnostics", "Direct ADB • Audio sessions", Icons.Rounded.MonitorHeart) { page = Page.DIAGNOSTICS }
                MenuRow("Updates", if(updateStatus.hasPendingUpdate()) "New update available" else "Version and update checks",
                  Icons.Rounded.SystemUpdate, showUpdateBadge=true, updateStatus=updateStatus) { page = Page.UPDATES }
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
                  music.take(visibleTrackCount).forEach { track ->
                    MenuRow(track.title,"Tap to play with SEQ's PCM processing",Icons.Rounded.MusicNote) {
                      stopService(SeqReferencePlayerService.intent(this@MainActivity,SeqReferencePlayerService.STOP))
                      startService(SeqMediaLibraryService.intent(this@MainActivity,SeqMediaLibraryService.PLAY).setData(track.uri))
                    }
                  }
                  if(music.size>visibleTrackCount) {
                    TextButton(onClick={ visibleTrackCount=(visibleTrackCount+20).coerceAtMost(music.size) }) {
                      Text("Show more tracks ("+visibleTrackCount+"/"+music.size+")")
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
              Page.DIAGNOSTICS -> {
                SeqAdbDiagnosticsScreen(
                  context=this@MainActivity,
                  adb=adb,
                  engine=status,
                  localPlayer=mediaStatus,
                  nativePath=nativeStatus,
                  permissionRevision=permissionRevision
                )
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
    permissionRevision++ // Refresh live Android grants after returning from system Settings.
    SeqAdaptiveAnalyzer.sync(this, SeqPlaybackMonitor.mediaActive.value)
    adb.resumed()
    SeqUpdater.onResume(this)
  }
  override fun onDestroy() {
    adb.dispose()
    super.onDestroy()
  }
}
@Composable private fun Header(title: String, icon: ImageVector) {
  Row(
    Modifier.fillMaxWidth()
      .background(MaterialTheme.colorScheme.surface)
      .statusBarsPadding()
      .padding(horizontal = 20.dp, vertical = 10.dp),
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
