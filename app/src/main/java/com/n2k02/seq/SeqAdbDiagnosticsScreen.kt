package com.n2k02.seq

import android.content.Context
import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.OutlinedButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.n2k02.seq.adb.LocalAdbDiagnostics
import com.n2k02.seq.adb.SeqPairingAccessibilityService
import dev.n2k02.adb.core.AdbConnectionSettingsPage
import dev.n2k02.adb.core.AdbDiagnosticsPage

/** Canonical shared diagnostics first, with SEQ-specific audio detail only. */
@Composable
internal fun SeqAdbDiagnosticsScreen(adb: LocalAdbDiagnostics) {
    AdbDiagnosticsPage(adb.sharedCore)
    val state by adb.state.collectAsState()
    if (state.details.isNotEmpty()) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
            Text("SEQ audio diagnostics", style = MaterialTheme.typography.titleSmall)
            Text(state.details, style = MaterialTheme.typography.bodySmall)
        }
    }
}

/** Exactly the same two ADB Settings cards and five switches as all other apps. */
@Composable
internal fun SeqAdbConnectionScreen(context: Context, adb: LocalAdbDiagnostics, permissionRevision: Int) {
    val enabled = remember(permissionRevision) { SeqPairingAccessibilityService.isEnabled(context) }
    AdbConnectionSettingsPage(
        adb = adb.sharedCore,
        accessibilityEnabled = enabled,
        onOpenAccessibility = {
            context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        },
    )
    val probe by adb.playerProbe.collectAsState()
    val grant by adb.dumpGrant.collectAsState()
    Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
        Text("Advanced player tracking", style = MaterialTheme.typography.titleSmall)
        Text("Read-only ADB diagnostics. Bluetooth hardware offload can still bypass SEQ.",
            style = MaterialTheme.typography.bodySmall)
        OutlinedButton(onClick = adb::inspectPlayers) { Text("Inspect audio players") }
        Text(probe, style = MaterialTheme.typography.bodySmall)
        OutlinedButton(onClick = adb::grantDumpPermission) { Text("Request DUMP via ADB") }
        Text(grant, style = MaterialTheme.typography.bodySmall)
    }
}
