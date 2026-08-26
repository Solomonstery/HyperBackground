package com.ciallo.hyperbackground.ui.pages

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.theme.MiuixTheme

private const val LATEST_RELEASE_API =
    "https://api.github.com/repos/Solomonstery/HyperBackground/releases/latest"
private const val RELEASES_URL = "https://github.com/Solomonstery/HyperBackground/releases"

/** 一个版本章节：版本标题 + 该版本下的条目列表。 */
private data class ReleaseNotesEntry(val version: String, val notes: List<String>)

/**
 * 解析打包进 assets 的 CHANGELOG.md，返回文件中出现的全部版本章节（按文件顺序，即从新到旧）。
 * 兼容 `-`/`*`/`+` 列表符号，跳过代码块围栏。
 */
private fun loadAllReleaseNotes(context: android.content.Context): List<ReleaseNotesEntry> {
    val text = runCatching {
        context.assets.open("CHANGELOG.md").bufferedReader(Charsets.UTF_8).use { it.readText() }
    }.getOrNull() ?: return emptyList()

    val headingRegex = Regex("""^##\s+(.+)$""")
    val bulletRegex = Regex("""^[-*+]\s+(.+)$""")

    val sections = mutableListOf<ReleaseNotesEntry>()
    var currentVersion: String? = null
    val currentNotes = mutableListOf<String>()
    var inFence = false
    fun flush() {
        currentVersion?.let { sections.add(ReleaseNotesEntry(it, currentNotes.toList())) }
        currentNotes.clear()
    }
    for (raw in text.lineSequence()) {
        val line = raw.trim()
        if (line.startsWith("```") || line.startsWith("~~~")) {
            inFence = !inFence
            continue
        }
        if (inFence) continue
        val heading = headingRegex.matchEntire(line)
        if (heading != null) {
            flush()
            currentVersion = heading.groupValues[1].trim()
            continue
        }
        val bullet = bulletRegex.matchEntire(line)
        if (bullet != null && currentVersion != null) {
            val note = bullet.groupValues[1].trim()
            if (note.isNotEmpty()) currentNotes.add(note)
        }
    }
    flush()
    return sections
}

/** 请求 GitHub releases/latest，返回最新正式版版本号（去掉 v 前缀）。 */
private suspend fun fetchLatestStableVersion(): String = withContext(Dispatchers.IO) {
    val connection = (URL(LATEST_RELEASE_API).openConnection() as HttpURLConnection).apply {
        connectTimeout = 7000
        readTimeout = 7000
        requestMethod = "GET"
        setRequestProperty("Accept", "application/vnd.github+json")
        setRequestProperty("User-Agent", "HyperBG/${BuildConfig.VERSION_NAME}")
    }
    try {
        val code = connection.responseCode
        if (code !in 200..299) error("GitHub HTTP $code")
        val body = connection.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
        val tag = JSONObject(body).optString("tag_name").trim().removePrefix("v")
        if (tag.isBlank()) error("empty tag")
        tag
    } finally {
        connection.disconnect()
    }
}

/** 比较两个语义化版本号，忽略 `v` 前缀与 `-` 之后的预发布后缀。 */
private fun compareVersions(a: String, b: String): Int {
    fun parts(v: String) = v.removePrefix("v").substringBefore('-').split('.').map { it.toIntOrNull() ?: 0 }
    val ap = parts(a)
    val bp = parts(b)
    for (i in 0 until maxOf(ap.size, bp.size)) {
        val av = ap.getOrElse(i) { 0 }
        val bv = bp.getOrElse(i) { 0 }
        if (av != bv) return av.compareTo(bv)
    }
    return 0
}

@Composable
private fun UpdateCheckCard(activity: MainActivity) {
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
                val isPreview = current.contains(Regex("(?i)(test|alpha|beta|rc|dev)"))
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
    var entries by remember { mutableStateOf<List<ReleaseNotesEntry>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }

    LaunchedEffect(Unit) {
        entries = runCatching { withContext(Dispatchers.IO) { loadAllReleaseNotes(context) } }.getOrDefault(emptyList())
        loading = false
    }

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
        item { UpdateCheckCard(activity) }
        when {
            loading -> item {
                UiCard(activity, Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp)) {
                        Text(
                            stringResource(R.string.changelog_loading),
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        )
                    }
                }
            }
            entries.isEmpty() -> item {
                UiCard(activity, Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp)) {
                        Text(
                            stringResource(R.string.changelog_empty),
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        )
                    }
                }
            }
            else -> items(entries) { entry ->
                UiCard(activity, Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(entry.version, style = MiuixTheme.textStyles.headline1, color = MiuixTheme.colorScheme.primary)
                        entry.notes.forEach { note ->
                            Text("• $note", color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                        }
                    }
                }
            }
        }
    }
}
