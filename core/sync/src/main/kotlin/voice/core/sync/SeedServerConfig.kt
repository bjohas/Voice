package voice.core.sync

import android.app.Application
import androidx.datastore.core.DataStore
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesIntoSet
import dev.zacsweers.metro.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import voice.core.data.store.ServerConfigStore
import voice.core.data.sync.ServerConfig
import voice.core.initializer.AppInitializer

/**
 * Copies the build's server address and API key into the app's own settings,
 * where either is still empty. From then on the phone remembers them: a later
 * build that carries none -- one from the public repository -- keeps working
 * on a phone that has them, and the settings dialog can change them.
 */
@Inject
@ContributesIntoSet(AppScope::class)
public class SeedServerConfig(
  @ServerConfigStore
  private val configStore: DataStore<ServerConfig>,
  private val scope: CoroutineScope,
) : AppInitializer {

  override fun onAppStart(application: Application) {
    scope.launch {
      configStore.updateData { seed(it, url = BuildConfig.SERVER_URL, apiKey = BuildConfig.SERVER_TOKEN) }
    }
  }

  internal companion object {
    fun seed(
      config: ServerConfig,
      url: String,
      apiKey: String,
    ): ServerConfig = config.copy(
      url = config.url.ifBlank { url },
      token = config.token.ifBlank { apiKey },
    )
  }
}
