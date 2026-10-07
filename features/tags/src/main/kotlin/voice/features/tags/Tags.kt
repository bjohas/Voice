package voice.features.tags

import android.nfc.NfcAdapter
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.retain.retain
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.navigation3.runtime.NavEntry
import coil.compose.AsyncImage
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.BindingContainer
import dev.zacsweers.metro.ContributesTo
import dev.zacsweers.metro.IntoSet
import dev.zacsweers.metro.Provides
import voice.core.common.rootGraphAs
import voice.core.tags.ScannedTag
import voice.core.tags.TagSource
import voice.core.ui.FeatureSwitch
import voice.core.ui.icons.VoiceIcons
import voice.navigation.Destination
import voice.navigation.NavEntryProvider
import java.io.File
import voice.core.strings.R as StringsR

@ContributesTo(AppScope::class)
interface TagsGraph {
  val tagsViewModel: TagsViewModel
}

@BindingContainer
@ContributesTo(AppScope::class)
object TagsProvider {

  @Provides
  @IntoSet
  fun tagsNavEntryProvider(): NavEntryProvider<*> = NavEntryProvider<Destination.Tags> { key ->
    NavEntry(key) {
      Tags()
    }
  }
}

@Composable
fun Tags() {
  val viewModel = retain<TagsViewModel> { rootGraphAs<TagsGraph>().tagsViewModel }
  // Only while the page is in front: left open in the background, tags must play again.
  LifecycleResumeEffect(Unit) {
    viewModel.setCapturing(true)
    onPauseOrDispose { viewModel.setCapturing(false) }
  }
  Tags(
    viewState = viewModel.viewState(),
    onSend = viewModel::send,
    onChoose = viewModel::choose,
    onFromStartChange = viewModel::setFromStart,
    onRemoveBook = viewModel::removeBook,
    onOpenPicker = viewModel::openPicker,
    onTagsOnChange = viewModel::setTagsOn,
    onRefresh = viewModel::refresh,
    onClose = viewModel::close,
  )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun Tags(
  viewState: TagsViewState,
  onSend: (ScannedTag, String) -> Unit,
  onChoose: (ScannedTag, BookOption, Boolean) -> Unit,
  onFromStartChange: (ScannedTag, Boolean) -> Unit,
  onRemoveBook: (ScannedTag) -> Unit,
  onOpenPicker: () -> Unit,
  onTagsOnChange: (Boolean) -> Unit,
  onRefresh: () -> Unit,
  onClose: () -> Unit,
) {
  val context = LocalContext.current
  val nfcOn = remember { NfcAdapter.getDefaultAdapter(context)?.isEnabled == true }
  var picking by remember { mutableStateOf<ScannedTag?>(null) }
  Scaffold(
    topBar = {
      TopAppBar(
        title = { Text(stringResource(StringsR.string.tags_title)) },
        navigationIcon = {
          IconButton(onClick = onClose) {
            Icon(VoiceIcons.Close, contentDescription = stringResource(StringsR.string.common_action_close))
          }
        },
        actions = {
          IconButton(onClick = onRefresh) {
            Icon(VoiceIcons.History, contentDescription = stringResource(StringsR.string.tags_refresh))
          }
        },
      )
    },
  ) { contentPadding ->
    LazyColumn(contentPadding = contentPadding) {
      item {
        FeatureSwitch(
          title = stringResource(StringsR.string.tags_use),
          checked = viewState.tagsOn,
          onCheckedChange = onTagsOnChange,
          offSummary = stringResource(StringsR.string.tags_off_explain),
        )
      }
      item { Intro(viewState.server) }
      if (!nfcOn) {
        item {
          Text(
            stringResource(StringsR.string.tags_no_nfc),
            color = MaterialTheme.colorScheme.error,
            modifier = Modifier.padding(horizontal = 16.dp),
          )
        }
      }
      item {
        LastScan(
          viewState = viewState,
          onSend = onSend,
          onPick = { tag ->
            onOpenPicker()
            picking = tag
          },
          onFromStartChange = onFromStartChange,
          onRemoveBook = onRemoveBook,
        )
      }
      item {
        Text(
          stringResource(StringsR.string.tags_known),
          style = MaterialTheme.typography.titleSmall,
          color = MaterialTheme.colorScheme.primary,
          modifier = Modifier.padding(start = 16.dp, top = 16.dp, end = 16.dp),
        )
      }
      if (viewState.tags.isEmpty()) {
        item { ListItem { Text(stringResource(StringsR.string.tags_empty)) } }
      }
      items(viewState.tags, key = { it.uid }) { TagRow(it) }
    }
  }
  picking?.let { tag ->
    BookPicker(
      books = viewState.books,
      onPick = { book ->
        onChoose(tag, book, viewState.lastScanRow?.fromStart == true)
        picking = null
      },
      onDismiss = { picking = null },
    )
  }
}

/** What tags do on this phone, and -- apart, as not everyone has one -- what the server adds. */
@Composable
private fun Intro(server: ServerStatus) {
  Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
    Text(stringResource(StringsR.string.tags_explain), style = MaterialTheme.typography.bodyMedium)
    Text(
      stringResource(StringsR.string.tags_server),
      style = MaterialTheme.typography.titleSmall,
      color = MaterialTheme.colorScheme.primary,
      modifier = Modifier.padding(top = 8.dp),
    )
    when (server) {
      is ServerStatus.On -> {
        Text(stringResource(StringsR.string.tags_server_on, server.host), style = MaterialTheme.typography.bodyMedium)
        Text(stringResource(StringsR.string.tags_server_explain), style = MaterialTheme.typography.bodyMedium)
      }
      ServerStatus.Off -> Text(stringResource(StringsR.string.tags_server_off), style = MaterialTheme.typography.bodyMedium)
      ServerStatus.NotSetUp -> Text(stringResource(StringsR.string.tags_server_not_set_up), style = MaterialTheme.typography.bodyMedium)
    }
  }
}

