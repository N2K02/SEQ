package com.n2k02.seq

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.n2k02.seq.adb.LocalAdbDiagnostics
import com.n2k02.seq.adb.SeqAdbDisplayPolicy
import com.n2k02.seq.adb.SeqPairingAccessibilityService
import com.n2k02.seq.audio.EngineStatus
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * mRadio / VRec-parity diagnostics.
 * The permission card is intentionally separate from pairing & recovery.
 * Revise checks on Activity ON_RESUME, never via a timer or ADB poll.
 */
@Composable
internal fun SeqAdbDiagnosticsScreen(
  context:Context,
  adb:LocalAdbDiagnostics,
  engine:EngineStatus,
  localPlayer:String,
  nativePath:String,
  permissionRevision:Int
) {
  val status by adb.state.collectAsState()
  val pref=adb.preferences
  var automatic by remember { mutableStateOf(pref.automatic) }
  var discovery by remember { mutableStateOf(pref.discoverWireless) }
  var autoPair by remember { mutableStateOf(pref.autoPairCode) }
  var watchdog by remember { mutableStateOf(pref.watchdog) }
  var startOnBoot by remember { mutableStateOf(pref.startOnBoot) }
  var restore by remember { mutableStateOf(pref.restoreAfterRestart) }
  var tcp by remember { mutableStateOf(pref.tcpMode) }
  var detailsExpanded by remember { mutableStateOf(false) }
  var audioExpanded by remember { mutableStateOf(false) }
  var confirmDisableTcp by remember { mutableStateOf(false) }

  // The system permission screen can be opened without leaving this Compose
  // route; refresh each grant on the Activity's next onResume.
  val access=remember(permissionRevision) { SeqPairingAccessibilityService.isEnabled(context) }
  val secure=remember(permissionRevision,status.verifiedAt,restore) {
    context.checkSelfPermission("android.permission.WRITE_SECURE_SETTINGS")==PackageManager.PERMISSION_GRANTED
  }
  val notifications=remember(permissionRevision) {
    Build.VERSION.SDK_INT<33 ||
      context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)==PackageManager.PERMISSION_GRANTED
  }
  val view=SeqAdbDisplayPolicy.resolve(
    previouslyVerified=status.uidVerified && status.verifiedAt>0L,
    working=status.busy,
    paired=pref.wasPaired,
    transport=status.transport,
    verifiedThisBoot=pref.currentBoot>=0 && pref.currentBoot==pref.verifiedBoot
  )
  val ink=MaterialTheme.colorScheme.onSurfaceVariant

  DiagnosticSection("SEQ ADB Details",Icons.Rounded.Terminal) {
    Text(status.message,style=MaterialTheme.typography.bodyMedium,
      color=if(view.previouslyVerified) MaterialTheme.colorScheme.primary else ink)
    HorizontalDivider(color=MaterialTheme.colorScheme.outlineVariant)
    Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(12.dp)) {
      SummaryCell("Shell",view.shell,Modifier.weight(1f))
      SummaryCell("Connection",view.connection,Modifier.weight(1f))
    }
    Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(12.dp)) {
      SummaryCell("Transport",view.transport,Modifier.weight(1f))
      SummaryCell("Authorization",view.authorization,Modifier.weight(1f))
    }
    val last=if(status.verifiedAt>0L)
      SimpleDateFormat("dd MMM, HH:mm",Locale.getDefault()).format(Date(status.verifiedAt)) else "Never"
    Text("Last checked  $last",style=MaterialTheme.typography.bodySmall,color=ink)
    TextButton(onClick={detailsExpanded=!detailsExpanded},contentPadding=PaddingValues(0.dp)) {
      Text(if(detailsExpanded) "Hide ports" else "Show discovered ports")
      Icon(if(detailsExpanded)Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore,null)
    }
    if(detailsExpanded) {
      SummaryLine("Wireless TLS",status.connectPort?.toString() ?: "Not discovered")
      SummaryLine("Pairing listener",status.pairingPort?.toString() ?: "Not discovered")
      SummaryLine("Discovery",if(status.scanning)"Scanning" else "Idle")
      SummaryLine("Last TLS port",pref.lastVerifiedPort?.toString() ?: "None for this boot")
    }
  }

  DiagnosticSection("Permissions",Icons.Rounded.VerifiedUser) {
    Text("Android-controlled access",style=MaterialTheme.typography.bodySmall,color=ink)
    PermissionStatusRow(Icons.Rounded.AccessibilityNew,"Accessibility",
      if(access)"Enabled" else "Not enabled",
      if(view.previouslyVerified) "Optional for future Wireless debugging pairing"
      else "Required for automatic pairing-code detection",
      access
    ) {
      openSystemSettings(context,Settings.ACTION_ACCESSIBILITY_SETTINGS)
    }
    HorizontalDivider(color=MaterialTheme.colorScheme.outlineVariant)
    PermissionStatusRow(Icons.Rounded.Security,"ADB authorization",
      if(view.previouslyVerified)"Last verified" else "Not verified",
      if(view.previouslyVerified)"UID 2000 previously confirmed; socket is now closed"
      else "Android must authorize SEQ's own ADB key",
      view.previouslyVerified
    ) {
      openWirelessDebugging(context)
      adb.discover(true)
    }
    HorizontalDivider(color=MaterialTheme.colorScheme.outlineVariant)
    PermissionStatusRow(Icons.Rounded.Restore,"Secure settings",
      when {
        !restore -> "Optional"
        secure -> "Granted"
        else -> "Not granted"
      },
      if(restore) "Required only for opted-in reboot restoration"
        else "Not required while Restore after restart is off",
      secure,
      optional=!restore,
      onClick=if(restore) ({ adb.connect() }) else null
    )
    if(Build.VERSION.SDK_INT>=33) {
      HorizontalDivider(color=MaterialTheme.colorScheme.outlineVariant)
      PermissionStatusRow(Icons.Rounded.NotificationsNone,"Notifications",
        if(notifications)"Allowed" else "Optional",
        "Optional playback and status notifications",
        notifications,
        optional=!notifications
      ) {
        openSystemSettings(context,Settings.ACTION_APP_NOTIFICATION_SETTINGS,
          appPackage=context.packageName)
      }
    }
  }

  DiagnosticSection("Automatic connection",Icons.Rounded.Sync) {
    DiagnosticSwitch("Automatic connection and recovery",automatic,
      "Use previously authorized localhost ADB when available") {
      automatic=it;adb.setAutomatic(it)
    }
    DiagnosticSwitch("Discover Wireless debugging",discovery,
      "Temporary local mDNS discovery, no continuous scan") {
      discovery=it;adb.setDiscovery(it)
    }
    Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(8.dp)) {
      Button(onClick={adb.discover(true);adb.connect()},
        enabled=!status.busy,modifier=Modifier.weight(1f)) {
        Icon(Icons.Rounded.Refresh,null,modifier=Modifier.size(18.dp))
        Spacer(Modifier.width(5.dp))
        Text("Retry")
      }
      OutlinedButton(onClick={adb.inspectAutomatic()},enabled=!status.busy,
        modifier=Modifier.weight(1f)) { Text("Inspect audio") }
    }
  }

  DiagnosticSection("Pairing & recovery",Icons.Rounded.SettingsInputAntenna) {
    DiagnosticSwitch("Auto-detect pairing code",autoPair,
      "Reads only Android's visible Wireless debugging dialog") {
      autoPair=it;adb.setAutoPairCode(it)
    }
    OutlinedButton(onClick={
      adb.discover(true)
      openWirelessDebugging(context)
    },modifier=Modifier.fillMaxWidth()) {
      Icon(Icons.Rounded.Wifi,null,modifier=Modifier.size(18.dp))
      Spacer(Modifier.width(8.dp))
      Text("Open Wireless debugging")
    }
    HorizontalDivider(color=MaterialTheme.colorScheme.outlineVariant)
    DiagnosticSwitch("Connection watchdog",watchdog,
      "Bounded recovery checks when previously authorized") {
      watchdog=it;adb.setWatchdog(it)
    }
    DiagnosticSwitch("Start on boot",startOnBoot,
      "Restore only when permitted after device restart") {
      startOnBoot=it;adb.setStartOnBoot(it)
    }
  }

  DiagnosticSection("Connection & security",Icons.Rounded.Shield) {
    DiagnosticSwitch("Restore after restart",restore,
      "Opt-in; requires secure settings permission") {
      restore=it;adb.setRestoreAfterRestart(it)
    }
    DiagnosticSwitch("Keep ADB without Wi-Fi (TCP/5555)",tcp,
      "Can expose ADB to other devices on the network") {
      tcp=it;adb.setTcpMode(it)
    }
    if(tcp || status.transport=="Local TCP/5555") {
      OutlinedButton(onClick={confirmDisableTcp=true},enabled=!status.busy,
        modifier=Modifier.fillMaxWidth()) { Text("Disable TCP/5555") }
    }
  }

  DiagnosticSection("SEQ audio diagnostics",Icons.Rounded.GraphicEq) {
    SummaryLine("Audio engine",if(engine.running)"Running" else "Stopped")
    SummaryLine("Attached sessions",engine.sessions.toString())
    SummaryLine("Native DSP",nativePath)
    TextButton(onClick={audioExpanded=!audioExpanded},contentPadding=PaddingValues(0.dp)) {
      Text(if(audioExpanded)"Hide details" else "More audio details")
      Icon(if(audioExpanded)Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore,null)
    }
    if(audioExpanded) {
      SummaryLine("Local music",localPlayer)
      SummaryLine("Output",engine.route)
      Text(engine.lastEvent,style=MaterialTheme.typography.bodySmall,color=ink)
      if(status.details.isNotBlank()) {
        HorizontalDivider(color=MaterialTheme.colorScheme.outlineVariant)
        Text("Audio routing snapshot",style=MaterialTheme.typography.titleSmall)
        Text(status.details,style=MaterialTheme.typography.bodySmall,color=ink)
      }
    }
  }
  if(confirmDisableTcp) AlertDialog(
    onDismissRequest={confirmDisableTcp=false},
    icon={ Icon(Icons.Rounded.WarningAmber,contentDescription=null) },
    title={ Text("Disable TCP/5555?") },
    text={ Text("This changes the phone's ADB listener and could interrupt VRec. Continue only when the shared TCP/5555 connection is no longer needed.") },
    confirmButton={ TextButton(onClick={
      confirmDisableTcp=false
      adb.disconnectTcp()
    }) { Text("Disable TCP") } },
    dismissButton={ TextButton(onClick={confirmDisableTcp=false}) { Text("Cancel") } }
  )
}

