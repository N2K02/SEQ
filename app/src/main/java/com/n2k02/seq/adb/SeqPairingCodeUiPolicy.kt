package com.n2k02.seq.adb

/** VRec pairing dialog policy, restricted to Android's own Settings packages. */
internal object SeqPairingCodeUiPolicy {
  private val settingsPackages=setOf("com.android.settings","com.google.android.settings",
    "com.android.tv.settings","com.samsung.android.settings")
  private val wholeCode=Regex("""[0-9]{6}""")
  private val inlineCode=Regex("""(?<![0-9])[0-9]{6}(?![0-9])""")
  fun isSettingsPackage(packageName:String?):Boolean =
    packageName!=null && packageName in settingsPackages
  fun extractCode(labels:List<String>):String? {
    if(labels.none { it.contains("pairing code",ignoreCase=true) ||
        it.contains("pair with device",ignoreCase=true) ||
        it.contains("pair device with",ignoreCase=true) }) return null
    labels.asSequence().map { it.trim() }.firstOrNull(wholeCode::matches)?.let { return it }
    return labels.asSequence().filter { it.contains("pairing code",ignoreCase=true) }
      .mapNotNull { inlineCode.find(it)?.value }.firstOrNull()
  }
}
