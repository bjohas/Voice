plugins {
  id("voice.library")
  id("voice.compose")
  alias(libs.plugins.metro)
}

dependencies {
  implementation(projects.core.common)
  implementation(projects.core.ui)
  implementation(projects.core.strings)
  implementation(projects.core.data.api)
  implementation(projects.core.speaker)
  implementation(projects.core.playback)
  implementation(projects.navigation)

  implementation(libs.datastore)
  implementation(libs.androidxCore)

  testImplementation(libs.junit)
}
