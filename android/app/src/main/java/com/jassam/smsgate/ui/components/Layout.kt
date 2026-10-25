package com.jassam.smsgate.ui.components

import androidx.annotation.DrawableRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.jassam.smsgate.ui.theme.SmsGateTheme
import com.jassam.smsgate.ui.theme.Tone

/** Widest content column, like the dashboard's centred max-w container. */
val ContentMaxWidth = 640.dp

/** A scrolling, centred column with the 16 dp phone gutter: Home and Settings. */
@Composable
fun ScreenColumn(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Box(modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        Column(
            Modifier
                .widthIn(max = ContentMaxWidth)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
            content = content,
        )
    }
}

/** Page title and optional subtitle (dashboard page header). */
@Composable
fun PageHeader(title: String, modifier: Modifier = Modifier, subtitle: String? = null) {
    val colors = SmsGateTheme.colors
    Column(modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(title, style = MaterialTheme.typography.headlineMedium, color = colors.fg, modifier = Modifier.semantics { heading() })
        if (subtitle != null) Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = colors.fgMuted)
    }
}

/** A card with a small heading (dashboard Card + CardHeader). */
@Composable
fun SectionCard(
    title: String,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    val colors = SmsGateTheme.colors
    SgCard(modifier.fillMaxWidth(), contentPadding = PaddingValues(horizontal = 16.dp, vertical = 16.dp)) {
        Text(
            title,
            style = MaterialTheme.typography.titleSmall,
            color = colors.fg,
            modifier = Modifier.padding(bottom = 8.dp).semantics { heading() },
        )
        content()
    }
}

/** A label/value line inside a card, read by TalkBack as one item. Wraps at large font sizes. */
@Composable
fun InfoRow(label: String, value: String, modifier: Modifier = Modifier, trailing: (@Composable () -> Unit)? = null) {
    val colors = SmsGateTheme.colors
    Row(
        modifier.fillMaxWidth().heightIn(min = 40.dp).padding(vertical = 6.dp).semantics(mergeDescendants = true) {},
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(label, style = MaterialTheme.typography.labelMedium, color = colors.fgMuted)
            Text(value, style = MaterialTheme.typography.bodyMedium, color = colors.fg)
        }
        trailing?.invoke()
    }
}

@Composable
fun CardDivider() = HorizontalDivider(color = SmsGateTheme.colors.border, thickness = 1.dp, modifier = Modifier.padding(vertical = 4.dp))

/** web EmptyState: brand icon tile, title, description. */
@Composable
fun EmptyState(@DrawableRes icon: Int, title: String, description: String, modifier: Modifier = Modifier) {
    val colors = SmsGateTheme.colors
    Column(
        modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 48.dp).semantics(mergeDescendants = true) {},
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        IconTile(icon, Tone.Brand, size = 48)
        Text(title, style = MaterialTheme.typography.titleSmall, color = colors.fg, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 8.dp))
        Text(description, style = MaterialTheme.typography.bodyMedium, color = colors.fgMuted, textAlign = TextAlign.Center)
    }
}
