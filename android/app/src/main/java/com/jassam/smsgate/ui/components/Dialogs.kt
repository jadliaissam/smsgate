package com.jassam.smsgate.ui.components

import androidx.annotation.DrawableRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.jassam.smsgate.ui.theme.Elevation
import com.jassam.smsgate.ui.theme.SmsGateTheme
import com.jassam.smsgate.ui.theme.Tone

/**
 * A raised card dialog (like the dashboard's modals): icon, title, body, then
 * [actions] stacked full width. Scrolls so large fonts never push the buttons off
 * screen. [dismissible] false while work is in flight (back and outside taps ignored).
 */
@Composable
fun SgDialog(
    title: String,
    onDismiss: () -> Unit,
    @DrawableRes icon: Int,
    tone: Tone = Tone.Brand,
    dismissible: Boolean = true,
    body: @Composable ColumnScope.() -> Unit,
    actions: @Composable ColumnScope.() -> Unit,
) {
    val colors = SmsGateTheme.colors
    Dialog(
        onDismissRequest = { if (dismissible) onDismiss() },
        properties = DialogProperties(dismissOnBackPress = dismissible, dismissOnClickOutside = dismissible),
    ) {
        SgCard(
            Modifier.widthIn(max = 480.dp).fillMaxWidth().semantics { paneTitle = title },
            level = Elevation.Raised,
            contentPadding = PaddingValues(24.dp),
        ) {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                IconTile(icon, tone)
                Text(title, style = MaterialTheme.typography.headlineSmall, color = colors.fg, modifier = Modifier.semantics { heading() })
                body()
                Column(Modifier.fillMaxWidth().padding(top = 4.dp), verticalArrangement = Arrangement.spacedBy(4.dp), content = actions)
            }
        }
    }
}

/** Body text for [SgDialog]. */
@Composable
fun DialogText(text: String) {
    Text(text, style = MaterialTheme.typography.bodyMedium, color = SmsGateTheme.colors.fgMuted)
}
