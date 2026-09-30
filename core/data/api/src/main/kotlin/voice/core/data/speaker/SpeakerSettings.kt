package voice.core.data.speaker

import kotlinx.serialization.Serializable

/**
 * The pillow speaker: which one it is, and what bVoice does about it.
 *
 * [address] and [associationId] come from pairing it through the companion
 * device picker. [lastOwnDisconnectMillis] is when bVoice last dropped it
 * itself, so a speaker that reconnects on its own is not taken for a request
 * to play.
 */
@Serializable
public data class SpeakerSettings(
  val address: String? = null,
  val name: String? = null,
  val associationId: Int? = null,
  val autoPlay: Boolean = true,
  val disconnectAfterPause: Boolean = true,
  val disconnectDelayMinutes: Int = 5,
  val lastOwnDisconnectMillis: Long = 0,
  /** Taps on the speaker's back/forward closer than this count as one gesture (BUTTON-PRESSES.md). */
  val tapSpacingMillis: Int = 400,
)
