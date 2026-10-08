package voice.core.playback.session

import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.view.KeyEvent
import androidx.datastore.core.DataStore
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.session.LibraryResult
import androidx.media3.session.MediaLibraryService.LibraryParams
import androidx.media3.session.MediaLibraryService.MediaLibrarySession
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSession.ConnectionResult
import androidx.media3.session.MediaSession.ControllerInfo
import androidx.media3.session.MediaSession.MediaItemsWithStartPosition
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionError
import androidx.media3.session.SessionResult
import com.google.common.collect.ImmutableList
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import dev.zacsweers.metro.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.guava.await
import kotlinx.coroutines.guava.future
import kotlinx.coroutines.launch
import voice.core.data.Book
import voice.core.data.BookId
import voice.core.data.repo.BookRepository
import voice.core.data.speaker.SpeakerSettings
import voice.core.data.store.CurrentBookStore
import voice.core.data.store.SeekTimeStore
import voice.core.data.store.SpeakerSettingsStore
import voice.core.logging.api.Logger
import voice.core.playback.player.VoicePlayer
import voice.core.playback.session.search.BookSearchHandler
import voice.core.playback.session.search.BookSearchParser
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

@Inject
class LibrarySessionCallback(
  private val mediaItemProvider: MediaItemProvider,
  private val scope: CoroutineScope,
  private val player: VoicePlayer,
  private val bookSearchParser: BookSearchParser,
  private val bookSearchHandler: BookSearchHandler,
  @CurrentBookStore
  private val currentBookStoreId: DataStore<BookId?>,
  private val bookRepository: BookRepository,
  @SeekTimeStore
  private val seekTimeStore: DataStore<Int>,
  private val mediaKeyLog: MediaKeyLog,
  private val logNotes: LogNotes,
  @SpeakerSettingsStore
  private val speakerSettingsStore: DataStore<SpeakerSettings>,
) : MediaLibrarySession.Callback {

  private val tapGesture = TapGesture()

  init {
    // Whether a tap continues a gesture is decided on the spot, so keep the setting to hand.
    scope.launch {
      speakerSettingsStore.data.collect {
        tapGesture.window = it.tapSpacingMillis.milliseconds
        gesturesOn = it.enabled
      }
    }
  }
  private var lastKeyDownAt: Long? = null

  @Volatile
  private var gesturesOn = true

  /**
   * Quick taps on a speaker's or headset's back/forward button add up
   * (BUTTON-PRESSES.md): the first tap is Media3's, as always -- a seek by the
   * Seek time -- and each quick tap after it tops the jump up at once. Key-ups,
   * held repeats and every other key go to Media3 unchanged.
   */
  override fun onMediaButtonEvent(
    session: MediaSession,
    controllerInfo: ControllerInfo,
    intent: Intent,
  ): Boolean {
    val keyEvent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
      intent.getParcelableExtra(Intent.EXTRA_KEY_EVENT, KeyEvent::class.java)
    } else {
      @Suppress("DEPRECATION")
      intent.getParcelableExtra(Intent.EXTRA_KEY_EVENT)
    }
    val forward = when (keyEvent?.keyCode) {
      // Not SKIP_FORWARD/BACKWARD: Media3 may not act on them, so the first tap would do nothing.
      KeyEvent.KEYCODE_MEDIA_NEXT, KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> true
      KeyEvent.KEYCODE_MEDIA_PREVIOUS, KeyEvent.KEYCODE_MEDIA_REWIND -> false
      else -> null
    }
    if (!gesturesOn || keyEvent == null || keyEvent.action != KeyEvent.ACTION_DOWN || keyEvent.repeatCount > 0) {
      return super.onMediaButtonEvent(session, controllerInfo, intent)
    }
    // Timed by arrival, not keyEvent.eventTime: keys relayed from a Bluetooth
    // speaker carry no usable eventTime (the tap tester showed a 0 ms gap for
    // every tap, however slow), so every tap counted as a quick one.
    val arrivedAt = SystemClock.elapsedRealtime()
    val gap = lastKeyDownAt?.let { arrivedAt - it }
    lastKeyDownAt = arrivedAt
    val key = KeyEvent.keyCodeToString(keyEvent.keyCode).removePrefix("KEYCODE_MEDIA_").removePrefix("KEYCODE_")
    val atMillis = System.currentTimeMillis()
    if (forward == null) {
      mediaKeyLog.add(KeyPress(key = key, atMillis = atMillis, gapMillis = gap))
      return super.onMediaButtonEvent(session, controllerInfo, intent)
    }
    val taps = tapGesture.tap(forward, arrivedAt)
    scope.launch {
      val seekTime = seekTimeStore.data.first().seconds
      val extra = TapGesture.extraFor(taps, seekTime)
      mediaKeyLog.add(
        KeyPress(
          key = key,
          atMillis = atMillis,
          gapMillis = gap,
          forward = forward,
          taps = taps,
          added = extra,
          total = TapGesture.totalAfter(taps, seekTime),
        ),
      )
      logNotes.note(
        "TAP",
        "$key ${if (forward) "fwd" else "back"} x$taps +${extra.inWholeSeconds}s total=${TapGesture.totalAfter(
          taps,
          seekTime,
        ).inWholeSeconds}s " +
          "gap=${gap}ms eventTime=${keyEvent.eventTime}",
      )
      if (taps > 1) {
        Logger.i("Tap $taps ${if (forward) "forward" else "back"}: $extra more")
        player.seekBy(extra, forward)
      }
    }
    return if (taps == 1) super.onMediaButtonEvent(session, controllerInfo, intent) else true
  }

  override fun onAddMediaItems(
    mediaSession: MediaSession,
    controller: ControllerInfo,
    mediaItems: MutableList<MediaItem>,
  ): ListenableFuture<List<MediaItem>> {
    Logger.d("onAddMediaItems")
    return scope.future {
      mediaItems.map { item ->
        mediaItemProvider.item(item.mediaId) ?: item
      }
    }
  }

  override fun onSetMediaItems(
    mediaSession: MediaSession,
    controller: ControllerInfo,
    mediaItems: MutableList<MediaItem>,
    startIndex: Int,
    startPositionMs: Long,
  ): ListenableFuture<MediaItemsWithStartPosition> {
    Logger.d("onSetMediaItems(mediaItems.size=${mediaItems.size}, startIndex=$startIndex, startPosition=$startPositionMs)")
    val item = mediaItems.singleOrNull()
    return if (startIndex == C.INDEX_UNSET && startPositionMs == C.TIME_UNSET && item != null) {
      scope.future {
        onSetMediaItemsForSingleItem(item)
          ?: super.onSetMediaItems(mediaSession, controller, mediaItems, startIndex, startPositionMs).await()
      }
    } else {
      super.onSetMediaItems(mediaSession, controller, mediaItems, startIndex, startPositionMs)
    }
  }

  private suspend fun onSetMediaItemsForSingleItem(item: MediaItem): MediaItemsWithStartPosition? {
    val searchQuery = item.requestMetadata.searchQuery
    return if (searchQuery != null) {
      val search = bookSearchParser.parse(searchQuery, item.requestMetadata.extras)
      val searchResult = bookSearchHandler.handle(search) ?: return null
      currentBookStoreId.updateData { searchResult.id }
      mediaItemProvider.mediaItemsWithStartPosition(searchResult)
    } else {
      (item.mediaId.toMediaIdOrNull() as? MediaId.Book)?.let { bookId ->
        currentBookStoreId.updateData { bookId.id }
      }
      mediaItemProvider.mediaItemsWithStartPosition(item.mediaId)
    }
  }

  override fun onGetLibraryRoot(
    session: MediaLibrarySession,
    browser: ControllerInfo,
    params: LibraryParams?,
  ): ListenableFuture<LibraryResult<MediaItem>> {
    val mediaItem = if (params?.isRecent == true) {
      mediaItemProvider.recent() ?: mediaItemProvider.root()
    } else {
      mediaItemProvider.root()
    }
    Logger.d("onGetLibraryRoot(isRecent=${params?.isRecent == true}). Returning ${mediaItem.mediaId}")
    return Futures.immediateFuture(LibraryResult.ofItem(mediaItem, params))
  }

  override fun onGetItem(
    session: MediaLibrarySession,
    browser: ControllerInfo,
    mediaId: String,
  ): ListenableFuture<LibraryResult<MediaItem>> = scope.future {
    Logger.d("onGetItem(mediaId=$mediaId)")
    val item = mediaItemProvider.item(mediaId)
    if (item != null) {
      LibraryResult.ofItem(item, null)
    } else {
      LibraryResult.ofError(SessionError.ERROR_BAD_VALUE)
    }
  }

  override fun onGetChildren(
    session: MediaLibrarySession,
    browser: ControllerInfo,
    parentId: String,
    page: Int,
    pageSize: Int,
    params: LibraryParams?,
  ): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> = scope.future {
    Logger.d("onGetChildren for $parentId")
    val children = mediaItemProvider.children(parentId)
    if (children != null) {
      LibraryResult.ofItemList(children, params)
    } else {
      LibraryResult.ofError(SessionError.ERROR_BAD_VALUE)
    }
  }

  override fun onPlaybackResumption(
    mediaSession: MediaSession,
    controller: ControllerInfo,
    isForPlayback: Boolean,
  ): ListenableFuture<MediaItemsWithStartPosition> {
    Logger.d("onPlaybackResumption")
    return scope.future {
      val currentBook = currentBook()
      if (currentBook != null) {
        mediaItemProvider.mediaItemsWithStartPosition(currentBook)
      } else {
        throw UnsupportedOperationException()
      }
    }
  }

  private suspend fun currentBook(): Book? {
    val bookId = currentBookStoreId.data.first() ?: return null
    return bookRepository.get(bookId)
  }

  override fun onConnect(
    session: MediaSession,
    controller: ControllerInfo,
  ): ConnectionResult {
    Logger.d("onConnect to ${controller.packageName}")

    if (player.playbackState == Player.STATE_IDLE &&
      controller.packageName == "com.google.android.projection.gearhead"
    ) {
      Logger.d("onConnect to ${controller.packageName} and player is idle.")
      Logger.d("Preparing current book so it shows up as recently played")
      scope.launch {
        prepareCurrentBook()
      }
    }

    val connectionResult = super.onConnect(session, controller)
    val sessionCommands = connectionResult.availableSessionCommands
      .buildUpon()
      .add(SessionCommand(CustomCommand.CUSTOM_COMMAND_ACTION, Bundle.EMPTY))
      .build()
    return ConnectionResult.accept(
      sessionCommands,
      connectionResult.availablePlayerCommands,
    )
  }

  private suspend fun prepareCurrentBook() {
    val bookId = currentBookStoreId.data.first() ?: return
    val book = bookRepository.get(bookId) ?: return
    val item = mediaItemProvider.mediaItem(book)
    player.setMediaItem(item)
    player.prepare()
  }

  override fun onCustomCommand(
    session: MediaSession,
    controller: ControllerInfo,
    customCommand: SessionCommand,
    args: Bundle,
  ): ListenableFuture<SessionResult> {
    val command = CustomCommand.parse(customCommand, args)
      ?: return super.onCustomCommand(session, controller, customCommand, args)
    when (command) {
      CustomCommand.ForceSeekToNext -> {
        player.forceSeekToNext()
      }
      CustomCommand.ForceSeekToPrevious -> {
        player.forceSeekToPrevious()
      }
      is CustomCommand.SetSkipSilence -> {
        player.setSkipSilenceEnabled(command.skipSilence)
      }
      is CustomCommand.SetGain -> {
        player.setGain(command.gain)
      }
    }

    return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
  }
}
