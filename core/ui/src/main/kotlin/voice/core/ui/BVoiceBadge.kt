package voice.core.ui

import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/** bVoice's orange: the launcher icon's background. */
val BVoiceOrange: Color = Color(0xFFB84A00)

/** A small "bVoice" pill, white on orange: marks what bVoice adds to Voice. */
@Composable
fun BVoiceBadge(modifier: Modifier = Modifier) {
  Surface(color = BVoiceOrange, contentColor = Color.White, shape = RoundedCornerShape(50), modifier = modifier) {
    Text("bVoice", style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(horizontal = 6.dp, vertical = 1.dp))
  }
}
