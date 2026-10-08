package voice.core.speaker

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothA2dp
import android.bluetooth.BluetoothClass
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import voice.core.logging.api.Logger
import java.lang.reflect.InvocationTargetException
import kotlin.coroutines.resume

/** The Bluetooth side of the pillow speaker: is it connected, and dropping it. */
public interface SpeakerLink {
  public fun hasPermission(): Boolean

  /** The phone's paired devices, audio ones first: the speaker is already among them. */
  public fun pairedDevices(): List<PairedDevice>
  public suspend fun isConnected(address: String): Boolean
  public suspend fun disconnect(address: String): DisconnectResult

  /** Waits for [address]'s media to connect: how long it took, or null after [timeoutMs]. */
  public suspend fun awaitConnected(
    address: String,
    timeoutMs: Long,
  ): Long? = SpeakerAutoPlay.waitForMedia(timeoutMs) { isConnected(address) }
}

public data class PairedDevice(
  val name: String,
  val address: String,
  val isAudio: Boolean,
)

public sealed interface DisconnectResult {
  public data object Disconnected : DisconnectResult
  public data object NotConnected : DisconnectResult
  public data object NoPermission : DisconnectResult

  /** The hidden API is gone from this Android release (AUDIOBOOKS.md §6, first caveat). */
  public data class Unavailable(val reason: String) : DisconnectResult
  public data class Failed(val reason: String) : DisconnectResult
}

/**
 * AUDIOBOOKS.md §6: `BluetoothA2dp.disconnect(BluetoothDevice)` is `@hide` but
 * on the unsupported list, not the blocklist, and needs only BLUETOOTH_CONNECT.
 * Two public calls and one reflective one. It drops the link; whether the
 * speaker then powers off, or reconnects by itself, is the speaker's business.
 */
@Inject
@ContributesBinding(AppScope::class)
public class AndroidSpeakerLink(private val context: Context) : SpeakerLink {

  override fun hasPermission(): Boolean = Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
    ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED

  @SuppressLint("MissingPermission")
  override fun pairedDevices(): List<PairedDevice> {
    if (!hasPermission()) return emptyList()
    val adapter = context.getSystemService(BluetoothManager::class.java)?.adapter ?: return emptyList()
    return try {
      adapter.bondedDevices.orEmpty()
        .map { device ->
          PairedDevice(
            name = device.name?.takeIf { it.isNotBlank() } ?: device.address,
            address = device.address,
            isAudio = device.bluetoothClass?.majorDeviceClass == BluetoothClass.Device.Major.AUDIO_VIDEO,
          )
        }
        .sortedWith(compareByDescending<PairedDevice> { it.isAudio }.thenBy { it.name.lowercase() })
    } catch (_: SecurityException) {
      emptyList()
    }
  }

  override suspend fun isConnected(address: String): Boolean {
    if (!hasPermission()) return false
    return withA2dp(address) { a2dp, device -> a2dp.connectedState(device) } ?: false
  }

  @SuppressLint("DiscouragedPrivateApi")
  override suspend fun disconnect(address: String): DisconnectResult {
    if (!hasPermission()) return DisconnectResult.NoPermission
    return withA2dp(address) { a2dp, device ->
      if (!a2dp.connectedState(device)) return@withA2dp DisconnectResult.NotConnected
      try {
        val method = BluetoothA2dp::class.java.getMethod("disconnect", BluetoothDevice::class.java)
        val accepted = method.invoke(a2dp, device) as? Boolean ?: false
        Logger.i("A2DP disconnect of $address accepted=$accepted")
        if (accepted) DisconnectResult.Disconnected else DisconnectResult.Failed("The Bluetooth stack declined")
      } catch (e: NoSuchMethodException) {
        DisconnectResult.Unavailable(e.toString())
      } catch (e: NoSuchMethodError) {
        DisconnectResult.Unavailable(e.toString())
      } catch (e: InvocationTargetException) {
        val cause = e.targetException
        if (cause is SecurityException) DisconnectResult.NoPermission else DisconnectResult.Failed(cause.toString())
      } catch (e: SecurityException) {
        DisconnectResult.NoPermission
      }
    } ?: DisconnectResult.Failed("Bluetooth is off or unavailable")
  }

  /** One proxy for the whole wait, not one per poll. */
  override suspend fun awaitConnected(
    address: String,
    timeoutMs: Long,
  ): Long? = withA2dp(address) { a2dp, device ->
    SpeakerAutoPlay.waitForMedia(timeoutMs) { a2dp.connectedState(device) }
  }

  @SuppressLint("MissingPermission")
  private fun BluetoothA2dp.connectedState(device: BluetoothDevice): Boolean =
    getConnectionState(device) == BluetoothProfile.STATE_CONNECTED

  @SuppressLint("MissingPermission")
  private suspend fun <T> withA2dp(
    address: String,
    block: suspend (BluetoothA2dp, BluetoothDevice) -> T,
  ): T? {
    val adapter = context.getSystemService(BluetoothManager::class.java)?.adapter ?: return null
    if (!adapter.isEnabled) return null
    val device = try {
      adapter.getRemoteDevice(address)
    } catch (_: IllegalArgumentException) {
      return null
    }
    val proxy = withTimeoutOrNull(5_000) {
      suspendCancellableCoroutine<BluetoothA2dp?> { continuation ->
        val listener = object : BluetoothProfile.ServiceListener {
          override fun onServiceConnected(
            profile: Int,
            proxy: BluetoothProfile,
          ) {
            if (continuation.isActive) {
              continuation.resume(proxy as BluetoothA2dp)
            } else {
              // Too late (timed out or cancelled): nobody will close it otherwise.
              adapter.closeProfileProxy(BluetoothProfile.A2DP, proxy)
            }
          }

          override fun onServiceDisconnected(profile: Int) {}
        }
        if (!adapter.getProfileProxy(context, listener, BluetoothProfile.A2DP)) {
          continuation.resume(null)
        }
      }
    } ?: return null
    return try {
      block(proxy, device)
    } finally {
      adapter.closeProfileProxy(BluetoothProfile.A2DP, proxy)
    }
  }
}
