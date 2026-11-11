package com.n2k02.seq.audio.dsp

import android.content.Context
import android.os.Build
import com.n2k02.seq.model.EngineSettings

/** Process-wide view of SEQ DSP so the platform fallback never processes SEQ's own output. */
object SeqDspRuntime {
  @Volatile var engine: SeqDspEngine? = null
    internal set

  /** True while SEQ DSP holds capture: the output-mix effect must stay released. */
  val active: Boolean get() = engine?.isRunning == true

  fun requestScan() { engine?.requestScan() }

  /** Set by the engine service: re-plan SEQ DSP after route, volume, adaptive or settings changes. */
  @Volatile var retune: (() -> Unit)? = null

  /** Prerequisites that SEQ can verify locally (the projection token is requested separately). */
  data class Readiness(val recordAudio: Boolean, val dump: Boolean) {
    val ready: Boolean get() = recordAudio && dump
  }

  fun readiness(context: Context) = Readiness(
    recordAudio = context.checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) ==
      android.content.pm.PackageManager.PERMISSION_GRANTED,
    dump = SeqServiceDumper.granted(context)
  )

  /** Session IDs appear in `dumpsys audio` from Android 12; older devices keep the Android effect. */
  val supported: Boolean get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S

  /** SEQ DSP is the default engine whenever the device can run it. */
  fun wanted(s: EngineSettings): Boolean = s.enabled && s.dspEngine && supported
}
