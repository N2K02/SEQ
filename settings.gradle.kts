pluginManagement {
  repositories { google(); mavenCentral(); gradlePluginPortal() }
}
dependencyResolutionManagement {
  repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
  repositories {
    google()
    mavenCentral()
    maven { url = uri("https://jitpack.io") }
    val privateToken=providers.environmentVariable("N2K02_PACKAGES_TOKEN").orNull
    require(!privateToken.isNullOrBlank()) {
      "N2K02_PACKAGES_TOKEN (read:packages) is required for n2k02theme and n2k02updater"
    }
    listOf("n2k02theme" to "theme","n2k02updater" to "updater",
      "n2k02AdbCoreLibrary" to "adb-core").forEach { (repository, module) ->
      maven {
        name="N2K02_${module}"
        url=uri("https://maven.pkg.github.com/N2K02/$repository")
        credentials {
          username=providers.environmentVariable("N2K02_PACKAGES_USER").orElse("N2K02").get()
          password=privateToken
        }
        content { includeModule("dev.n2k02",module) }
      }
    }
  }
}
rootProject.name="SEQ"
include(":app")
