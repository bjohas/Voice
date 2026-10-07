package voice.core.data

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
public enum class ThemeColorScheme {
  /** bVoice's own, the default: the launcher icon's orange. */
  @SerialName("BVoiceOrange")
  BVoiceOrange,

  @SerialName("VoiceBlue")
  VoiceBlue,

  @SerialName("Dynamic")
  Dynamic,
}
