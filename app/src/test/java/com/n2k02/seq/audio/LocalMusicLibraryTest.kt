package com.n2k02.seq.audio

import org.junit.Assert.*
import org.junit.Test

class LocalMusicLibraryTest {
 @Test fun onlyRecognizedAudioExtensions() {
   assertTrue(LocalMusicLibrary.supported("lossless.FLAC"))
   assertTrue(LocalMusicLibrary.supported("track.wav"))
   assertTrue(LocalMusicLibrary.supported("sound.opus"))
   assertFalse(LocalMusicLibrary.supported("video.mp4"))
   assertFalse(LocalMusicLibrary.supported("exploit.apk"))
 }
}
