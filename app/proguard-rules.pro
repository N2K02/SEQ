# Android starts manifest-registered services/receivers by their class names.
-keep class com.n2k02.seq.adb.SeqPairingAccessibilityService { public <init>(); }
-keep class com.n2k02.seq.adb.SeqAdbBootReceiver { public <init>(); }
-keep class com.n2k02.seq.audio.SeqEngineService { public <init>(); }
# Do not globally disable R8 warnings or obfuscation; fix missing references.

# Match the proven VRec/LocalAdb release fix: WorkManager uses reflection to create
# Room's WorkDatabase_Impl before Application.onCreate. R8 full mode must keep its
# public no-argument constructor; otherwise AndroidX Startup crashes on launch.
-keep class androidx.work.impl.WorkDatabase_Impl {
    public <init>();
}

# Other generated Room databases may use the same reflective constructor contract.
-keep class * extends androidx.room.RoomDatabase {
    public <init>();
}

# WorkManager deserializes InputMergers using class names from persistent work.
-keep class * extends androidx.work.InputMerger {
    <init>();
}
