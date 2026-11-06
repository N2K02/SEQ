package com.n2k02.seq.audio

import java.util.concurrent.ConcurrentHashMap

/**
 * Prevent own decoded media playback from receiving a *second* platform EQ
 * after the native DSP. Package check also covers timing before listener fires.
 */
object SeqOwnedAudioSessions {
  private val ids=ConcurrentHashMap.newKeySet<Int>()
  fun add(id:Int) { if(id>0) ids.add(id) }
  fun remove(id:Int) { if(id>0) ids.remove(id) }
  fun contains(id:Int):Boolean=id>0 && ids.contains(id)
}
