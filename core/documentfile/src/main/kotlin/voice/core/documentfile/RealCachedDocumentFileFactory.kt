package voice.core.documentfile

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import androidx.core.net.toFile
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding

@ContributesBinding(AppScope::class)
class RealCachedDocumentFileFactory(private val context: Context) : CachedDocumentFileFactory {
  override fun create(uri: Uri): CachedDocumentFile {
    // A plain file path is the app's own books directory, which the sync
    // writes into and no document provider fronts.
    if (uri.scheme == ContentResolver.SCHEME_FILE) {
      return FileBasedDocumentFile(uri.toFile())
    }
    return RealCachedDocumentFile(context = context, uri = uri, preFilledContent = null)
  }
}