@Composable
private fun LastScan(
  viewState: TagsViewState,
  onSend: (ScannedTag, String) -> Unit,
  onPick: (ScannedTag) -> Unit,
  onFromStartChange: (ScannedTag, Boolean) -> Unit,
  onRemoveBook: (ScannedTag) -> Unit,
) {
  Card(Modifier.fillMaxWidth().padding(16.dp)) {
    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
      Text(stringResource(StringsR.string.tags_last_scan), style = MaterialTheme.typography.titleSmall)
      val tag = viewState.lastScan
      if (tag == null) {
        Text(stringResource(StringsR.string.tags_none_yet))
        return@Column
      }
      val row = viewState.lastScanRow
      Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        row?.picture?.let { TagPicture(it, size = 72.dp) }
        Text(tag.uid, fontFamily = FontFamily.Monospace)
      }
      Text(
        tag.tech + if (tag.probablyTonie) " · " + stringResource(StringsR.string.tags_probably_tonie) else "",
        style = MaterialTheme.typography.bodySmall,
      )
      Text(
        if (row != null && row.name != row.uid) {
          stringResource(StringsR.string.tags_known_as, row.name)
        } else {
          stringResource(StringsR.string.tags_unknown)
        },
      )
      var name by remember(tag.uid, row?.name) { mutableStateOf(row?.name?.takeIf { it != tag.uid }.orEmpty()) }
      Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(
          value = name,
          onValueChange = { name = it },
          label = { Text(stringResource(StringsR.string.tags_name)) },
          singleLine = true,
          modifier = Modifier.weight(1F),
        )
        Button(onClick = { onSend(tag, name) }, enabled = !viewState.sending && name.isNotBlank()) {
          Text(stringResource(if (viewState.configured) StringsR.string.tags_send else StringsR.string.tags_save))
        }
      }
      if (row?.source != null) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
          SourceLabel(row.source)
          Text(stringResource(StringsR.string.tags_book, row.book.orEmpty()), modifier = Modifier.weight(1F))
        }
        row.serverBook?.let { Text(stringResource(StringsR.string.tags_overrides, it), style = MaterialTheme.typography.bodySmall) }
        Row(verticalAlignment = Alignment.CenterVertically) {
          Text(stringResource(StringsR.string.tags_from_start), modifier = Modifier.weight(1F))
          Switch(
            checked = row.fromStart,
            onCheckedChange = { onFromStartChange(tag, it) },
            enabled = !viewState.sending,
          )
        }
      }
      Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(onClick = { onPick(tag) }, enabled = !viewState.sending) {
          Text(stringResource(if (row?.source == null) StringsR.string.tags_choose_book else StringsR.string.tags_change_book))
        }
        if (row?.source != null) {
          TextButton(onClick = { onRemoveBook(tag) }, enabled = !viewState.sending) {
            Text(stringResource(StringsR.string.tags_remove_book))
          }
        }
      }
      val message = viewState.message
      when {
        viewState.failed && message != null ->
          Text(stringResource(StringsR.string.tags_choose_failed, message), color = MaterialTheme.colorScheme.error)
        row != null && row.source == null && row.name != row.uid -> Text(stringResource(StringsR.string.tags_sent))
      }
    }
  }
}

