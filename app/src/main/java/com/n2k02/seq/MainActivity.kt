package com.n2k02.seq

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.enableEdgeToEdge
import androidx.activity.SystemBarStyle
import androidx.activity.compose.BackHandler
import dev.n2k02.theme.N2K02Theme
import dev.n2k02.updater.N2K02UpdateBadge
import dev.n2k02.updater.N2K02UpdateBadgedIcon
import dev.n2k02.updater.N2K02UpdateScreen
import dev.n2k02.updater.hasPendingUpdate
import androidx.activity.compose.setContent
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.n2k02.seq.audio.SeqMediaSessionListener
import com.n2k02.seq.audio.SeqEngineService
import com.n2k02.seq.audio.SeqGlobalEffect
import com.n2k02.seq.audio.SeqPlaybackMonitor
import com.n2k02.seq.audio.SeqAdaptiveAnalyzer
import com.n2k02.seq.audio.SeqRuntime
import com.n2k02.seq.audio.SeqRoute
import com.n2k02.seq.model.*
import com.n2k02.seq.adb.LocalAdbDiagnostics
import com.n2k02.seq.adb.SeqAdbRecoveryWorker

private val seqColors = darkColorScheme(
  primary = Color(0xFFA1F8C9), onPrimary = Color(0xFF09271A),
  primaryContainer = Color(0xFF234B37), onPrimaryContainer = Color(0xFFBBFBD7),
  secondaryContainer = Color(0xFF273C32), onSecondaryContainer = Color(0xFFD2F5E2),
  background = Color(0xFF101112), onBackground = Color(0xFFF1F2F2),
  surface = Color(0xFF141617), onSurface = Color(0xFFF1F2F2),
  surfaceVariant = Color(0xFF242827), onSurfaceVariant = Color(0xFFBEC9C3),
  surfaceContainerLow = Color(0xFF1C1E1F), surfaceContainer = Color(0xFF222425),
  surfaceContainerHigh = Color(0xFF292C2C), outline = Color(0xFF808D85),
  outlineVariant = Color(0xFF3B4440)
)
private val accent: Color @Composable get() = MaterialTheme.colorScheme.primary
private val background: Color @Composable get() = MaterialTheme.colorScheme.background
private val quiet: Color @Composable get() = MaterialTheme.colorScheme.onSurfaceVariant
private enum class Page(val title: String) {
  HOME("Home"), SETTINGS("Settings"), ADB_CONNECTION("ADB connection"), DIAGNOSTICS("Diagnostics"),
  UPDATES("Updates"), EQUALIZER("Equalizer"), DEVICE_PROFILES("Device profiles"), SIGNAL_PATH("Signal path")
}

private fun pageIcon(page:Page):ImageVector = when(page) {
  Page.HOME -> Icons.Rounded.Home
  Page.SETTINGS -> Icons.Rounded.Settings
  Page.ADB_CONNECTION -> Icons.Rounded.SettingsInputAntenna
  Page.DIAGNOSTICS -> Icons.Rounded.MonitorHeart
  Page.UPDATES -> Icons.Rounded.SystemUpdate
  Page.EQUALIZER -> Icons.Rounded.Equalizer
  Page.DEVICE_PROFILES -> Icons.Rounded.Headphones
  Page.SIGNAL_PATH -> Icons.Rounded.AccountTree
}

