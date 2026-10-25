package com.jassam.smsgate.ui.settings

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.jassam.smsgate.R
import com.jassam.smsgate.ui.components.CardDivider
import com.jassam.smsgate.ui.components.InfoRow
import com.jassam.smsgate.ui.components.PageHeader
import com.jassam.smsgate.ui.components.ScreenColumn
import com.jassam.smsgate.ui.components.SectionCard
import com.jassam.smsgate.ui.theme.SmsGateTheme
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** One bundled library and its licence (names are not translated). */
data class Library(val name: String, @StringRes val licence: Int)

/** The main libraries shipped in the APK (see gradle/libs.versions.toml). */
val Libraries: List<Library> = listOf(
    Library("Kotlin, kotlinx.coroutines, kotlinx.serialization", R.string.licence_apache2),
    Library("AndroidX: Activity, Core, Lifecycle, Compose, Material 3", R.string.licence_apache2),
    Library("AndroidX: Room, WorkManager, DataStore, ProfileInstaller", R.string.licence_apache2),
    Library("OkHttp, Okio", R.string.licence_apache2),
    Library("Firebase Cloud Messaging", R.string.licence_apache2),
    Library("Google Play services (base, code scanner)", R.string.licence_google_sdk),
    Library("Material icons", R.string.licence_apache2),
    Library("Inter typeface", R.string.licence_ofl),
)

/** A static list of the libraries and licences, plus the Inter font's OFL text (bundled asset). */
@Composable
fun LicencesScreen(onBack: () -> Unit) {
    val colors = SmsGateTheme.colors
    val context = LocalContext.current
    val ofl by produceState<String?>(null) {
        value = withContext(Dispatchers.IO) {
            try {
                context.assets.open("licenses/inter-OFL-1.1.txt").bufferedReader().use { it.readText() }
            } catch (_: IOException) {
                null
            }
        }
    }
    ScreenColumn {
        Row(verticalAlignment = Alignment.CenterVertically) {
            val back = stringResource(R.string.action_back)
            IconButton(onClick = onBack, modifier = Modifier.semantics { contentDescription = back }) {
                Icon(painterResource(R.drawable.ic_arrow_back), contentDescription = null, tint = colors.fg)
            }
            Spacer(Modifier.size(4.dp))
            PageHeader(stringResource(R.string.licences_title))
        }
        SectionCard(stringResource(R.string.licences_libraries)) {
            Libraries.forEachIndexed { i, lib ->
                if (i > 0) CardDivider()
                InfoRow(lib.name, stringResource(lib.licence))
            }
        }
        Text(stringResource(R.string.licences_apache_note), style = MaterialTheme.typography.bodySmall, color = colors.fgMuted)
        ofl?.let { text ->
            SectionCard(stringResource(R.string.licences_ofl_title)) {
                Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(text, style = MaterialTheme.typography.bodySmall, color = colors.fgMuted)
                }
            }
        }
    }
}
