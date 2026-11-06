package com.n2k02.seq.audio

import org.junit.Assert.*
import org.junit.Test

class SeqOwnedAudioSessionsTest {
 @Test fun ownSessionCannotBeProcessedTwice() {
   SeqOwnedAudioSessions.add(789123)
   assertTrue(SeqOwnedAudioSessions.contains(789123))
   SeqOwnedAudioSessions.remove(789123)
   assertFalse(SeqOwnedAudioSessions.contains(789123))
 }
 @Test fun ignoresInvalidSessionIds() {
   SeqOwnedAudioSessions.add(0)
   SeqOwnedAudioSessions.add(-1)
   assertFalse(SeqOwnedAudioSessions.contains(0))
   assertFalse(SeqOwnedAudioSessions.contains(-1))
 }
 @Test fun sourceOwnershipIsStrict() {
   assertFalse(SessionPolicy.isExternalSession(55,"com.n2k02.seq","com.n2k02.seq"))
   assertFalse(SessionPolicy.isExternalSession(55,null,"com.n2k02.seq"))
   assertFalse(SessionPolicy.isExternalSession(0,"external.player","com.n2k02.seq"))
   assertTrue(SessionPolicy.isExternalSession(55,"external.player","com.n2k02.seq"))
 }
}
