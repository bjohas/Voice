package voice.features.pillowSpeaker

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.companion.AssociationInfo
import android.companion.AssociationRequest
import android.companion.BluetoothDeviceFilter
import android.companion.CompanionDeviceManager
import android.content.Context
import android.content.IntentSender
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.RequiresApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.retain.retain
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.navigation3.runtime.NavEntry
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesTo
import dev.zacsweers.metro.IntoSet
import dev.zacsweers.metro.Provides
import voice.core.common.rootGraphAs
import voice.core.playback.session.KeyPress
import voice.core.speaker.DisconnectResult
import voice.core.speaker.PairedDevice
import voice.core.ui.icons.VoiceIcons
import voice.navigation.Destination
import voice.navigation.NavEntryProvider
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.math.roundToInt
import kotlin.time.Duration
import voice.core.strings.R as StringsR

@ContributesTo(AppScope::class)
interface PillowSpeakerGraph {
  val pillowSpeakerViewModel: PillowSpeakerViewModel
}

@ContributesTo(AppScope::class)
interface PillowSpeakerProvider {

  @Provides
  @IntoSet
  fun pillowSpeakerNavEntryProvider(): NavEntryProvider<*> = NavEntryProvider<Destination.PillowSpeaker> { key ->
    NavEntry(key) {
      PillowSpeaker()
    }
  }
}

@Composable
fun PillowSpeaker() {
  val viewModel = retain<PillowSpeakerViewModel> { rootGraphAs<PillowSpeakerGraph>().pillowSpeakerViewModel }
  PillowSpeaker(viewModel.viewState(), viewModel)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PillowSpeaker(
  viewState: PillowSpeakerViewState,
  viewModel: PillowSpeakerViewModel,
) {
  LaunchedEffect(Unit) { viewModel.checkConnection() }
  Scaffold(
    topBar = {
      TopAppBar(
        title = { Text(stringResource(StringsR.string.pillow_speaker_title)) },
        navigationIcon = {
          IconButton(onClick = viewModel::close) {
            Icon(VoiceIcons.Close, contentDescription = stringResource(StringsR.string.common_action_close))
          }
        },
      )
    },
  ) { contentPadding ->
    Column(
      Modifier
        .padding(contentPadding)
        .verticalScroll(rememberScrollState()),
    ) {
      if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
        ListItem { Text(stringResource(StringsR.string.pillow_speaker_needs_android_12)) }
        return@Column
      }
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && !viewState.hasPermission) PermissionRow(viewModel::onPermissionResult)
      SpeakerRow(
        viewState = viewState,
        onPair = viewModel::onPaired,
        onPairFail = viewModel::onPairingFailed,
        onForget = viewModel::forget,
        onOpenChooser = viewModel::openChooser,
        onCloseChooser = viewModel::closeChooser,
      )
      SwitchRow(
        title = stringResource(StringsR.string.pillow_speaker_auto_play),
        summary = stringResource(StringsR.string.pillow_speaker_auto_play_summary),
        checked = viewState.settings.autoPlay,
        onCheckedChange = viewModel::setAutoPlay,
      )
      if (viewState.settings.autoPlay) {
        Text(
          stringResource(StringsR.string.pillow_speaker_force_stop_note),
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
          modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
        )
      }
      SwitchRow(
        title = stringResource(StringsR.string.pillow_speaker_disconnect),
        summary = pluralStringResource(
          StringsR.plurals.pillow_speaker_disconnect_summary,
          viewState.settings.disconnectDelayMinutes,
          viewState.settings.disconnectDelayMinutes,
        ),
        checked = viewState.settings.disconnectAfterPause,
        onCheckedChange = viewModel::setDisconnectAfterPause,
      )
      if (viewState.settings.disconnectAfterPause) {
        Text(
          stringResource(StringsR.string.pillow_speaker_disconnect_note),
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
          modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
        )
        var minutes by remember(viewState.settings.disconnectDelayMinutes) {
          mutableFloatStateOf(viewState.settings.disconnectDelayMinutes.toFloat())
        }
        Slider(
          value = minutes,
          onValueChange = { minutes = it },
          onValueChangeFinished = { viewModel.setDisconnectDelay(minutes.roundToInt()) },
          valueRange = 1F..30F,
          steps = 28,
          modifier = Modifier.padding(horizontal = 24.dp),
        )
      }
      if (viewState.settings.address != null) {
        TestRow(viewState, onDisconnectNow = viewModel::disconnectNow, onCheck = viewModel::checkConnection)
      }
      TapSpacing(viewState.settings.tapSpacingMillis, onChange = viewModel::setTapSpacing)
      TapTester(viewState.presses, onClear = viewModel::clearPresses)
    }
  }
}

