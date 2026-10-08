package voice.core.common

import kotlin.time.Instant

interface AppInfoProvider {
  val versionName: String

  /** bVoice: the Voice release it is built on, e.g. "26.6.1+106"; empty if unknown. */
  val basedOnVoice: String get() = ""

  val analyticsIncluded: Boolean

  val supportDevelopmentIncluded: Boolean

  val installTime: Instant
}
