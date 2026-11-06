plugins {
  id("com.android.application")
  id("org.jetbrains.kotlin.plugin.compose")
}
android {
  namespace = "com.n2k02.seq"
  compileSdk = 37
  defaultConfig {
    applicationId = "com.n2k02.seq"
    minSdk = 29
    targetSdk = 35
    // Each CI build gets a monotonically increasing version code for the shared updater.
    val buildNumber = providers.gradleProperty("seqBuild").orNull?.toIntOrNull()
    versionCode = buildNumber?.takeIf { it > 2 } ?: 3
    versionName = "0.3.0-experimental" + (buildNumber?.let { ".$it" } ?: "")
    testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
  }
  buildFeatures { compose = true; buildConfig = true; aidl = true }
  compileOptions {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
  }
  ndkVersion = "27.3.13750724"
  defaultConfig {
    ndk { abiFilters += listOf("arm64-v8a", "x86_64") }
  }
  externalNativeBuild { cmake { path = file("../dsp/CMakeLists.txt") } }
  packaging { resources { excludes += "/META-INF/{AL2.0,LGPL2.1}" } }
}
dependencies {
  val composeBom = platform("androidx.compose:compose-bom:2025.12.00")
  implementation(composeBom)
  implementation("androidx.activity:activity-compose:1.12.4")
  implementation("androidx.compose.material3:material3:1.5.0-beta01")
  implementation("dev.n2k02:theme:0.1.4")
  implementation("dev.n2k02:updater:0.1.19")
  implementation("androidx.compose.material:material-icons-extended")
  implementation("androidx.compose.foundation:foundation")
  implementation("androidx.compose.ui:ui")
  implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.9.4")
  implementation("androidx.core:core-ktx:1.17.0")
  implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
  implementation("androidx.work:work-runtime-ktx:2.10.5")
  // Direct localhost ADB; no Shizuku runtime or daemon.
  implementation("com.github.MuntashirAkon:libadb-android:3.1.1")
  implementation("org.bouncycastle:bcprov-jdk15to18:1.81")
  implementation("org.bouncycastle:bcpkix-jdk15to18:1.81")
  implementation("org.conscrypt:conscrypt-android:2.7.0")
  implementation("com.google.mlkit:genai-prompt:1.0.0-beta4")
  // Actual local music browsing and playback for phone + Android Auto.
  val media3 = "1.7.1"
  implementation("androidx.media3:media3-exoplayer:$media3")
  implementation("androidx.media3:media3-session:$media3")
  implementation("com.google.guava:guava:33.4.8-android")
  testImplementation("junit:junit:4.13.2")
}