@RequiresApi(Build.VERSION_CODES.S)
@Composable
private fun PermissionRow(onResult: () -> Unit) {
  val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { onResult() }
  ListItem(
    supportingContent = { Text(stringResource(StringsR.string.pillow_speaker_permission_summary)) },
    trailingContent = {
      Button(onClick = { launcher.launch(Manifest.permission.BLUETOOTH_CONNECT) }) {
        Text(stringResource(StringsR.string.pillow_speaker_permission_allow))
      }
    },
  ) {
    Text(stringResource(StringsR.string.pillow_speaker_permission_title), color = MaterialTheme.colorScheme.error)
  }
}

@Composable
private fun SpeakerRow(
  viewState: PillowSpeakerViewState,
  onPair: (address: String?, name: String?, associationId: Int?) -> Unit,
  onPairFail: (String?) -> Unit,
  onForget: () -> Unit,
  onOpenChooser: () -> Unit,
  onCloseChooser: () -> Unit,
) {
  val context = LocalContext.current
  var chosenName by remember { mutableStateOf<String?>(null) }
  val picker = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
    // Before Android 13 the chosen device arrives here; from 13 on, in onAssociationCreated.
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
      @Suppress("DEPRECATION")
      val device = result.data?.getParcelableExtra<BluetoothDevice>(CompanionDeviceManager.EXTRA_DEVICE)
      if (device != null) onPair(device.address, chosenName, null)
    }
  }
  val address = viewState.settings.address
  ListItem(
    supportingContent = {
      Column {
        if (address != null) Text(address)
        val checkedAt = viewState.checkedAtMillis?.let { " · " + clockTime(it) }.orEmpty()
        when {
          viewState.checking -> Text(stringResource(StringsR.string.pillow_speaker_checking))
          viewState.connected == true ->
            Text(stringResource(StringsR.string.pillow_speaker_connected) + checkedAt, color = MaterialTheme.colorScheme.primary)
          viewState.connected == false -> Text(stringResource(StringsR.string.pillow_speaker_not_connected) + checkedAt)
        }
        viewState.pairingError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
      }
    },
    trailingContent = {
      if (address == null) {
        Button(onClick = onOpenChooser, enabled = viewState.hasPermission) {
          Text(stringResource(StringsR.string.pillow_speaker_choose))
        }
      } else {
        OutlinedButton(
          onClick = {
            forget(context, viewState.settings.associationId, address)
            onForget()
          },
        ) {
          Text(stringResource(StringsR.string.pillow_speaker_forget))
        }
      }
    },
  ) {
    Text(
      viewState.settings.name
        ?: stringResource(if (address == null) StringsR.string.pillow_speaker_none else StringsR.string.pillow_speaker_title),
    )
  }
  viewState.chooser?.let { devices ->
    AlertDialog(
      onDismissRequest = onCloseChooser,
      title = { Text(stringResource(StringsR.string.pillow_speaker_choose_title)) },
      text = {
        if (devices.isEmpty()) {
          Text(stringResource(StringsR.string.pillow_speaker_no_paired))
        } else {
          Column(Modifier.verticalScroll(rememberScrollState())) {
            devices.forEach { device ->
              ListItem(
                modifier = Modifier.clickable {
                  onCloseChooser()
                  chosenName = device.name
                  pair(context, device, onPair, onPairFail) { picker.launch(IntentSenderRequest.Builder(it).build()) }
                },
                supportingContent = { Text(device.address) },
              ) {
                Text(device.name)
              }
            }
          }
        }
      },
      confirmButton = {},
      dismissButton = {
        TextButton(onClick = onCloseChooser) { Text(stringResource(StringsR.string.common_dialog_cancel)) }
      },
    )
  }
}