class MainActivity : ComponentActivity() {
  private lateinit var store: SettingsStore
  private lateinit var adb: LocalAdbDiagnostics
  private var permissionRevision by mutableIntStateOf(0)
  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    SeqPlaybackMonitor.start(this)
    // Mirror mRadio: edge-to-edge window with explicit header and navigation insets.
    enableEdgeToEdge(statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
      navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT))
    store = SettingsStore(this)
    adb = LocalAdbDiagnostics(applicationContext)
    adb.preferences.markUserOpened()
    SeqAdbRecoveryWorker.reconcile(applicationContext)
    SeqUpdater.init(applicationContext)
    setContent {
      var settings by remember { mutableStateOf(store.read()) }
      var page by rememberSaveable { mutableStateOf(Page.HOME) }
      var audioParent by rememberSaveable { mutableStateOf(Page.HOME) }
      var presets by remember { mutableStateOf(store.presets()) }
      fun openAudioPage(next: Page) {
        audioParent = page
        page = next
      }
      val isAudioPage = page == Page.EQUALIZER || page == Page.DEVICE_PROFILES || page == Page.SIGNAL_PATH
      BackHandler(enabled = page != Page.HOME && page != Page.SETTINGS) {
        page = if (isAudioPage) audioParent else Page.SETTINGS
      }
      DisposableEffect(page) {
        if((page==Page.DIAGNOSTICS || page==Page.ADB_CONNECTION)) adb.enterPage()
        onDispose { if((page==Page.DIAGNOSTICS || page==Page.ADB_CONNECTION)) adb.leavePage() }
      }
      val updateStatus by SeqUpdater.controller.status.collectAsState()
      val experimentalEnabled by SeqUpdater.experimentalEnabled.collectAsState()
      val status by SeqRuntime.status.collectAsState()
      val externalPlayer by SeqMediaSessionListener.detectedPlayer.collectAsState()
      val mediaListenerConnected by SeqMediaSessionListener.connected.collectAsState()
      val mediaActive by SeqPlaybackMonitor.mediaActive.collectAsState()
      val carMode by SeqPlaybackMonitor.carMode.collectAsState()
      val adaptiveOn = remember(permissionRevision) { SeqAdaptiveAnalyzer.permitted(this@MainActivity) }
      val activeRoute = SeqRoute.kind(this, carMode)
      val output = SeqRoute.describe(this, carMode)
      var bindingRevision by remember { mutableIntStateOf(0) }
      val boundBands = remember(activeRoute, bindingRevision) { store.routeBands(activeRoute) }
      val manual = boundBands ?: settings.manualBands
      fun bindOutput(bands: List<Float>?) {
        store.saveRouteBands(activeRoute, bands)
        bindingRevision++
      }
      val mode = if (settings.profile == AudioProfile.PURE) "Pure" else if (manual != null) "Manual" else "SEQ Auto"
      val adaptiveTone by SeqAdaptiveAnalyzer.state.collectAsState()
      val tone = SeqRoute.tone(this, settings, carMode)
      val autoBands = remember(tone, adaptiveTone) {
        SystemEqCurveBuilder.build(tone, null).bands.map { it.gainDb }
      }
      val response = if (settings.profile == AudioProfile.PURE) autoBands else manual ?: autoBands
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
      N2K02Theme(darkTheme = true, dynamicColor = false) {
        MaterialTheme(colorScheme = seqColors) {
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
                    NavigationBarItem(selected = page == Page.HOME || (isAudioPage && audioParent == Page.HOME), onClick = { page = Page.HOME },
                      icon = { Icon(Icons.Rounded.Home, null) }, label = { Text("Home") })
                    NavigationBarItem(selected = page != Page.HOME && (!isAudioPage || audioParent == Page.SETTINGS), onClick = { page = Page.SETTINGS },
                      icon = {
                        N2K02UpdateBadgedIcon(updateStatus) {
                          Icon(Icons.Rounded.Settings, contentDescription = "Settings")
                        }
                      }, label = { Text("Settings") })
                    NavigationBarItem(selected = false, onClick = {
                      update(settings.copy(enabled = false))
                      adb.shutdown()
                      SeqUpdater.controller.cancel()
                      finishAffinity()
                    }, icon = { Icon(Icons.Rounded.PowerSettingsNew, contentDescription = "Quit SEQ") }, label = { Text("Quit") })
                  }
                }
              }
            }
          ) { padding ->
            val scroll = key(page) { rememberScrollState() }
            Column(Modifier.padding(padding).fillMaxSize().verticalScroll(scroll).padding(16.dp),
              verticalArrangement = Arrangement.spacedBy(12.dp)) {
              when (page) {
                Page.HOME -> {
                  AudioCard {
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                      Column(Modifier.weight(1f)) {
                        Text("Audio engine", style = MaterialTheme.typography.titleLarge)
                        Text(when {
                            status.globalActive -> "● Output mix effect enabled"
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
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                      FilterChip(selected = mode == "SEQ Auto",
                        onClick = {
                          bindOutput(null)
                          update(settings.copy(profile = AudioProfile.AUTO, manualBands = null))
                        },
                        label = { Text("SEQ Auto") }, modifier = Modifier.weight(1f))
                      FilterChip(selected = settings.profile == AudioProfile.PURE,
                        onClick = { update(settings.copy(profile = AudioProfile.PURE)) },
                        label = { Text("Pure") }, modifier = Modifier.weight(1f))
                    }
                    if (mode == "Manual") Text("Manual tuning selected", color = accent)
                    if (!adaptiveOn && settings.enabled && mode == "SEQ Auto") TextButton(onClick = {
                      requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 110)
                    }) { Text("Let SEQ adapt to your music") }
                  }
                  AudioCard {
                    MenuRow(output, "Device details", routeIcon(activeRoute), card = true) {
                      openAudioPage(Page.DEVICE_PROFILES)
                    }
                  }
                  AudioCard {
                    Text(if (mode == "SEQ Auto") "Auto response" else "$mode response",
                      style = MaterialTheme.typography.titleMedium)
                    ResponseGraph(response, "Configured equalizer response preview")
                    FilledTonalButton(onClick = { openAudioPage(Page.EQUALIZER) },
                      modifier = Modifier.fillMaxWidth()) {
                      Text("Open equalizer")
                      Spacer(Modifier.width(6.dp))
                      Icon(Icons.Rounded.ChevronRight, null)
                    }
                  }
                  AudioCard {
                    Text("Detected media", style = MaterialTheme.typography.titleMedium)
                    Row(verticalAlignment = Alignment.CenterVertically,
                      horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                      Icon(Icons.Rounded.MusicNote, null, tint = accent)
                      Text(externalPlayer ?: if (mediaActive) "Media playing" else "No media playing")
                    }
                    if (!mediaListenerConnected) TextButton(onClick = {
                      startActivity(Intent(android.provider.Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
                    }) { Text("Allow notification access") }
                  }
                  AudioCard {
                    MenuRow("Signal path", status.effectEngine.ifEmpty { status.lastEvent },
                      Icons.Rounded.AccountTree, card = true) { openAudioPage(Page.SIGNAL_PATH) }
                  }
                }
                Page.EQUALIZER -> key(activeRoute) {
                  EqualizerScreen(manual ?: autoBands.map {
                    it.coerceIn(-SystemEqCurveBuilder.MANUAL_LIMIT_DB, SystemEqCurveBuilder.MANUAL_LIMIT_DB)
                  }, mode == "Manual", settings,
                    positivePreamp = status.limiterAvailable && settings.peakGuard,
                    compareEnabled = settings.enabled && settings.allMedia && settings.profile != AudioProfile.PURE,
                    onCompare = { SeqGlobalEffect.compare(this@MainActivity, it) },
                    onControls = { update(it) },
                    onApply = { bands ->
                      if (boundBands != null) bindOutput(bands)
                      update(settings.copy(profile = AudioProfile.AUTO,
                        manualBands = if (boundBands == null) bands else settings.manualBands))
                    },
                    onSave = { name, bands ->
                      store.savePreset(name, bands)
                      presets = store.presets()
                    })
                }
                Page.DEVICE_PROFILES -> DeviceProfilesScreen(activeRoute, output, mode, presets,
                  manual.takeIf { mode == "Manual" }, bound = boundBands != null,
                  onBind = { bands ->
                    bindOutput(bands)
                    update(settings)
                  },
                  onPreset = { bands ->
                    if (boundBands != null) bindOutput(bands)
                    update(settings.copy(profile = AudioProfile.AUTO,
                      manualBands = if (boundBands == null) bands else settings.manualBands))
                  })
                Page.SIGNAL_PATH -> SignalPathScreen(status, output, settings) {
                  update(settings.copy(peakGuard = it))
                }
                Page.SETTINGS -> {
                  MenuRow("Equalizer", "10- or 31-band manual tuning", Icons.Rounded.Equalizer) { openAudioPage(Page.EQUALIZER) }
                  MenuRow("Device profiles", "Output details and saved presets", Icons.Rounded.Headphones) { openAudioPage(Page.DEVICE_PROFILES) }
                  MenuRow("Signal path", "Processing stages and peak limiter", Icons.Rounded.AccountTree) { openAudioPage(Page.SIGNAL_PATH) }
                  MenuRow("ADB connection", "Pairing, recovery and TCP/5555", Icons.Rounded.SettingsInputAntenna) { page = Page.ADB_CONNECTION }
                  HorizontalDivider()
                  MenuRow("Diagnostics", "Direct ADB • Audio sessions", Icons.Rounded.MonitorHeart) { page = Page.DIAGNOSTICS }
                  MenuRow("Updates", if(updateStatus.hasPendingUpdate()) "New update available" else "Version and update checks",
                    Icons.Rounded.SystemUpdate, showUpdateBadge=true, updateStatus=updateStatus) { page = Page.UPDATES }
                }
                Page.ADB_CONNECTION -> SeqAdbConnectionScreen(
                  context = this@MainActivity,
                  adb = adb,
                  permissionRevision = permissionRevision
                )
                Page.DIAGNOSTICS -> SeqAdbDiagnosticsScreen(adb)
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
  }
  override fun onResume() {
    super.onResume()
    permissionRevision++ // Refresh live Android grants after returning from system Settings.
    SeqAdaptiveAnalyzer.sync(this, SeqPlaybackMonitor.mediaActive.value)
    adb.resumed()
    SeqUpdater.onResume(this)
  }
  override fun onPause() {
    SeqGlobalEffect.compare(this, false)
    super.onPause()
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
@Composable private fun MenuRow(
  title: String, subtitle: String, icon: ImageVector,
  showUpdateBadge: Boolean = false,
  updateStatus: dev.n2k02.updater.UpdateStatus = dev.n2k02.updater.UpdateStatus.Idle,
  card: Boolean = false,
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
    colors=ListItemDefaults.colors(containerColor=if (card) Color.Transparent else MaterialTheme.colorScheme.background)
  )
}