@Composable
private fun BookPicker(
  books: List<BookOption>,
  onPick: (BookOption) -> Unit,
  onDismiss: () -> Unit,
) {
  var query by remember { mutableStateOf("") }
  AlertDialog(
    onDismissRequest = onDismiss,
    title = { Text(stringResource(StringsR.string.tags_choose_book)) },
    text = {
      Column {
        OutlinedTextField(
          value = query,
          onValueChange = { query = it },
          label = { Text(stringResource(StringsR.string.tags_search)) },
          singleLine = true,
          modifier = Modifier.fillMaxWidth(),
        )
        val shown = books.filter { book ->
          query.isBlank() || book.title.contains(query, ignoreCase = true) || book.author?.contains(query, ignoreCase = true) == true
        }
        LazyColumn(Modifier.heightIn(max = 400.dp)) {
          items(shown, key = { it.target.toString() }) { book ->
            ListItem(
              modifier = Modifier.clickable { onPick(book) },
              supportingContent = {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                  SourceLabel(if (book.server) TagSource.Server else TagSource.Phone)
                  Text(
                    listOfNotNull(book.author, if (book.onPhone) null else stringResource(StringsR.string.tags_not_on_phone_yet))
                      .joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall,
                  )
                }
              },
            ) {
              Text(book.title)
            }
          }
        }
      }
    },
    confirmButton = {},
    dismissButton = {
      TextButton(onClick = onDismiss) { Text(stringResource(StringsR.string.common_dialog_cancel)) }
    },
  )
}

@Composable
private fun TagRow(row: TagRowState) {
  ListItem(
    leadingContent = { if (row.picture != null) TagPicture(row.picture, size = 56.dp) },
    supportingContent = {
      Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
          if (row.source != null) SourceLabel(row.source)
          Text(
            row.book?.let { it + if (row.fromStart) " · " + stringResource(StringsR.string.tags_from_start) else "" }
              ?: stringResource(StringsR.string.tags_no_book),
          )
        }
        row.serverBook?.let { Text(stringResource(StringsR.string.tags_overrides, it), style = MaterialTheme.typography.bodySmall) }
        Text(row.uid, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
      }
    },
  ) {
    Text(row.name)
  }
}

/** "server", "phone" or "override": where a tag's book comes from. */
@Composable
private fun SourceLabel(source: TagSource) {
  val (text, color) = when (source) {
    TagSource.Server -> StringsR.string.tags_source_server to MaterialTheme.colorScheme.secondaryContainer
    TagSource.Phone -> StringsR.string.tags_source_phone to MaterialTheme.colorScheme.tertiaryContainer
    TagSource.Override -> StringsR.string.tags_source_override to MaterialTheme.colorScheme.errorContainer
  }
  Surface(color = color, shape = RoundedCornerShape(6.dp)) {
    Text(
      stringResource(text),
      style = MaterialTheme.typography.labelSmall,
      modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
    )
  }
}

/** A tag's picture, cropped square: the shop's images are wide, with the figure in the middle. */
@Composable
private fun TagPicture(
  file: File,
  size: Dp,
) {
  AsyncImage(
    model = file,
    contentDescription = null,
    contentScale = ContentScale.Crop,
    modifier = Modifier
      .size(size)
      .clip(RoundedCornerShape(8.dp)),
  )
}
