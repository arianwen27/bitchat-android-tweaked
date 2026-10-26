package com.bitchat.android.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bitchat.android.R
import com.bitchat.android.ui.theme.BitchatFontFamily

/**
 * Slim status row under the chat header.
 *
 * It is a single tap target, not two: the realistic action is "what did the shield hide?", and the
 * target-language label is information, not a button. Translation settings live in the settings sheet,
 * so this bar never opens a picker.
 */
@Composable
fun ModerationBar(
    hiddenCount: Int,
    targetLangLabel: String?,
    onReviewClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    if (hiddenCount <= 0 && targetLangLabel == null) return
    val colorScheme = MaterialTheme.colorScheme
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(bottomStart = 12.dp, bottomEnd = 12.dp))
            // Solid, not translucent: the bar sits on top of the timeline, so a see-through row let the
            // message text underneath bleed through the count and the language label.
            .background(colorScheme.surface.copy(alpha = 0.97f))
            .then(if (hiddenCount > 0) Modifier.clickable(onClick = onReviewClick) else Modifier)
            .padding(horizontal = 16.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(9.dp)
    ) {
        if (hiddenCount > 0) {
            Icon(
                imageVector = Icons.Default.Shield,
                contentDescription = null,
                tint = colorScheme.primary,
                modifier = Modifier.size(17.dp)
            )
            Text(
                text = stringResource(R.string.spam_hidden_count, hiddenCount),
                fontFamily = BitchatFontFamily,
                fontSize = 13.sp,
                color = colorScheme.onSurface
            )
        }
        if (targetLangLabel != null) {
            Text(
                text = stringResource(R.string.translation_active_label, targetLangLabel),
                fontFamily = BitchatFontFamily,
                fontSize = 13.sp,
                color = colorScheme.onSurfaceVariant
            )
        }
    }
}
