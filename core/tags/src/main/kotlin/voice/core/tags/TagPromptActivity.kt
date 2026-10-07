package voice.core.tags

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.os.Bundle
import voice.core.strings.R as StringsR

/**
 * "Unknown tag — set it up in Settings → Tags", with a button that opens that
 * screen. A small dialog of its own, so it works whether or not bVoice is open.
 */
public class TagPromptActivity : Activity() {

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    val uid = intent.getStringExtra(EXTRA_UID).orEmpty()
    AlertDialog.Builder(this)
      .setTitle(StringsR.string.tags_prompt_title)
      .setMessage(getString(StringsR.string.tags_prompt_message, uid))
      .setPositiveButton(StringsR.string.tags_prompt_set_up) { _, _ ->
        packageManager.getLaunchIntentForPackage(packageName)?.let { launch ->
          startActivity(
            launch.setAction(OPEN_TAGS)
              .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK),
          )
        }
      }
      .setNegativeButton(StringsR.string.common_dialog_cancel, null)
      .setOnDismissListener { finish() }
      .show()
  }

  internal companion object {
    private const val EXTRA_UID = "uid"

    /** Matched in the app's StartDestinationProvider. */
    const val OPEN_TAGS = "voice.action.OPEN_TAGS"

    fun show(
      context: Context,
      tag: ScannedTag,
    ) {
      context.startActivity(
        Intent(context, TagPromptActivity::class.java)
          .putExtra(EXTRA_UID, tag.uid)
          .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
      )
    }
  }
}
