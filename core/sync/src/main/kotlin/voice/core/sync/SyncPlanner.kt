package voice.core.sync

/**
 * What one sync has to do, decided before anything is touched.
 *
 * Paths here are local: relative to the books directory, `Author/Title/file`.
 */
public data class SyncPlan(
  val fetch: List<Fetch>,
  val delete: List<String>,
  /** Selected-away books that stay because one of them is playing. */
  val keptWhilePlaying: Set<String>,
) {
  public data class Fetch(
    val remotePath: String,
    val localPath: String,
    val size: Long,
    val sha256: String,
  )

  val fetchBytes: Long get() = fetch.sumOf { it.size }
}

/** A file already in the books directory, and its sha256 when that is known. */
public data class LocalFile(
  val size: Long,
  val sha256: String?,
)

/**
 * The directory should match the catalogue, intersected with the selection.
 *
 * A book is the files directly inside its group's folder. Deletion is limited
 * to books the sync has a claim on -- ones the server offers now, or ones it
 * put there before -- so a folder copied in by hand is never touched. Partial
 * downloads of wanted files are kept, because the next sync resumes them.
 */
public object SyncPlanner {

  public const val PART_SUFFIX: String = ".part"

  public fun plan(
    remote: Map<String, RemoteFile>,
    catalogue: List<CatalogueItem>,
    selection: Set<String>,
    local: Map<String, LocalFile>,
    previouslySynced: Set<String>,
    playingGroup: String?,
  ): SyncPlan {
    val offered = catalogue.map { it.group }.toSet()
    val wanted = selection intersect offered

    val wantedFiles = remote.mapNotNull { (remotePath, file) ->
      val localPath = remotePath
      if (!isSafePath(localPath)) return@mapNotNull null
      if (groupOf(localPath) in wanted) localPath to (remotePath to file) else null
    }.toMap()

    val fetch = wantedFiles.mapNotNull { (localPath, pair) ->
      val (remotePath, file) = pair
      val present = local[localPath]
      val current = present != null && present.size == file.size && present.sha256 == file.sha256
      if (current) {
        null
      } else {
        SyncPlan.Fetch(remotePath = remotePath, localPath = localPath, size = file.size, sha256 = file.sha256)
      }
    }.sortedBy { it.localPath }

    val fetching = fetch.map { it.localPath }.toSet()
    val claimed = offered + previouslySynced
    // Deleting trusts the server's answer, so not one that looks broken: an
    // empty catalogue or manifest (a library mount down, a rescan halfway)
    // deletes nothing, and nor does a chosen book the manifest has no files for.
    val trusted = catalogue.isNotEmpty() && remote.isNotEmpty()
    val groupsWithFiles = remote.keys.mapNotNull { groupOf(it) }.toSet()
    val kept = mutableSetOf<String>()
    val delete = local.keys.filter { path ->
      val finalPath = path.removeSuffix(PART_SUFFIX)
      val group = groupOf(finalPath) ?: return@filter false
      val isPart = path != finalPath
      when {
        !trusted -> false
        group !in claimed -> false
        // A wanted file stays; its partial download stays only while it is still needed.
        finalPath in wantedFiles -> isPart && finalPath !in fetching
        group in wanted && group !in groupsWithFiles -> false
        group == playingGroup -> {
          kept += group
          false
        }
        else -> true
      }
    }.sorted()

    return SyncPlan(fetch = fetch, delete = delete, keptWhilePlaying = kept)
  }

  /**
   * A path from the server that stays inside the books folder when joined to
   * it: relative, with no empty, `.` or `..` segments, and no backslash or NUL.
   * Paths and groups come from the server, so they are checked before they
   * become files.
   */
  public fun isSafePath(path: String): Boolean {
    if (path.isEmpty() || path.startsWith('/') || '\\' in path || '\u0000' in path) return false
    return path.split('/').none { it.isEmpty() || it == "." || it == ".." }
  }

  /** The group a file belongs to: its folder, or null for a file at the top. */
  public fun groupOf(localPath: String): String? {
    val slash = localPath.lastIndexOf('/')
    return if (slash <= 0) null else localPath.substring(0, slash)
  }
}
