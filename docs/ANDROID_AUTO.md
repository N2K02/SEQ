# Android Auto support

SEQ's phone application declares a real Media3 MediaLibraryService and MediaLibrarySession. Its car browser lists only music files the user imported through Android's Storage Access Framework; it never claims to access other apps' streams.

- Native DSP is applied to supported decoded stereo PCM16. Unsupported formats use platform playback without our native correction.
- MediaLibrarySession handles library root, children, item resolution, play commands and player controls. There is no UI with EQ sliders on the car display.
- Android Auto host eligibility and actual routing remain unverified until tested with a head unit or Desktop Head Unit.
- Third-party apps in Android Auto (Spotify, YouTube Music, …) are processed by the **All media** output-mix effect; the car tone is selected automatically when Android Auto enters car UI mode. Verify on a head unit/DHU that projection audio passes through the output mix on your device. Android Automotive OS requires a distinct application target and is not claimed by this phone APK.
- Selecting a car profile by device name is based on connected devices, not proof of current route.
