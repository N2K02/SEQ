# JNI entry points are resolved by full class and method name from native C++.
-keep class com.n2k02.seq.audio.NativeDsp {
    *;
}
# Android starts manifest-registered services/receivers by their class names.
-keep class com.n2k02.seq.adb.SeqPairingAccessibilityService { public <init>(); }
-keep class com.n2k02.seq.adb.SeqAdbBootReceiver { public <init>(); }
-keep class com.n2k02.seq.audio.SeqEngineService { public <init>(); }
-keep class com.n2k02.seq.audio.SeqReferencePlayerService { public <init>(); }
-keep class com.n2k02.seq.audio.SeqMediaLibraryService { public <init>(); }
# Do not globally disable R8 warnings or obfuscation; fix missing references.
