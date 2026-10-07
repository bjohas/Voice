package voice.core.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp

/**
 * The on/off switch at the top of one of bVoice's own feature pages: the same
 * switch as on that feature's row in Settings. [offSummary] says what off means.
 */
@Composable
fun FeatureSwitch(
  title: String,
  checked: Boolean,
  onCheckedChange: (Boolean) -> Unit,
  offSummary: String,
  modifier: Modifier = Modifier,
) {
  Surface(
    color = if (checked) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant,
    shape = RoundedCornerShape(24.dp),
    modifier = modifier.fillMaxWidth().padding(16.dp),
  ) {
    Row(
      modifier = Modifier
        .toggleable(value = checked, role = Role.Switch, onValueChange = onCheckedChange)
        .padding(horizontal = 20.dp, vertical = 12.dp),
      verticalAlignment = Alignment.CenterVertically,
    ) {
      Column(Modifier.weight(1F)) {
        Text(title, style = MaterialTheme.typography.titleMedium)
        if (!checked) Text(offSummary, style = MaterialTheme.typography.bodySmall)
      }
      Switch(checked = checked, onCheckedChange = null)
    }
  }
}
