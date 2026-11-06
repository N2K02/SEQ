package com.n2k02.seq

import android.content.Context
import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.n2k02.seq.adb.LocalAdbDiagnostics
import com.n2k02.seq.adb.SeqPairingAccessibilityService
import com.n2k02.seq.audio.EngineStatus

/** VRec Settings > ADB connection parity: two cards, five choices, no manual setup controls. */
@Composable
internal fun SeqAdbDiagnosticsScreen(
  context: Context,
  adb: LocalAdbDiagnostics,
  engine: EngineStatus,
  permissionRevision: Int
) {
  val pref=adb.preferences
  var autoPair by remember { mutableStateOf(pref.autoPairCode) }
  var watchdog by remember { mutableStateOf(pref.watchdog) }
  var startOnBoot by remember { mutableStateOf(pref.startOnBoot) }
  var restore by remember { mutableStateOf(pref.restoreAfterRestart) }
  var tcp by remember { mutableStateOf(pref.tcpMode) }
  val accessibilityEnabled=remember(permissionRevision) {
    SeqPairingAccessibilityService.isEnabled(context)
  }

  // Mirror VRec's Home connection details without treating a saved
  // authorization as a currently connected shell. Diagnostics is on demand.
  val status by adb.state.collectAsState()
  AdbSettingCard("SEQ ADB Details", Icons.Rounded.Info) {
    Text(
      when {
        status.busy -> "Checking local ADB…"
        status.connected && status.uidVerified -> "Connected • shell UID 2000 verified"
        status.uidVerified -> "UID 2000 previously verified • disconnected"
        else -> "ADB shell UID 2000 not verified"
      },
      style=MaterialTheme.typography.bodyLarge,
      fontWeight=FontWeight.SemiBold,
    )
    Text("Transport: "+status.transport, style=MaterialTheme.typography.bodySmall)
    Text("Last verified: "+(if(status.verifiedAt>0L)
      java.text.DateFormat.getDateTimeInstance(
        java.text.DateFormat.SHORT,java.text.DateFormat.SHORT
      ).format(java.util.Date(status.verifiedAt))
      else "Never"),
      style=MaterialTheme.typography.bodySmall)
    Text("Previously verified port: "+(status.lastVerifiedPort?.toString() ?: "None"),
      style=MaterialTheme.typography.bodySmall)
    Text("Automatic discovery: "+(if(status.scanning) "Scanning" else "Idle")+
      " • Pairing port: "+(status.pairingPort?.toString() ?: "Not detected")+
      " • Connection port: "+(status.connectPort?.toString() ?: "Not detected"),
      style=MaterialTheme.typography.bodySmall)
    Text(status.message,style=MaterialTheme.typography.bodySmall,
      color=MaterialTheme.colorScheme.onSurfaceVariant)
  }

  AdbSettingCard("Pairing & recovery",Icons.Rounded.SettingsInputAntenna) {
    AdbSettingToggle("Auto-detect pairing code",
      "Read the Android pairing dialog with Accessibility.",autoPair) {
      autoPair=it
      adb.setAutoPairCode(it)
    }
    if(autoPair && !accessibilityEnabled) {
      OutlinedButton(onClick={
        runCatching {
          context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
      }) { Text("Enable Accessibility") }
    }
    HorizontalDivider()
    AdbSettingToggle("Connection watchdog",
      "Check connection health and recover automatically when possible.",watchdog) {
      watchdog=it
      adb.setWatchdog(it)
    }
    AdbSettingToggle("Start on boot",
      "Try to restore after restarting and unlocking.",startOnBoot) {
      startOnBoot=it
      adb.setStartOnBoot(it)
    }
  }
  AdbSettingCard("Connection & security",Icons.Rounded.Shield) {
    AdbSettingToggle("Restore after restart",
      "Restore Wireless debugging after reboot using saved pairing. Requires Android permission.",restore) {
      restore=it
      adb.setRestoreAfterRestart(it)
    }
    AdbSettingToggle("Keep ADB without Wi-Fi (TCP/5555)",
      "Keeps ADB available without Wi-Fi. TCP/5555 may be reachable on your network.",tcp) {
      tcp=it
      adb.setTcpMode(it)
    }
  }
}

@Composable
private fun AdbSettingCard(title:String,icon:ImageVector,body:@Composable ColumnScope.()->Unit) {
  Card(modifier=Modifier.fillMaxWidth(),
    colors=CardDefaults.cardColors(containerColor=MaterialTheme.colorScheme.surfaceContainerLow),
    shape=MaterialTheme.shapes.large) {
    Column(Modifier.fillMaxWidth().padding(16.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
      Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(10.dp)) {
        Icon(icon,null,tint=MaterialTheme.colorScheme.primary)
        Text(title,style=MaterialTheme.typography.titleMedium,fontWeight=FontWeight.SemiBold)
      }
      body()
    }
  }
}

@Composable
private fun AdbSettingToggle(title:String,subtitle:String,checked:Boolean,onChecked:(Boolean)->Unit) {
  Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically) {
    Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(3.dp)) {
      Text(title,style=MaterialTheme.typography.bodyLarge)
      Text(subtitle,style=MaterialTheme.typography.bodySmall,
        color=MaterialTheme.colorScheme.onSurfaceVariant)
    }
    Spacer(Modifier.width(8.dp))
    Switch(checked=checked,onCheckedChange=onChecked)
  }
}