@Composable
private fun DiagnosticSection(title:String,icon:ImageVector,body:@Composable ColumnScope.()->Unit) {
  Card(modifier=Modifier.fillMaxWidth(),
    colors=CardDefaults.cardColors(containerColor=MaterialTheme.colorScheme.surfaceContainerLow),
    shape=MaterialTheme.shapes.large) {
    Column(Modifier.fillMaxWidth().padding(16.dp),verticalArrangement=Arrangement.spacedBy(10.dp)) {
      Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(10.dp)) {
        Icon(icon,contentDescription=null,tint=MaterialTheme.colorScheme.primary,modifier=Modifier.size(22.dp))
        Text(title,style=MaterialTheme.typography.titleMedium,fontWeight=FontWeight.SemiBold)
      }
      body()
    }
  }
}
@Composable
private fun SummaryCell(label:String,value:String,modifier:Modifier=Modifier) {
  Column(modifier) {
    Text(label,style=MaterialTheme.typography.labelMedium,color=MaterialTheme.colorScheme.onSurfaceVariant)
    Text(value,style=MaterialTheme.typography.bodyMedium,fontWeight=FontWeight.Medium)
  }
}
@Composable
private fun SummaryLine(label:String,value:String) {
  Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(8.dp)) {
    Text(label,modifier=Modifier.weight(1f),style=MaterialTheme.typography.bodySmall,
      color=MaterialTheme.colorScheme.onSurfaceVariant)
    Text(value,style=MaterialTheme.typography.bodySmall)
  }
}
@Composable
private fun PermissionStatusRow(icon:ImageVector,title:String,status:String,
  subtitle:String,granted:Boolean,optional:Boolean=false,onClick:(()->Unit)?=null) {
  Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically,
    horizontalArrangement=Arrangement.spacedBy(10.dp)) {
    Icon(icon,null,tint=MaterialTheme.colorScheme.onSurfaceVariant,
      modifier=Modifier.size(20.dp))
    Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(2.dp)) {
      Text(title,style=MaterialTheme.typography.bodyMedium,fontWeight=FontWeight.Medium)
      Text(subtitle,style=MaterialTheme.typography.bodySmall,
        color=MaterialTheme.colorScheme.onSurfaceVariant)
    }
    Column(horizontalAlignment=Alignment.End,verticalArrangement=Arrangement.spacedBy(3.dp)) {
      Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(3.dp)) {
        Icon(when {
          optional -> Icons.Rounded.RemoveCircleOutline
          granted -> Icons.Rounded.CheckCircle
          else -> Icons.Rounded.Info
        }, contentDescription=null,modifier=Modifier.size(15.dp),
          tint=if(granted && !optional)MaterialTheme.colorScheme.primary
            else MaterialTheme.colorScheme.onSurfaceVariant)
        Text(status,style=MaterialTheme.typography.labelSmall,
          color=MaterialTheme.colorScheme.onSurfaceVariant)
      }
      if(!granted && onClick!=null) TextButton(onClick=onClick,contentPadding=PaddingValues(0.dp)) {
        Text(if(title=="Secure settings")"Retry" else "Open",style=MaterialTheme.typography.labelMedium)
      }
    }
  }
}
@Composable
private fun DiagnosticSwitch(title:String,checked:Boolean,subtitle:String,onChecked:(Boolean)->Unit) {
  Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically) {
    Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(3.dp)) {
      Text(title,style=MaterialTheme.typography.bodyMedium)
      Text(subtitle,style=MaterialTheme.typography.bodySmall,
        color=MaterialTheme.colorScheme.onSurfaceVariant)
    }
    Spacer(Modifier.width(8.dp))
    Switch(checked=checked,onCheckedChange=onChecked)
  }
}
private fun openSystemSettings(context:Context,action:String,appPackage:String?=null) {
  val intent=Intent(action).apply {
    if(appPackage!=null) putExtra(Settings.EXTRA_APP_PACKAGE,appPackage)
    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
  }
  runCatching { context.startActivity(intent) }
}
private fun openWirelessDebugging(context:Context) {
  val args=Bundle().apply { putString(":settings:fragment_args_key","toggle_adb_wireless") }
  val intent=Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS)
    .putExtra(":settings:fragment_args_key","toggle_adb_wireless")
    .putExtra(":settings:show_fragment_args",args)
    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
  runCatching { context.startActivity(intent) }
}
