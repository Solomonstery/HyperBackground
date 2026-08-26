package com.ciallo.hyperbackground.ui.pages

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.ciallo.hyperbackground.BackgroundContract
import com.ciallo.hyperbackground.BuildConfig
import com.ciallo.hyperbackground.R
import com.ciallo.hyperbackground.ui.MainActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.theme.LocalDismissState
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.window.WindowDialog

/**
 * 全局更新提示弹窗。挂在最外层容器，App 启动时检查一次：
 * 当前为正式版、存在更高的正式版、且该版本未被用户忽略时弹出。
 * 左「取消」（忽略此版本，不再提醒），右「更新」（强调色，跳转 Releases）。
 */
@Composable
fun UpdateAvailableDialog(activity: MainActivity) {
    val context = LocalContext.current
    val current = BuildConfig.VERSION_NAME
    var show by remember { mutableStateOf(false) }
    var latest by remember { mutableStateOf("") }
    var notes by remember { mutableStateOf<List<String>>(emptyList()) }

    // 仅在进程内检查一次；预览版不提示（沿用页面内检查更新的口径）。
    LaunchedEffect(Unit) {
        if (isPreviewVersion(current)) return@LaunchedEffect
        val remote = runCatching { fetchLatestStableVersion() }.getOrNull() ?: return@LaunchedEffect
        if (compareVersions(current, remote) >= 0) return@LaunchedEffect
        val ignored = activity.config.getString(BackgroundContract.UI_IGNORED_UPDATE_VERSION, "") ?: ""
        if (normalizeVersion(ignored) == normalizeVersion(remote)) return@LaunchedEffect
        // 更新日志正文取自本地打包的 CHANGELOG.md；取不到则留空由 UI 兜底提示。
        notes = runCatching {
            withContext(Dispatchers.IO) { findEntry(loadAllReleaseNotes(context), remote)?.notes }
        }.getOrNull().orEmpty()
        latest = remote
        show = true
    }

    WindowDialog(
        title = stringResource(R.string.update_dialog_title, latest),
        summary = stringResource(R.string.update_dialog_summary),
        show = show,
        onDismissRequest = { show = false },
    ) {
        val dismiss = LocalDismissState.current
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 240.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                if (notes.isNotEmpty()) {
                    notes.forEach { note ->
                        Text("• $note", color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                    }
                } else {
                    Text(
                        stringResource(R.string.changelog_see_github),
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    )
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                TextButton(
                    modifier = Modifier.weight(1f),
                    text = stringResource(R.string.cancel),
                    onClick = {
                        // 记住忽略的版本号，同一正式版不再提醒。
                        activity.config.edit()
                            .putString(BackgroundContract.UI_IGNORED_UPDATE_VERSION, latest)
                            .apply()
                        dismiss?.invoke()
                    },
                )
                TextButton(
                    modifier = Modifier.weight(1f),
                    text = stringResource(R.string.update_dialog_update),
                    colors = ButtonDefaults.textButtonColorsPrimary(),
                    onClick = {
                        dismiss?.invoke()
                        activity.openUrl(RELEASES_URL)
                    },
                )
            }
        }
    }
}
