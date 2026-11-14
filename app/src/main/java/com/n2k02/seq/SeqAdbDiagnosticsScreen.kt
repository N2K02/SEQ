package com.n2k02.seq

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import com.n2k02.seq.audio.SeqPcmCaptureProbeService
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
import com.n2k02.seq.audio.SeqEffectCapabilities
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
}

/** Read-only and explicitly user-triggered audio diagnostics, separate from ADB setup. */
@Composable
internal fun SeqAudioDiagnosticsScreen(context: Context, adb: LocalAdbDiagnostics) {
    val probe by adb.playerProbe.collectAsState()
    val effects by adb.effectProbe.collectAsState()
    val audit by adb.exclusiveAudit.collectAsState()
    val pcmProbe by SeqPcmCaptureProbeService.status.collectAsState()
    val projectionManager = remember(context) { context.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager }
    val captureLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK && result.data != null) {
            val service = Intent(context, SeqPcmCaptureProbeService::class.java)
                .setAction(SeqPcmCaptureProbeService.START)
                .putExtra(SeqPcmCaptureProbeService.EXTRA_CAPTURE_DATA, result.data)
            ContextCompat.startForegroundService(context, service)
        }
    }
    val audioPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) captureLauncher.launch(projectionManager.createScreenCaptureIntent())
    }
    val sdkEffects by SeqEffectCapabilities.summary.collectAsState()
    val grant by adb.dumpGrant.collectAsState()
    Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
        Text("Advanced player tracking", style = MaterialTheme.typography.titleSmall)
        Text("Read-only ADB diagnostics. Bluetooth hardware offload can still bypass SEQ.",
            style = MaterialTheme.typography.bodySmall)
        OutlinedButton(onClick = adb::inspectPlayers) { Text("Inspect audio players") }
        Text(probe, style = MaterialTheme.typography.bodySmall)
        OutlinedButton(onClick = adb::grantDumpPermission) { Text("Request DUMP via ADB") }
        Text(grant, style = MaterialTheme.typography.bodySmall)
        Text("System effects and audio policy", style = MaterialTheme.typography.titleSmall)
        OutlinedButton(onClick = adb::inspectAudioEffects) { Text("Inspect reverb, spatial and offload") }
        Text(effects, style = MaterialTheme.typography.bodySmall)
        Text("Exclusive PCM feasibility", style = MaterialTheme.typography.titleSmall)
        OutlinedButton(onClick = adb::auditExclusivePcm) { Text("Audit routing privileges") }
        Text(audit, style = MaterialTheme.typography.bodySmall)
        Text("Third-party PCM compatibility", style = MaterialTheme.typography.titleSmall)
        Text("User-approved capture test only; audio is not replayed or recorded to files.",
            style = MaterialTheme.typography.bodySmall)
        OutlinedButton(onClick = {
            if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
                PackageManager.PERMISSION_GRANTED) {
                captureLauncher.launch(projectionManager.createScreenCaptureIntent())
            } else audioPermission.launch(Manifest.permission.RECORD_AUDIO)
        }) { Text("Test third-party PCM capture") }
        OutlinedButton(onClick = {
            context.stopService(Intent(context, SeqPcmCaptureProbeService::class.java))
        }) { Text("Stop PCM test") }
        Text(pcmProbe, style = MaterialTheme.typography.bodySmall)
        OutlinedButton(onClick = { SeqEffectCapabilities.inspect() }) { Text("Inspect Android effect implementations") }
        OutlinedButton(onClick = { SeqEffectCapabilities.summary.value = SeqEffectCapabilities.probeAuxReverb() }) {
            Text("Check auxiliary concert-hall reverb")
        }
        Text(sdkEffects, style = MaterialTheme.typography.bodySmall)
    }
}
