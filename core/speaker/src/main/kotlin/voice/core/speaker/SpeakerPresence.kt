package voice.core.speaker

import android.annotation.SuppressLint
import android.app.Application
import android.companion.CompanionDeviceManager
import android.companion.ObservingDevicePresenceRequest
import android.content.Context
import android.os.Build
import androidx.datastore.core.DataStore
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesIntoSet
import dev.zacsweers.metro.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.distinctUntilChangedBy
import kotlinx.coroutines.launch
import voice.core.data.speaker.SpeakerSettings
import voice.core.data.store.SpeakerSettingsStore
import voice.core.initializer.AppInitializer
import voice.core.logging.api.Logger

/**
 * Asks the system to tell [PillowSpeakerService] when the associated speaker
 * comes and goes -- or, with the pillow speaker switched off, to stop, so the
 * service is not woken for nothing.
 */
@Inject
public class SpeakerPresence(private val context: Context) {

  @SuppressLint("MissingPermission")
  @Suppress("DEPRECATION")
  public fun observe(settings: SpeakerSettings) {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return
    val manager = context.getSystemService(CompanionDeviceManager::class.java) ?: return
    try {
      val id = settings.associationId
      val address = settings.address
      val byId = Build.VERSION.SDK_INT >= Build.VERSION_CODES.BAKLAVA && id != null
      when {
        byId && settings.enabled ->
          manager.startObservingDevicePresence(ObservingDevicePresenceRequest.Builder().setAssociationId(id).build())
        byId -> manager.stopObservingDevicePresence(ObservingDevicePresenceRequest.Builder().setAssociationId(id).build())
        address != null && settings.enabled -> manager.startObservingDevicePresence(address)
        address != null -> manager.stopObservingDevicePresence(address)
      }
    } catch (e: Exception) {
      // SecurityException, or DeviceNotAssociatedException once the pairing is gone.
      Logger.w(e, "Could not observe the pillow speaker")
    }
  }
}

@Inject
@ContributesIntoSet(AppScope::class)
public class ObserveSpeakerOnAppStart(
  @SpeakerSettingsStore
  private val settingsStore: DataStore<SpeakerSettings>,
  private val presence: SpeakerPresence,
  private val scope: CoroutineScope,
) : AppInitializer {

  override fun onAppStart(application: Application) {
    // At start, and again whenever it is switched on or off (here or in Settings).
    scope.launch {
      settingsStore.data.distinctUntilChangedBy { it.enabled }.collect { presence.observe(it) }
    }
  }
}
