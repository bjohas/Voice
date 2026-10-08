package voice.core.sync

import androidx.datastore.core.DataStore
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import voice.core.data.BookId
import voice.core.data.store.CurrentBookStore
import voice.core.data.store.ServerConfigStore
import voice.core.data.store.ServerSelectionStore
import voice.core.data.sync.ServerConfig
import voice.core.logging.api.Logger
import voice.core.sync.SyncState.Companion.write
import java.io.File
import java.io.IOException
import java.net.URI
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import kotlin.coroutines.cancellation.CancellationException

/**
 * Makes the books directory match the server's catalogue, intersected with the
 * selection stored on this device (AUDIOBOOKS.md §10).
 *
 * One pass does both halves: fetching what is selected and missing, removing
 * what the sync put there and is no longer selected. The book that is current
 * in the player is never removed. Positions live in the library database, not
 * in the files, so a book taken off and put back resumes where it was.
 */
@SingleIn(AppScope::class)
@Inject
public class BookSync(
  private val client: ServerClient,
  @ServerConfigStore
  private val configStore: DataStore<ServerConfig>,
  @ServerSelectionStore
  private val selectionStore: DataStore<Set<String>>,
  @CurrentBookStore
  private val currentBookStore: DataStore<BookId?>,
  private val directory: SyncDirectory,
  private val rescan: LibraryRescan,
) {

  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
  private val logUpload = LogUpload(client, directory)
  private val mutex = Mutex()
  private var job: Job? = null

  public val catalogue: StateFlow<CatalogueState>
    field = MutableStateFlow<CatalogueState>(CatalogueState.NotLoaded)

  public val progress: StateFlow<SyncProgress>
    field = MutableStateFlow<SyncProgress>(SyncProgress.Idle)

  public fun refresh() {
    scope.launch { loadCatalogue() }
  }

  /** Loads the catalogue again and says how that went: [CatalogueState.Loaded] or [CatalogueState.Failed]. */
  public suspend fun reload(): CatalogueState {
    loadCatalogue()
    return catalogue.value
  }

  public fun sync() {
    if (job?.isActive == true) return
    job = scope.launch { runSync() }
  }

  /**
   * Syncs now and says how it ended. If a sync is running, this one starts
   * after it, so it sees a selection changed meanwhile.
   */
  public suspend fun syncAndAwait(): SyncProgress {
    val run = scope.async {
      runSync()
      progress.value
    }
    job = run
    return run.await()
  }

  /** Stops a running sync. What was downloaded stays; a partial file resumes next time. */
  public fun cancel() {
    job?.cancel()
  }

  internal suspend fun loadCatalogue() {
    catalogue.value = CatalogueState.Loading
    catalogue.value = try {
      val config = configStore.data.first()
      val items = client.catalogue(config)
      val manifest = client.manifest(config)
      val local = withContext(Dispatchers.IO) { localFiles(SyncState.read(directory.stateFile)) }
      CatalogueState.Loaded(items.map { it.toBook(manifest, local) })
    } catch (e: IOException) {
      Logger.w(e, "Could not load the catalogue")
      CatalogueState.Failed(e.message ?: e.toString())
    }
  }

  internal suspend fun runSync() {
    mutex.withLock {
      progress.value = SyncProgress.Running(doneBytes = 0, totalBytes = 0, filesDone = 0, filesTotal = 0)
      try {
        val result = withContext(Dispatchers.IO) { syncOnce() }
        progress.value = result
      } catch (e: CancellationException) {
        progress.value = SyncProgress.Idle
        throw e
      } catch (e: IOException) {
        Logger.w(e, "Sync failed")
        progress.value = SyncProgress.Failed(e.message ?: e.toString())
      } finally {
        rescan.rescan()
      }
      val logs = withContext(Dispatchers.IO) { logUpload.run(configWithDeviceName()) }
      (progress.value as? SyncProgress.Finished)?.let { finished ->
        progress.value = finished.copy(logsSent = logs.sent, logError = logs.error)
      }
    }
    loadCatalogue()
  }

  private suspend fun syncOnce(): SyncProgress.Finished {
    val config = configStore.data.first()
    val selection = selectionStore.data.first()
    val items = client.catalogue(config)
    val remote = client.manifest(config)
    var state = SyncState.read(directory.stateFile)
    var local = localFiles(state)

    // A file copied in by hand has no cached hash; hash the ones that could
    // spare a download, rather than fetching them again.
    val selectedGroups = selection.toSet()
    val hashed = state.hashes.toMutableMap()
    local = local.mapValues { (path, file) ->
      val remoteFile = remote[path]
      val worthHashing = file.sha256 == null &&
        remoteFile != null &&
        remoteFile.size == file.size &&
        SyncPlanner.groupOf(path) in selectedGroups
      if (worthHashing) {
        val onDisk = File(directory.books, path)
        val sha = onDisk.sha256()
        hashed[path] = SyncState.CachedHash(onDisk.hashKey(), sha)
        file.copy(sha256 = sha)
      } else {
        file
      }
    }
    state = state.copy(hashes = hashed)

    val plan = SyncPlanner.plan(
      remote = remote,
      catalogue = items,
      selection = selection,
      local = local,
      previouslySynced = state.synced,
      playingGroup = playingGroup(),
    )
    Logger.i("Sync plan: fetch ${plan.fetch.size} (${plan.fetchBytes} bytes), delete ${plan.delete.size}")

    var done = 0L
    var failed = 0
    plan.fetch.forEachIndexed { index, fetch ->
      progress.value = SyncProgress.Running(
        doneBytes = done,
        totalBytes = plan.fetchBytes,
        filesDone = index,
        filesTotal = plan.fetch.size,
        current = fetch.localPath,
      )
      val base = done
      val ok = fetch(config, fetch) { bytesInFile ->
        progress.value = SyncProgress.Running(
          doneBytes = base + bytesInFile,
          totalBytes = plan.fetchBytes,
          filesDone = index,
          filesTotal = plan.fetch.size,
          current = fetch.localPath,
        )
      }
      done += fetch.size
      if (ok) {
        val target = File(directory.books, fetch.localPath)
        hashed[fetch.localPath] = SyncState.CachedHash(target.hashKey(), fetch.sha256)
      } else {
        failed++
      }
      state.copy(hashes = hashed).write(directory.stateFile)
    }

    plan.delete.forEach { path ->
      File(directory.books, path).delete()
      hashed.remove(path)
    }
    pruneEmptyDirectories(directory.books)

    val offered = items.map { it.group }.toSet()
    val onDevice = localFiles(state).keys.mapNotNull { SyncPlanner.groupOf(it.removeSuffix(SyncPlanner.PART_SUFFIX)) }.toSet()
    val synced = (state.synced + (selection intersect offered)) intersect onDevice
    SyncState(synced = synced, hashes = hashed.filterKeys { File(directory.books, it).isFile })
      .write(directory.stateFile)

    return SyncProgress.Finished(
      fetched = plan.fetch.size - failed,
      failed = failed,
      deleted = plan.delete.size,
      keptWhilePlaying = plan.keptWhilePlaying,
    )
  }

  private suspend fun configWithDeviceName(): ServerConfig = configStore.updateData { config ->
    if (config.deviceName.isBlank()) config.copy(deviceName = LogUpload.deviceName(android.os.Build.MODEL)) else config
  }

  /** Downloads into a `.part` beside the target, checks it, and renames it into place. */
  private suspend fun fetch(
    config: ServerConfig,
    fetch: SyncPlan.Fetch,
    onProgress: (Long) -> Unit,
  ): Boolean {
    val target = File(directory.books, fetch.localPath)
    val part = File(target.path + SyncPlanner.PART_SUFFIX)
    target.parentFile?.mkdirs()
    if (part.length() > fetch.size) part.delete()
    var written = 0L
    client.download(config, fetch.remotePath, part) { bytes ->
      written += bytes
      onProgress(written)
    }
    if (part.length() != fetch.size || part.sha256() != fetch.sha256) {
      Logger.w("Discarding ${fetch.localPath}: it does not match the manifest")
      part.delete()
      return false
    }
    Files.move(part.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
    return true
  }

  /** The group of the book the player has open, if it lives in the books directory. */
  private suspend fun playingGroup(): String? {
    val id = currentBookStore.data.first() ?: return null
    val path = try {
      URI(id.value).takeIf { it.scheme == "file" }?.path
    } catch (_: IllegalArgumentException) {
      null
    } ?: return null
    val root = directory.books.absolutePath.trimEnd('/') + "/"
    return path.takeIf { it.startsWith(root) }?.removePrefix(root)?.trimEnd('/')
  }

  private fun localFiles(state: SyncState): Map<String, LocalFile> {
    val root = directory.books
    if (!root.isDirectory) return emptyMap()
    return root.walkTopDown()
      .filter { it.isFile }
      .associate { file ->
        val path = file.relativeTo(root).invariantSeparatorsPath
        val cached = state.hashes[path]?.takeIf { it.key == file.hashKey() }
        path to LocalFile(size = file.length(), sha256 = cached?.sha256)
      }
  }

  private fun CatalogueItem.toBook(
    manifest: Map<String, RemoteFile>,
    local: Map<String, LocalFile>,
  ): ServerBook {
    val files = manifest.filterKeys { key -> SyncPlanner.groupOf(key) == group }
    val present = files.count { (key, remote) -> local[key]?.size == remote.size }
    val status = when {
      files.isEmpty() || present == 0 -> ServerBook.Status.NotOnDevice
      present == files.size -> ServerBook.Status.OnDevice
      else -> ServerBook.Status.Partial
    }
    return ServerBook(item = this, group = group, status = status)
  }
}

public data class ServerBook(
  val item: CatalogueItem,
  /** The group as the device names it, which is what the selection stores. */
  val group: String,
  val status: Status,
) {
  public enum class Status { NotOnDevice, Partial, OnDevice }
}

public sealed interface CatalogueState {
  public data object NotLoaded : CatalogueState
  public data object Loading : CatalogueState
  public data class Loaded(val books: List<ServerBook>) : CatalogueState
  public data class Failed(val message: String) : CatalogueState
}

public sealed interface SyncProgress {
  public data object Idle : SyncProgress

  public data class Running(
    val doneBytes: Long,
    val totalBytes: Long,
    val filesDone: Int,
    val filesTotal: Int,
    val current: String? = null,
  ) : SyncProgress

  public data class Finished(
    val fetched: Int,
    val failed: Int,
    val deleted: Int,
    val keptWhilePlaying: Set<String>,
    /** Listening-log day files handed to the server after the sync. */
    val logsSent: Int = 0,
    val logError: String? = null,
  ) : SyncProgress

  public data class Failed(val message: String) : SyncProgress
}

private fun pruneEmptyDirectories(root: File) {
  root.walkBottomUp()
    .filter { it.isDirectory && it != root && it.list()?.isEmpty() == true }
    .forEach { it.delete() }
}

private fun File.sha256(): String {
  val digest = MessageDigest.getInstance("SHA-256")
  inputStream().use { input ->
    val buffer = ByteArray(1 shl 16)
    while (true) {
      val read = input.read(buffer)
      if (read < 0) break
      digest.update(buffer, 0, read)
    }
  }
  return digest.digest().joinToString("") { "%02x".format(it) }
}
