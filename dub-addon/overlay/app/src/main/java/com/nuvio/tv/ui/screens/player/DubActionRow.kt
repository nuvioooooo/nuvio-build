@file:OptIn(androidx.tv.material3.ExperimentalTvMaterial3Api::class)

package com.nuvio.tv.ui.screens.player

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Border
import androidx.tv.material3.Card
import androidx.tv.material3.CardDefaults
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Switch
import androidx.tv.material3.SwitchDefaults
import androidx.tv.material3.Text
import com.nuvio.tv.ui.theme.NuvioTheme

/**
 * One row of the audio menu: the action (tap the label) with its own on/off switch on the right.
 * Both are separate focus targets, so with the remote: OK on the label runs the action, Right moves
 * to the switch, OK on the switch flips it, Right again continues to the delay settings.
 */
@Composable
internal fun DubActionRow(
    label: String,
    icon: ImageVector,
    onClick: () -> Unit,
    switchChecked: Boolean,
    onSwitchChange: (Boolean) -> Unit,
    rightFocusRequester: FocusRequester
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        DubRowCard(onClick = onClick, modifier = Modifier.weight(1f)) {
            Row(
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Icon(imageVector = icon, contentDescription = null, tint = Color.White)
                Text(
                    text = label,
                    style = MaterialTheme.typography.titleMedium,
                    color = Color.White
                )
            }
        }
        DubRowCard(
            onClick = { onSwitchChange(!switchChecked) },
            modifier = Modifier.focusProperties { right = rightFocusRequester }
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    text = if (switchChecked) "Açık" else "Kapalı",
                    style = MaterialTheme.typography.bodyMedium,
                    color = Color.White.copy(alpha = 0.8f)
                )
                Switch(
                    checked = switchChecked,
                    onCheckedChange = null,
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = NuvioTheme.colors.Secondary,
                        checkedTrackColor = NuvioTheme.colors.Secondary.copy(alpha = 0.3f)
                    )
                )
            }
        }
    }
}

@Composable
private fun DubRowCard(
    onClick: () -> Unit,
    modifier: Modifier,
    content: @Composable () -> Unit
) {
    Card(
        onClick = onClick,
        modifier = modifier,
        colors = CardDefaults.colors(
            containerColor = Color.Transparent,
            focusedContainerColor = Color.Transparent
        ),
        shape = CardDefaults.shape(RoundedCornerShape(NuvioTheme.radii.md)),
        border = CardDefaults.border(
            border = Border(
                border = BorderStroke(NuvioTheme.spacing.xxs, Color.Transparent),
                shape = RoundedCornerShape(NuvioTheme.radii.md)
            ),
            focusedBorder = Border(
                border = NuvioTheme.focusRing.border(NuvioTheme.spacing.xxs),
                shape = RoundedCornerShape(NuvioTheme.radii.md)
            )
        ),
        scale = CardDefaults.scale(focusedScale = 1f, pressedScale = 1f)
    ) {
        content()
    }
}
