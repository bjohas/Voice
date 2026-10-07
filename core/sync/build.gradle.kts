plugins {
  id("voice.library")
  alias(libs.plugins.metro)
  alias(libs.plugins.kotlin.serialization)
}

kotlin {
  explicitApi()
}

// No server address or API key is built in: they are entered on the phone
// (Server books → cogwheel) or set up from the server's page with a QR code
// (ServerSetupActivity), and kept in the app's own settings.

dependencies {
  implementation(projects.core.data.api)
  implementation(projects.core.scanner)
  implementation(projects.core.initializer)
  implementation(projects.core.logging.api)
  implementation(projects.core.common)
  implementation(projects.core.strings)

  implementation(libs.okhttp)
  implementation(libs.serialization.json)
  implementation(libs.datastore)

  testImplementation(libs.junit)
  testImplementation(libs.coroutines.test)
  testImplementation(libs.okhttp.mockwebserver)
}
