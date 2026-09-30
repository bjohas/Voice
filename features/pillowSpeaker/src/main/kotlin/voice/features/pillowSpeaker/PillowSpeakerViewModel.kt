package voice.features.pillowSpeaker

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.datastore.core.DataStore
import dev.zacsweers.metro.Inject
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import voice.core.common.DispatcherProvider
import voice.core.common.MainScope
import voice.core.data.speaker.SpeakerSettings
import voice.core.data.store.SpeakerSettingsStore
import voice.core.playback.session.KeyPress
import voice.core.playback.session.MediaKeyLog
import voice.core.speaker.DisconnectResult
import voice.core.speaker.DisconnectSchedule
import voice.core.speaker.PairedDevice
import voice.core.speaker.SpeakerLink
import voice.core.speaker.SpeakerPresence
import voice.navigation.Navigator

data class PillowSpeakerViewState(
  val settings: SpeakerSettings,
  val hasPermission: Boolean,
  val connected: Boolean?,
  val lastResult: DisconnectResult?,
  val pairingError: String?,
  /** The paired devices to choose from, while the chooser is open. */
  val chooser: List<PairedDevice>?,
  val checking: Boolean,
  val checkedAtMillis: Long?,
  /** At the last Check: when auto-disconnect was due, or null if nothing was pending. */
  val disconnectAtMillis: Long?,
  /** The tap tester: the last key presses, newest first. */
  val presses: List<KeyPress>,
)

@Inject
class PillowSpeakerViewModel(
  @SpeakerSettingsStore
  private val settingsStore: DataStore<SpeakerSettings>,
  private val link: SpeakerLink,
  private val presence: SpeakerPresence,
  private val navigator: Navigator,
  private val mediaKeyLog: MediaKeyLog,
  private val disconnectSchedule: DisconnectSchedule,
  dispatcherProvider: DispatcherProvider,
) {

  private val scope = MainScope(dispatcherProvider)
  private var permissionChecks by mutableIntStateOf(0)
  private var connected by mutableStateOf<Boolean?>(null)
  private var lastResult by mutableStateOf<DisconnectResult?>(null)
  private var pairingError by mutableStateOf<String?>(null)
  private var chooser by mutableStateOf<List<PairedDevice>?>(null)
  private var checking by mutableStateOf(false)
  private var checkedAtMillis by mutableStateOf<Long?>(null)
  private var disconnectAtMillis by mutableStateOf<Long?>(null)

  @Composable
  fun viewState(): PillowSpeakerViewState {
    val settings by remember { settingsStore.data }.collectAsState(SpeakerSettings())
    return PillowSpeakerViewState(
      settings = settings,
      hasPermission = remember(permissionChecks) { link.hasPermission() },
      connected = connected,
      lastResult = lastResult,
      pairingError = pairingError,
      chooser = chooser,
      checking = checking,
      checkedAtMillis = checkedAtMillis,
      disconnectAtMillis = disconnectAtMillis,
      presses = mediaKeyLog.presses.collectAsState().value,
    )
  }

  fun openChooser() {
    pairingError = null
    chooser = link.pairedDevices()
  }

  fun closeChooser() {
    chooser = null
  }

  fun onPermissionResult() {
    permissionChecks++
    checkConnection()
  }

  fun onPaired(
    address: String?,
    name: String?,
    associationId: Int?,
  ) {
    if (address == null) {
      pairingError = "The system did not say which device was chosen"
      return
    }
    pairingError = null
    scope.launch {
      val settings = settingsStore.updateData {
        it.copy(address = address.uppercase(), name = name, associationId = associationId)
      }
      presence.observe(settings)
      checkConnection()
    }
  }

  fun onPairingFailed(error: String?) {
    pairingError = error ?: "Pairing was cancelled"
  }

  fun forget() {
    connected = null
    lastResult = null
    scope.launch {
      settingsStore.updateData { it.copy(address = null, name = null, associationId = null) }
    }
  }

  fun setAutoPlay(enabled: Boolean) {
    scope.launch { settingsStore.updateData { it.copy(autoPlay = enabled) } }
  }

  fun setDisconnectAfterPause(enabled: Boolean) {
    scope.launch { settingsStore.updateData { it.copy(disconnectAfterPause = enabled) } }
  }

  fun setTapSpacing(millis: Int) {
    scope.launch { settingsStore.updateData { it.copy(tapSpacingMillis = millis) } }
  }

  fun setDisconnectDelay(minutes: Int) {
    scope.launch { settingsStore.updateData { it.copy(disconnectDelayMinutes = minutes) } }
  }

  /** The §6 test: drop the speaker now, then watch whether it comes back by itself. */
  fun disconnectNow() {
    scope.launch {
      val address = settingsStore.data.first().address ?: return@launch
      val result = link.disconnect(address)
      if (result == DisconnectResult.Disconnected) {
        settingsStore.updateData { it.copy(lastOwnDisconnectMillis = System.currentTimeMillis()) }
      }
      lastResult = result
      connected = link.isConnected(address)
    }
  }

  /** Says it is checking, then the answer and when, so a repeat of the same answer still shows. */
  fun checkConnection() {
    if (checking) return
    scope.launch {
      val address = settingsStore.data.first().address ?: return@launch
      checking = true
      try {
        connected = link.isConnected(address)
        disconnectAtMillis = disconnectSchedule.at.value
        checkedAtMillis = System.currentTimeMillis()
      } finally {
        checking = false
      }
    }
  }

  fun clearPresses() {
    mediaKeyLog.clear()
  }

  fun close() {
    navigator.goBack()
  }
}
