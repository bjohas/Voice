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
  implementation(projects.core.common)

  implementation(libs.datastore)
  implementation(libs.androidxCore)

  testImplementation(libs.junit)
  testImplementation(libs.coroutines.test)
}
