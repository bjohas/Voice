plugins {
  id("voice.library")
  alias(libs.plugins.metro)
}

kotlin {
  explicitApi()
}

dependencies {
  implementation(projects.core.data.api)
  implementation(projects.core.playback)
  implementation(projects.core.initializer)
  implementation(projects.core.logging.api)

  implementation(libs.datastore)

  testImplementation(libs.junit)
  testImplementation(libs.coroutines.test)
}
