package voice.core.speaker

import android.companion.AssociationInfo
import android.companion.CompanionDeviceService
import android.companion.DevicePresenceEvent
import android.os.Build
import android.os.Process
import android.os.SystemClock
import androidx.annotation.RequiresApi
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesTo
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import voice.core.common.rootGraphAs
import voice.core.playback.session.LogNotes

@ContributesTo(AppScope::class)
public interface SpeakerGraph {
  public val speakerAutoPlay: SpeakerAutoPlay
  public val coroutineScope: CoroutineScope
  public val logNotes: LogNotes
}

/**
 * Bound by the system when the associated speaker connects, whether or not the
 * app is running (AUDIOBOOKS.md §5). The association is also what lets it start
 * the playback service from the background.
 *
 * Three spellings of the same event across releases; each release calls the
 * one it knows, and playing twice is harmless.
 */
@RequiresApi(Build.VERSION_CODES.S)
public class PillowSpeakerService : CompanionDeviceService() {

  private val graph by lazy { rootGraphAs<SpeakerGraph>() }

  override fun onCreate() {
    super.onCreate()
    val processAge = SystemClock.elapsedRealtime() - Process.getStartElapsedRealtime()
    graph.logNotes.note("SPEAKER", "companion service woken by the system (process ${processAge / 1000}s old)")
    graph.speakerAutoPlay.onServiceWoken(processAge)
  }

  private fun connected(address: String?) {
    graph.speakerAutoPlay.onSpeakerConnected(address)
  }

  @RequiresApi(Build.VERSION_CODES.BAKLAVA)
  override fun onDevicePresenceEvent(event: DevicePresenceEvent) {
    graph.logNotes.note("SPEAKER", "presence event ${event.event}")
    if (event.event == DevicePresenceEvent.EVENT_BT_CONNECTED) connected(address = null)
  }

  @Deprecated("Deprecated in API 36")
  @RequiresApi(Build.VERSION_CODES.TIRAMISU)
  override fun onDeviceAppeared(associationInfo: AssociationInfo) {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.BAKLAVA) {
      connected(associationInfo.deviceMacAddress?.toString())
    }
  }

  @Deprecated("Deprecated in API 33")
  override fun onDeviceAppeared(address: String) {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) connected(address)
  }
}
