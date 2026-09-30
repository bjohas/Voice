plugins {
  id("voice.library")
  alias(libs.plugins.metro)
  alias(libs.plugins.kotlin.serialization)
}

kotlin {
  explicitApi()
}

// The server's API key, as a default the app copies into its own settings on
// first start (SeedServerConfig); after that the phone remembers it, and a
// build without one still works there. It comes from the environment, a Gradle
// property, or the server's gitignored descriptor (VOICE_SERVER_TOKEN_FILE or
// voice.serverTokenFile) -- never from a file in this repository -- and ends up
// in the APK, which is therefore not for sharing. Absent, it is empty and the
// server dialog is the only way in.
val serverToken: Provider<String> = providers.environmentVariable("VOICE_SERVER_TOKEN")
  .orElse(providers.gradleProperty("voice.serverToken"))
  .orElse(
    providers.fileContents(
      objects.fileProperty().fileProvider(
        providers.gradleProperty("voice.serverTokenFile")
          .orElse(providers.environmentVariable("VOICE_SERVER_TOKEN_FILE"))
          .map { File(it) },
      ),
    ).asText.map { text ->
      val described = groovy.json.JsonSlurper().parseText(text) as Map<*, *>
      ((described["credentials"] as? Map<*, *>)?.get("password") as? String).orEmpty()
    },
  )
  .orElse("")

// The server's address, likewise from the environment (voice-b's build-env.sh
// sets VOICE_CONTENT_SERVER) or a Gradle property, never from this repository.
val serverUrl: Provider<String> = providers.environmentVariable("VOICE_CONTENT_SERVER")
  .orElse(providers.gradleProperty("voice.serverUrl"))
  .orElse("")

android {
  buildFeatures {
    buildConfig = true
  }
  defaultConfig {
    if (serverToken.get().isEmpty() || serverUrl.get().isEmpty()) {
      logger.warn(
        "voice: no built-in server address and/or API key. A phone that has them saved keeps using them; " +
          "a fresh install asks for them in Server books (cogwheel).",
      )
    }
    val escaped = serverToken.get().replace("\\", "\\\\").replace("\"", "\\\"")
    buildConfigField("String", "SERVER_TOKEN", "\"$escaped\"")
    val url = serverUrl.get().replace("\\", "\\\\").replace("\"", "\\\"")
    buildConfigField("String", "SERVER_URL", "\"$url\"")
  }
}

dependencies {
  implementation(projects.core.data.api)
  implementation(projects.core.scanner)
  implementation(projects.core.initializer)
  implementation(projects.core.logging.api)

  implementation(libs.okhttp)
  implementation(libs.serialization.json)
  implementation(libs.datastore)

  testImplementation(libs.junit)
  testImplementation(libs.coroutines.test)
  testImplementation(libs.okhttp.mockwebserver)
}
