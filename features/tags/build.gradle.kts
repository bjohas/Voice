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
  implementation(projects.core.sync)
  implementation(projects.core.tags)
  implementation(projects.navigation)

  implementation(libs.datastore)
  implementation(libs.androidxCore)
  implementation(libs.coil)
  implementation(libs.lifecycle.compose)
}
