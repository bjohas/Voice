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
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import voice.core.data.speaker.SpeakerSettings
import voice.core.data.store.SpeakerSettingsStore
import voice.core.initializer.AppInitializer
import voice.core.logging.api.Logger

/** Asks the system to tell [PillowSpeakerService] when the associated speaker comes and goes. */
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
      when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.BAKLAVA && id != null ->
          manager.startObservingDevicePresence(ObservingDevicePresenceRequest.Builder().setAssociationId(id).build())
        address != null -> manager.startObservingDevicePresence(address)
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
    scope.launch { presence.observe(settingsStore.data.first()) }
  }
}