@Composable
private fun SwitchRow(
  title: String,
  summary: String,
  checked: Boolean,
  onCheckedChange: (Boolean) -> Unit,
) {
  ListItem(
    supportingContent = { Text(summary) },
    trailingContent = { Switch(checked = checked, onCheckedChange = onCheckedChange) },
  ) {
    Text(title)
  }
}

@Composable
private fun TestRow(
  viewState: PillowSpeakerViewState,
  onDisconnectNow: () -> Unit,
  onCheck: () -> Unit,
) {
  Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
    Text(stringResource(StringsR.string.pillow_speaker_test_summary), style = MaterialTheme.typography.bodyMedium)
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
      Button(onClick = onDisconnectNow) { Text(stringResource(StringsR.string.pillow_speaker_disconnect_now)) }
      OutlinedButton(onClick = onCheck, enabled = !viewState.checking) { Text(stringResource(StringsR.string.pillow_speaker_check)) }
    }
    // The answer next to the button that asked: the speaker row at the top may be scrolled away.
    val checkedAt = viewState.checkedAtMillis?.let { " · " + clockTime(it) }.orEmpty()
    when {
      viewState.checking -> Text(stringResource(StringsR.string.pillow_speaker_checking))
      viewState.connected == true ->
        Text(stringResource(StringsR.string.pillow_speaker_connected) + checkedAt, color = MaterialTheme.colorScheme.primary)
      viewState.connected == false -> Text(stringResource(StringsR.string.pillow_speaker_not_connected) + checkedAt)
    }
    val checkedAtMillis = viewState.checkedAtMillis
    if (!viewState.checking && checkedAtMillis != null) {
      val due = viewState.disconnectAtMillis
      Text(
        when {
          !viewState.settings.disconnectAfterPause -> stringResource(StringsR.string.pillow_speaker_disconnect_off)
          due == null -> stringResource(StringsR.string.pillow_speaker_disconnect_none)
          else -> stringResource(
            StringsR.string.pillow_speaker_disconnect_in,
            minutesSeconds((due - checkedAtMillis).coerceAtLeast(0)),
            clockTime(due),
          )
        },
      )
    }
    viewState.lastResult?.let { result ->
      Text(
        when (result) {
          DisconnectResult.Disconnected -> stringResource(StringsR.string.pillow_speaker_result_disconnected)
          DisconnectResult.NotConnected -> stringResource(StringsR.string.pillow_speaker_not_connected)
          DisconnectResult.NoPermission -> stringResource(StringsR.string.pillow_speaker_permission_title)
          is DisconnectResult.Unavailable -> stringResource(StringsR.string.pillow_speaker_result_unavailable, result.reason)
          is DisconnectResult.Failed -> stringResource(StringsR.string.pillow_speaker_result_failed, result.reason)
        },
      )
    }
  }
}

/** Opens the system's companion device picker, filtered to Bluetooth devices. */
@SuppressLint("NewApi")
private fun pair(
  context: Context,
  device: PairedDevice,
  onPaired: (address: String?, name: String?, associationId: Int?) -> Unit,
  onFailed: (String?) -> Unit,
  launch: (IntentSender) -> Unit,
) {
  val manager = context.getSystemService(CompanionDeviceManager::class.java) ?: return
  val request = AssociationRequest.Builder()
    .addDeviceFilter(BluetoothDeviceFilter.Builder().setAddress(device.address).build())
    .setSingleDevice(true)
    .build()
  val callback = object : CompanionDeviceManager.Callback() {
    override fun onAssociationPending(intentSender: IntentSender) = launch(intentSender)

    @Deprecated("Deprecated in API 33")
    override fun onDeviceFound(intentSender: IntentSender) = launch(intentSender)

    override fun onAssociationCreated(associationInfo: AssociationInfo) {
      onPaired(
        associationInfo.deviceMacAddress?.toString(),
        device.name,
        associationInfo.id,
      )
    }

    override fun onFailure(error: CharSequence?) = onFailed(error?.toString())
  }
  if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
    manager.associate(request, context.mainExecutor, callback)
  } else {
    @Suppress("DEPRECATION")
    manager.associate(request, callback, null)
  }
}

