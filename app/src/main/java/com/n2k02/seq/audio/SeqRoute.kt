package com.n2k02.seq.audio

import android.app.UiModeManager
import android.content.Context
import android.content.res.Configuration
import android.media.AudioDeviceInfo
import android.media.AudioManager
import com.n2k02.seq.model.EngineSettings

/** Pure car-profile decision so it can be unit tested. */
object CarPolicy {
  fun isCar(manual: Boolean, automatic: Boolean, carMode: Boolean, namedDeviceConnected: Boolean) =
    manual || (automatic && (carMode || namedDeviceConnected))
}

/** One route decision for every SEQ path (native DSP, session effects, all-media effect). */
object SeqRoute {
  /** Android Auto puts the phone in car UI mode while projecting. */
  fun isCarMode(context: Context): Boolean = runCatching {
    (context.getSystemService(Context.UI_MODE_SERVICE) as UiModeManager).currentModeType ==
      Configuration.UI_MODE_TYPE_CAR
  }.getOrDefault(false)

  fun isCar(context: Context, s: EngineSettings): Boolean {
    if (s.carManual) return true
    if (!s.automaticCarProfile) return false
    val named = s.carDeviceName.isNotBlank() && outputs(context).any {
      it.productName?.toString()?.equals(s.carDeviceName, ignoreCase = true) == true
    }
    return CarPolicy.isCar(false, true, isCarMode(context), named)
  }

  fun describe(context: Context, car: Boolean): String {
    if (car) return if (isCarMode(context)) "Car profile • Android Auto" else "Car profile"
    return outputs(context).firstOrNull {
      it.type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP || it.type == AudioDeviceInfo.TYPE_USB_DEVICE ||
        it.type == AudioDeviceInfo.TYPE_USB_HEADSET || it.type == AudioDeviceInfo.TYPE_WIRED_HEADPHONES ||
        it.type == AudioDeviceInfo.TYPE_BLE_HEADSET
    }?.productName?.toString()?.take(80) ?: "Phone / other output"
  }

  private fun outputs(context: Context): Array<AudioDeviceInfo> = runCatching {
    (context.getSystemService(Context.AUDIO_SERVICE) as AudioManager)
      .getDevices(AudioManager.GET_DEVICES_OUTPUTS)
  }.getOrDefault(emptyArray())
}
