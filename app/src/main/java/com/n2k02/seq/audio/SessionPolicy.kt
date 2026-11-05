package com.n2k02.seq.audio

import com.n2k02.seq.model.AudioProfile
import com.n2k02.seq.model.EngineSettings

/** Pure logic for safe attachment and idempotent session lifecycle. */
object SessionPolicy {
  fun canAttach(s: EngineSettings, sessionId: Int, alreadyAttached: Boolean) =
    s.enabled && s.profile != AudioProfile.PURE && sessionId > 0 && !alreadyAttached
}
