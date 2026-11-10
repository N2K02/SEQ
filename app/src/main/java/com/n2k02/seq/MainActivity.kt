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
import androidx.lifecycle.lifecycleScope
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import kotlinx.coroutines.launch
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
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
import com.n2k02.seq.audio.HuaweiNativeEq
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
  HOME("Equalizer"), SETTINGS("Settings"), ADB_CONNECTION("ADB connection"),
  UPDATES("Updates")
}

private fun pageIcon(page:Page):ImageVector = when(page) {
  Page.HOME -> Icons.Rounded.Home
  Page.SETTINGS -> Icons.Rounded.Settings
  Page.ADB_CONNECTION -> Icons.Rounded.SettingsInputAntenna
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
    enableEdgeToEdge(statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
      navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT))
    store = SettingsStore(this)
    adb = LocalAdbDiagnostics(applicationContext)
    if (savedInstanceState == null) adb.preferences.markUserOpened()
    SeqAdbRecoveryWorker.reconcile(applicationContext)
    SeqUpdater.init(applicationContext)
    setContent {
      var settings by remember { mutableStateOf(store.read()) }
      var page by rememberSaveable { mutableStateOf(Page.HOME) }
      var resetRevision by remember { mutableIntStateOf(0) }
      var routeRevision by remember { mutableIntStateOf(0) }
      var nativeEnabled by remember { mutableStateOf(HuaweiNativeEq.enabled(this)) }
      var nativePreset by remember { mutableIntStateOf(HuaweiNativeEq.preset(this)) }
      val nativeStatus by HuaweiNativeEq.status.collectAsState()
      BackHandler(enabled = page != Page.HOME && page != Page.SETTINGS) {
        page = Page.SETTINGS
      }
      DisposableEffect(page) {
        if(page==Page.ADB_CONNECTION) adb.enterPage()
        onDispose { if(page==Page.ADB_CONNECTION) adb.leavePage() }
      }
      val updateStatus by SeqUpdater.controller.status.collectAsState()
      val experimentalEnabled by SeqUpdater.experimentalEnabled.collectAsState()
      val status by SeqRuntime.status.collectAsState()
      val externalPlayer by SeqMediaSessionListener.detectedPlayer.collectAsState()
      val mediaListenerConnected by SeqMediaSessionListener.connected.collectAsState()
      val mediaActive by SeqPlaybackMonitor.mediaActive.collectAsState()
      val carMode by SeqPlaybackMonitor.carMode.collectAsState()
      val mediaVolume by SeqPlaybackMonitor.mediaVolume.collectAsState()
      val activeRoute = SeqRoute.kind(this, carMode)
      val output = SeqRoute.describe(this, carMode)
      val activeCorrection = remember(activeRoute, resetRevision) { store.routeCorrection(activeRoute) }
      val manual = remember(activeRoute, routeRevision, resetRevision) {
        store.routeBands(activeRoute) ?: settings.manualBands
      }
      val mode = if (manual != null) "Custom" else "Automatic"
      val tone = SeqRoute.tone(this, settings, carMode)
      val autoBands = remember(tone) {
        SystemEqCurveBuilder.build(tone, null).bands.map { it.gainDb }
      }
      fun update(next: EngineSettings) {
        settings = next
        store.write(next)
        SeqAdaptiveAnalyzer.sync(this, SeqPlaybackMonitor.mediaActive.value)
        SeqGlobalEffect.sync(this)
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
                      SeqPlaybackMonitor.stop()
                      HuaweiNativeEq.stop()
                      SeqUpdater.controller.cancel()
                      lifecycleScope.launch {
                        adb.shutdownAndAwait()
                        finishAffinity()
                      }
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
                  // ADB is optional: media tuning works without pairing or diagnostics screens.
                  val adbState by adb.sharedCore.state.collectAsState()
                  AudioCard {
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                      Column(Modifier.weight(1f)) {
                        Text("Sound", style = MaterialTheme.typography.titleLarge)
                        Text(when {
                          !settings.enabled -> "Off"
                          status.nativeActive -> "FreeBuds hardware EQ confirmed"
                          status.globalActive && activeRoute == RouteKind.BLUETOOTH -> "Android EQ attached • Bluetooth path unverified"
                          status.processing -> "Sound enhancement active"
                          else -> "On • " + status.lastEvent
                        }, color = if (status.processing) accent else quiet,
                          style = MaterialTheme.typography.bodySmall)
                      }
                      Switch(checked = settings.enabled, onCheckedChange = { on ->
                        if (on && Build.VERSION.SDK_INT >= 33 &&
                          checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                          requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 109)
                        }
                        update(settings.copy(enabled = on, profile = AudioProfile.AUTO, allMedia = true))
                      })
                    }
                    Text(if (mediaActive) "Media playing • $output" else "Ready • $output",
                      style = MaterialTheme.typography.bodyMedium)
                    if (settings.enabled && !status.processing) Text(
                      "Android may limit effects on some apps, offloaded audio or Android Auto outputs.",
                      style = MaterialTheme.typography.bodySmall, color = quiet)
                    if (adbState.connected) Text("ADB connected", color = accent,
                      style = MaterialTheme.typography.labelSmall)
                  }
                  if (HuaweiNativeEq.targetRoute(this@MainActivity) != null || nativeEnabled) {
                    AudioCard {
                      Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                          Text("FreeBuds 5 hardware EQ", style = MaterialTheme.typography.titleMedium)
                          Text("Experimental • selects earbuds presets, not SEQ's custom bands",
                            style = MaterialTheme.typography.bodySmall, color = quiet)
                        }
                        Switch(checked = nativeEnabled, enabled = settings.enabled,
                          onCheckedChange = { on ->
                            nativeEnabled = on
                            if (on && Build.VERSION.SDK_INT >= 31 && !HuaweiNativeEq.permitted(this@MainActivity)) {
                              requestPermissions(arrayOf(Manifest.permission.BLUETOOTH_CONNECT), 110)
                            }
                            HuaweiNativeEq.configure(this@MainActivity, on, nativePreset)
                          })
                      }
                      if (nativeEnabled) {
                        Row(Modifier.horizontalScroll(rememberScrollState()),
                          horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                          listOf(1 to "Default", 2 to "Bass", 3 to "Treble", 9 to "Voices").forEach { (code, label) ->
                            FilterChip(selected = nativePreset == code, onClick = {
                              nativePreset = code
                              HuaweiNativeEq.configure(this@MainActivity, true, code)
                            }, label = { Text(label) })
                          }
                        }
                        Text(nativeStatus, style = MaterialTheme.typography.bodySmall, color = quiet)
                        Text("Only uses hardware EQ after device readback. If unavailable, Android DSP remains active.",
                          style = MaterialTheme.typography.bodySmall, color = quiet)
                      }
                    }
                  }
                  AudioCard {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                      Icon(Icons.Rounded.MusicNote, null, tint = accent)
                      Spacer(Modifier.width(10.dp))
                      Text(externalPlayer?.name ?: if (mediaActive) "Media is playing" else "Play music to start tuning",
                        modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    Text("Changes are saved automatically and follow your media output.",
                      style = MaterialTheme.typography.bodySmall, color = quiet)
                  }
                  key(activeRoute, resetRevision) {
                    SimpleEqualizerScreen(
                      initial = manual ?: SystemEqCurveBuilder.resample(autoBands,
                        SystemEqCurveBuilder.COMPACT_BAND_COUNT),
                      selectedMode = settings.soundMode,
                      custom = manual != null,
                      bassBoost = settings.bassBoost,
                      enabled = settings.enabled,
                      onBands = { bands ->
                        store.saveRouteBands(activeRoute, bands)
                        routeRevision++
                        update(settings.copy(profile = AudioProfile.AUTO, allMedia = true,
                          manualBands = null))
                      },
                      onMode = { sound ->
                        store.saveRouteBands(activeRoute, null)
                        routeRevision++
                        update(settings.copy(profile = AudioProfile.AUTO, allMedia = true,
                          soundMode = sound, manualBands = null))
                      },
                      onBass = { bass -> update(settings.copy(bassBoost = bass)) }
                    )
                  }
                  OutlinedButton(
                    onClick = {
                      store.resetSound(activeRoute)
                      // Recreate slider state after clearing in-flight edits and saved levels.
                      resetRevision++
                      update(store.read().copy(enabled = settings.enabled,
                        profile = AudioProfile.AUTO, allMedia = true))
                    },
                    modifier = Modifier.fillMaxWidth()
                  ) {
                    Icon(Icons.Rounded.RestartAlt, null)
                    Spacer(Modifier.width(8.dp))
                    Text("Reset all sound settings")
                  }
                }
                Page.SETTINGS -> {
                  AudioCard {
                    Text("SEQ tunes automatically while music plays.", style = MaterialTheme.typography.titleMedium)
                    Text("No manual Apply or Save steps. Choose a sound style or adjust the equalizer on Home.",
                      style = MaterialTheme.typography.bodySmall)
                  }
                  MenuRow("ADB connection", "Optional connection and recovery", Icons.Rounded.SettingsInputAntenna) {
                    page = Page.ADB_CONNECTION
                  }
                  HorizontalDivider()
                  MenuRow("Updates", if(updateStatus.hasPendingUpdate()) "New update available" else "Version and update checks",
                    Icons.Rounded.SystemUpdate, showUpdateBadge=true, updateStatus=updateStatus) { page = Page.UPDATES }
                }
                Page.ADB_CONNECTION -> SeqAdbConnectionScreen(
                  context = this@MainActivity,
                  adb = adb,
                  permissionRevision = permissionRevision
                )
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
    HuaweiNativeEq.sync(this)
    SeqGlobalEffect.sync(this)
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
