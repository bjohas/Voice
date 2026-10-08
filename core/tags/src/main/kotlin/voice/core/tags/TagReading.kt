package voice.core.tags

import android.app.Activity
import android.app.Application
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.nfc.NfcAdapter
import android.nfc.Tag
import android.os.Build
import android.os.Bundle
import androidx.datastore.core.DataStore
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesIntoSet
import dev.zacsweers.metro.ContributesTo
import dev.zacsweers.metro.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import voice.core.common.rootGraphAs
import voice.core.data.store.TagsEnabledStore
import voice.core.initializer.AppInitializer
import voice.core.logging.api.Logger
import voice.core.sync.BookSync
import voice.core.sync.SyncProgress
import voice.core.sync.TagMapSync
import java.lang.ref.WeakReference

internal fun Tag.toScanned(): ScannedTag {
  val tech = TagUid.tech(techList)
  return ScannedTag(uid = TagUid.canonical(id, tech), tech = tech)
}

/**
 * While any bVoice screen is in front, tags are read in reader mode: they come
 * straight to [TagPlayer], with no app chooser. The platform's sound stays, as
 * the sign that a tag was read. And after every sync the tag map is refreshed.
 *
 * Tags switched off in Settings: no reader mode, and [TagScanActivity] is
 * disabled, so Android does not hand tags to bVoice at all.
 */
@Inject
@ContributesIntoSet(AppScope::class)
public class TagReading(
  private val player: TagPlayer,
  private val bookSync: BookSync,
  private val tagMapSync: TagMapSync,
  private val localTags: LocalTags,
  @TagsEnabledStore
  private val enabledStore: DataStore<Boolean>,
  private val scope: CoroutineScope,
) : AppInitializer {

  @Volatile
  private var enabled = true
  private var resumed: WeakReference<Activity>? = null

  override fun onAppStart(application: Application) {
    scope.launch {
      enabledStore.data.distinctUntilChanged().collect { on ->
        enabled = on
        application.packageManager.setComponentEnabledSetting(
          ComponentName(application, TagScanActivity::class.java),
          if (on) PackageManager.COMPONENT_ENABLED_STATE_DEFAULT else PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
          PackageManager.DONT_KILL_APP,
        )
        withContext(Dispatchers.Main) {
          resumed?.get()?.let { if (on) startReading(it) else stopReading(it) }
        }
      }
    }
    application.registerActivityLifecycleCallbacks(
      object : Application.ActivityLifecycleCallbacks {
        override fun onActivityResumed(activity: Activity) {
          resumed = WeakReference(activity)
          if (enabled) startReading(activity)
        }

        override fun onActivityPaused(activity: Activity) {
          resumed = null
          stopReading(activity)
        }

        override fun onActivityCreated(
          activity: Activity,
          savedInstanceState: Bundle?,
        ) {}

        override fun onActivityStarted(activity: Activity) {}
        override fun onActivityStopped(activity: Activity) {}
        override fun onActivitySaveInstanceState(
          activity: Activity,
          outState: Bundle,
        ) {}

        override fun onActivityDestroyed(activity: Activity) {}
      },
    )
    scope.launch {
      refreshTags("app start")
      // Once per sync: a run reports Finished twice (again with the logs it sent).
      bookSync.progress
        .map { it is SyncProgress.Finished }
        .distinctUntilChanged()
        .filter { it }
        .collect { refreshTags("after a sync") }
    }
  }

  private fun startReading(activity: Activity) {
    NfcAdapter.getDefaultAdapter(activity)?.enableReaderMode(
      activity,
      { tag -> player.onTag(tag.toScanned()) },
      NfcAdapter.FLAG_READER_NFC_A or NfcAdapter.FLAG_READER_NFC_B or
        NfcAdapter.FLAG_READER_NFC_F or NfcAdapter.FLAG_READER_NFC_V or
        NfcAdapter.FLAG_READER_SKIP_NDEF_CHECK,
      null,
    )
  }

  private fun stopReading(activity: Activity) {
    NfcAdapter.getDefaultAdapter(activity)?.disableReaderMode(activity)
  }

  private suspend fun refreshTags(why: String) {
    if (tagMapSync.refresh()) {
      localTags.adopt()
    } else {
      Logger.i("Tag map not refreshed ($why): keeping the phone's copy")
    }
  }
}

@ContributesTo(AppScope::class)
public interface TagsGraph {
  public val tagPlayer: TagPlayer
}

/**
 * Started by Android when a tag is read while bVoice is not in front (screen on
 * and unlocked): hands the tag over and closes, showing nothing.
 */
public class TagScanActivity : Activity() {

  private val scope = MainScope()

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    handle(intent)
  }

  override fun onNewIntent(intent: Intent) {
    super.onNewIntent(intent)
    handle(intent)
  }

  override fun onDestroy() {
    scope.cancel()
    super.onDestroy()
  }

  /** Stays (invisibly) until the tag is decided: only a visible screen may open the prompt. */
  private fun handle(intent: Intent?) {
    val tag = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
      intent?.getParcelableExtra(NfcAdapter.EXTRA_TAG, Tag::class.java)
    } else {
      @Suppress("DEPRECATION")
      intent?.getParcelableExtra(NfcAdapter.EXTRA_TAG)
    }
    if (tag == null) {
      finish()
      return
    }
    val scanned = tag.toScanned()
    scope.launch {
      if (rootGraphAs<TagsGraph>().tagPlayer.handle(scanned) == TagAction.Prompt) {
        TagPromptActivity.show(this@TagScanActivity, scanned)
      }
      finish()
    }
  }
}
