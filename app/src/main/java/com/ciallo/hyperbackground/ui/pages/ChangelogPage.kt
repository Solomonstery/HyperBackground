package com.ciallo.hyperbackground.ui.pages

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.ciallo.hyperbackground.BuildConfig
import com.ciallo.hyperbackground.R
import com.ciallo.hyperbackground.ui.MainActivity
import com.ciallo.hyperbackground.ui.components.UiCard
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
private fun UpdateCheckCard(activity: MainActivity, onLatest: (String) -> Unit) {
    val current = BuildConfig.VERSION_NAME
    var latest by remember { mutableStateOf<String?>(null) }
    var message by remember { mutableStateOf("") }
    var checking by remember { mutableStateOf(true) }
    var refresh by remember { mutableIntStateOf(0) }
    var hasUpdate by remember { mutableStateOf(false) }

    val checkingText = stringResource(R.string.update_checking)
    val previewText = stringResource(R.string.update_preview)
    val upToDateText = stringResource(R.string.update_up_to_date)
    val failedText = stringResource(R.string.update_failed)

    LaunchedEffect(refresh) {
        checking = true
        message = checkingText
        hasUpdate = false
        runCatching { fetchLatestStableVersion() }
            .onSuccess { remote ->
                latest = remote
                onLatest(remote)
                val isPreview = isPreviewVersion(current)
                hasUpdate = !isPreview && compareVersions(current, remote) < 0
                message = when {
                    isPreview -> "$previewText $remote"
                    hasUpdate -> activity.getString(R.string.update_available, remote)
                    else -> upToDateText
                }
            }
            .onFailure { message = "$failedText · ${it.message ?: ""}" }
        checking = false
    }

    UiCard(activity, Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(stringResource(R.string.update_check), style = MiuixTheme.textStyles.headline1)
            Text(
                stringResource(R.string.current_version, current),
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            )
            latest?.let {
                Text(
                    stringResource(R.string.update_latest_stable, it),
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                )
            }
            Text(
                message,
                color = if (hasUpdate) MiuixTheme.colorScheme.primary else MiuixTheme.colorScheme.onSurfaceVariantActions,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                TextButton(
                    modifier = Modifier.weight(1f),
                    text = if (checking) stringResource(R.string.update_checking) else stringResource(R.string.update_recheck),
                    onClick = { if (!checking) refresh++ },
                )
                TextButton(
                    modifier = Modifier.weight(1f),
                    text = stringResource(R.string.github_releases),
                    colors = ButtonDefaults.textButtonColorsPrimary(),
                    onClick = { activity.openUrl(RELEASES_URL) },
                )
            }
        }
    }
}

@Composable
fun ChangelogPage(
    activity: MainActivity,
    modifier: Modifier = Modifier,
    padding: PaddingValues = PaddingValues(0.dp),
) {
    val context = LocalContext.current
    var all by remember { mutableStateOf<List<ReleaseNotesEntry>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var latest by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) {
        all = runCatching { withContext(Dispatchers.IO) { loadAllReleaseNotes(context) } }.getOrDefault(emptyList())
        loading = false
    }

    val current = BuildConfig.VERSION_NAME
    val currentEntry = findEntry(all, current)
    val latestVer = latest
    val latestEntry = latestVer?.let { findEntry(all, it) }
    // 当前版本与最新正式版相同时，只显示一张日志卡，避免重复。
    val showLatestSeparately = latestVer != null && normalizeVersion(latestVer) != normalizeVersion(current)

    LazyColumn(
        modifier.fillMaxSize(),
        contentPadding = PaddingValues(
            top = padding.calculateTopPadding() + 12.dp,
            bottom = padding.calculateBottomPadding() + 12.dp,
            start = 12.dp,
            end = 12.dp,
        ),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item { UpdateCheckCard(activity) { latest = it } }

        // 当前版本更新日志
        item {
            ChangelogCard(
                activity = activity,
                title = stringResource(R.string.changelog_current, current),
                entry = currentEntry,
                loading = loading,
            )
        }

        // 最新正式版更新日志（与当前不同版本时才单独显示）
        if (showLatestSeparately && latestVer != null) {
            item {
                ChangelogCard(
                    activity = activity,
                    title = stringResource(R.string.changelog_latest, latestVer),
                    entry = latestEntry,
                    loading = false,
                    fallbackHint = stringResource(R.string.changelog_see_github),
                )
            }
        }
    }
}

/** 单个版本的更新日志卡片。entry 为 null 时显示占位/提示。 */
@Composable
private fun ChangelogCard(
    activity: MainActivity,
    title: String,
    entry: ReleaseNotesEntry?,
    loading: Boolean,
    fallbackHint: String? = null,
) {
    UiCard(activity, Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, style = MiuixTheme.textStyles.headline1, color = MiuixTheme.colorScheme.primary)
            when {
                loading -> Text(
                    stringResource(R.string.changelog_loading),
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                )
                entry != null && entry.notes.isNotEmpty() -> entry.notes.forEach { note ->
                    Text("• $note", color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                }
                else -> Text(
                    fallbackHint ?: stringResource(R.string.changelog_empty),
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                )
            }
        }
    }
}