@SuppressLint("NewApi")
private fun forget(
  context: Context,
  associationId: Int?,
  address: String,
) {
  val manager = context.getSystemService(CompanionDeviceManager::class.java) ?: return
  try {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && associationId != null) {
      manager.disassociate(associationId)
    } else {
      @Suppress("DEPRECATION")
      manager.disassociate(address)
    }
  } catch (_: RuntimeException) {
    // Already gone; the settings are cleared either way.
  }
}

/** What each key press from the speaker did, newest first: tap counts, jumps, and the gap before it. */
@Composable
private fun TapTester(
  presses: List<KeyPress>,
  onClear: () -> Unit,
) {
  Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
    Row(verticalAlignment = Alignment.CenterVertically) {
      Text(
        stringResource(StringsR.string.pillow_speaker_tap_tester),
        style = MaterialTheme.typography.titleSmall,
        modifier = Modifier.weight(1F),
      )
      TextButton(onClick = onClear, enabled = presses.isNotEmpty()) {
        Text(stringResource(StringsR.string.pillow_speaker_tap_tester_clear))
      }
    }
    if (presses.isEmpty()) {
      Text(stringResource(StringsR.string.pillow_speaker_tap_tester_empty), style = MaterialTheme.typography.bodyMedium)
    }
    presses.forEach { press ->
      Text(pressLine(press), style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
    }
  }
}

internal fun pressLine(press: KeyPress): String = buildString {
  append(clockTime(press.atMillis, withMillis = true))
  append("  ")
  append(press.key)
  val forward = press.forward
  val taps = press.taps
  if (taps != null && forward != null) {
    append(if (forward) "  fwd" else "  back")
    append(" ×").append(taps)
    press.added?.let { append("  +").append(shortDuration(it)) }
    press.total?.let { append(" → ").append(shortDuration(it)) }
  }
  press.gapMillis?.let { append("  gap ").append(it).append(" ms") }
}

private fun shortDuration(duration: Duration): String {
  val seconds = duration.inWholeSeconds
  return if (seconds < 60) "$seconds s" else "%d:%02d".format(seconds / 60, seconds % 60)
}

private fun clockTime(
  millis: Long,
  withMillis: Boolean = false,
): String {
  val time = Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault()).toLocalTime()
  return time.format(DateTimeFormatter.ofPattern(if (withMillis) "HH:mm:ss.SSS" else "HH:mm:ss"))
}

/** How close together taps on the speaker's back/forward must be to count as one gesture. */
@Composable
private fun TapSpacing(
  millis: Int,
  onChange: (Int) -> Unit,
) {
  var value by remember(millis) { mutableFloatStateOf(millis.toFloat()) }
  Column {
    ListItem(
      supportingContent = {
        Text(
          if (value.roundToInt() == 0) {
            stringResource(StringsR.string.pillow_speaker_tap_spacing_off)
          } else {
            stringResource(StringsR.string.pillow_speaker_tap_spacing_summary, value.roundToInt())
          },
        )
      },
    ) {
      Text(stringResource(StringsR.string.pillow_speaker_tap_spacing))
    }
    Slider(
      value = value,
      onValueChange = { value = (it / 50).roundToInt() * 50F },
      onValueChangeFinished = { onChange(value.roundToInt()) },
      valueRange = 0F..1500F,
      steps = 29,
      modifier = Modifier.padding(horizontal = 24.dp),
    )
  }
}

private fun minutesSeconds(millis: Long): String {
  val seconds = millis / 1000
  return "%d:%02d".format(seconds / 60, seconds % 60)
}
