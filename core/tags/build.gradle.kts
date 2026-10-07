plugins {
  id("voice.library")
  alias(libs.plugins.metro)
}

kotlin {
  explicitApi()
}

android {
  // For res/xml/nfc_tech_filter.xml, which the launch activity's manifest entry names.
  androidResources {
    enable = true
  }
}

dependencies {
  implementation(projects.core.data.api)
  implementation(projects.core.sync)
  implementation(projects.core.playback)
  implementation(projects.core.initializer)
  implementation(projects.core.logging.api)
  implementation(projects.core.common)
  implementation(projects.core.strings)

  implementation(libs.datastore)
  implementation(libs.androidxCore)

  testImplementation(libs.junit)
}
