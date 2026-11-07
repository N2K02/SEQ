package com.n2k02.seq

import android.app.Activity
import android.content.Context
import android.os.Build
import com.n2k02.seq.audio.SeqRuntime
import dev.n2k02.updater.UpdateConfig
import dev.n2k02.updater.UpdateInstallGuard
import dev.n2k02.updater.UpdaterController
import dev.n2k02.updater.PublishedUpdateSource
import kotlinx.coroutines.flow.MutableStateFlow

/** Shared N2K02 updater with strict playback/engine install guard. */
object SeqUpdater {
  private lateinit var app: Context
  private var instance:UpdaterController? = null
  val experimentalEnabled=MutableStateFlow(false)
  val installGuard=UpdateInstallGuard {
    !SeqRuntime.status.value.running
  }
  private fun prefs()=app.getSharedPreferences("seq_updater",Context.MODE_PRIVATE)
  fun init(context:Context) {
    app=context.applicationContext
    if(instance!=null) return
    experimentalEnabled.value=prefs().getBoolean("experimentalEnabled",false)
    instance=create()
  }
  @Suppress("DEPRECATION")
  private fun currentVersion():Long {
    val info=app.packageManager.getPackageInfo(app.packageName,0)
    return if(Build.VERSION.SDK_INT>=28) info.longVersionCode else info.versionCode.toLong()
  }
  private fun create()=UpdaterController(
    context=app,
    config=UpdateConfig(
      repository="N2K02/SEQ",packageName=app.packageName,
      installedVersionCode=currentVersion(),
      experimentalEnabled=experimentalEnabled.value
    ),
    publishedSource=PublishedUpdateSource("https://n2k02.github.io/seq/updates"),
    offerPreferences=prefs()
  )
  val controller:UpdaterController get()=requireNotNull(instance)
  fun setExperimentalEnabled(enabled:Boolean) {
    if(enabled==experimentalEnabled.value) return
    controller.cancel()
    experimentalEnabled.value=enabled
    prefs().edit().putBoolean("experimentalEnabled",enabled).apply()
    instance=create()
    controller.check(manual=true)
  }
  fun onResume(activity:Activity)=controller.onHostResume(activity,installGuard)
}
