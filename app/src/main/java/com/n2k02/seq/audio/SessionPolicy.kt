package com.n2k02.seq.audio

import com.n2k02.seq.model.AudioProfile
import com.n2k02.seq.model.EngineSettings

/** Pure logic for safe attachment and idempotent session lifecycle. */
object SessionPolicy {
  fun canAttach(s: EngineSettings, sessionId: Int, alreadyAttached: Boolean) =
    s.enabled && s.profile != AudioProfile.PURE && !s.allMedia && sessionId > 0 && !alreadyAttached
  /** Android requires package metadata on audio effect control broadcasts. */
  fun isExternalSession(id:Int,sourcePackage:String?,ownPackage:String):Boolean =
    id>0 && !sourcePackage.isNullOrBlank() && sourcePackage!=ownPackage
  /** Per-app broadcast sessions are only used when all-media processing is off (no double EQ). */
  fun acceptsSessionBroadcasts(s: EngineSettings) = s.enabled && !s.allMedia
  /** The output-mix effect runs whenever the engine is on in all-media mode (not Pure). */
  fun shouldProcessAllMedia(s: EngineSettings) =
    s.enabled && s.allMedia && s.profile != AudioProfile.PURE
}
