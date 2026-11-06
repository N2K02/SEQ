# Android starts manifest-registered services/receivers by their class names.
-keep class com.n2k02.seq.adb.SeqPairingAccessibilityService { public <init>(); }
-keep class com.n2k02.seq.adb.SeqAdbBootReceiver { public <init>(); }
-keep class com.n2k02.seq.audio.SeqEngineService { public <init>(); }
# Do not globally disable R8 warnings or obfuscation; fix missing references.
