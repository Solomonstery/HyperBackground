package com.ciallo.hyperbackground.ui.pages

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.ciallo.hyperbackground.BackgroundContract
import com.ciallo.hyperbackground.ConfigManager
import com.ciallo.hyperbackground.R
import com.ciallo.hyperbackground.ui.MainActivity
import com.ciallo.hyperbackground.ui.components.SectionTitle
import com.ciallo.hyperbackground.ui.components.UiCard
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.basic.ArrowRight
import top.yukonga.miuix.kmp.icon.extended.Background
import top.yukonga.miuix.kmp.icon.extended.Phone
import top.yukonga.miuix.kmp.icon.extended.Settings
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
fun HomePage(
    modifier: Modifier = Modifier,
    padding: PaddingValues = PaddingValues(0.dp),
    revision: Int,
    onOpenBackground: (String) -> Unit,
    onOpenLogo: () -> Unit,
) {
    val activity = LocalContext.current as MainActivity
    val sayingEnabled = remember(revision) {
        activity.config.getBoolean(BackgroundContract.UI_SAYING_ENABLED, true)
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
        if (sayingEnabled) {
            item { SectionTitle(stringResource(R.string.saying)) }
            item { SayingCard(activity) }
        }
        item { SectionTitle(stringResource(R.string.scope)) }
        item {
            UiCard(activity, Modifier.fillMaxWidth()) {
                ScopeEntry(
                    icon = MiuixIcons.Settings,
                    title = stringResource(R.string.background_home),
                    summary = stringResource(R.string.background_home_summary),
                ) { onOpenBackground(BackgroundContract.HOME) }
                ScopeEntry(
                    icon = MiuixIcons.Phone,
                    title = stringResource(R.string.background_device),
                    summary = stringResource(R.string.background_device_summary),
                ) { onOpenBackground(BackgroundContract.DEVICE) }
                ScopeEntry(
                    icon = MiuixIcons.Background,
                    title = stringResource(R.string.background_global),
                    summary = stringResource(R.string.background_global_summary),
                ) { onOpenBackground(BackgroundContract.GLOBAL) }
            }
        }
        item { SectionTitle(stringResource(R.string.custom_logo_section)) }
        item {
            UiCard(activity, Modifier.fillMaxWidth()) {
                ScopeEntry(
                    icon = MiuixIcons.Background,
                    title = stringResource(R.string.custom_logo),
                    summary = stringResource(R.string.custom_logo_summary),
                    onClick = onOpenLogo,
                )
            }
        }
    }
}

@Composable
private fun SayingCard(activity: MainActivity) {
    var refresh by rememberSaveable { mutableIntStateOf(0) }
    var text by remember { mutableStateOf(activity.getString(R.string.saying_loading)) }
    val config = ConfigManager.get(activity)
    val api = config.getString(BackgroundContract.UI_SAYING_API, DEFAULT_API) ?: DEFAULT_API
    val key = config.getString(BackgroundContract.UI_SAYING_KEY, DEFAULT_KEY) ?: DEFAULT_KEY
    LaunchedEffect(api, key, refresh) {
        text = runCatching { fetchSaying(api, key) }.getOrElse { activity.getString(R.string.saying_failed) }
    }
    UiCard(activity, Modifier.fillMaxWidth().clickable { refresh++ }) {
        Column(Modifier.padding(18.dp)) {
            Text(text, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
        }
    }
}

@Composable
private fun ScopeEntry(
    icon: ImageVector,
    title: String,
    summary: String,
    onClick: () -> Unit,
) {
    BasicComponent(
        title = title,
        summary = summary,
        startAction = {
            Icon(
                modifier = Modifier.padding(end = 16.dp).size(26.dp),
                imageVector = icon,
                contentDescription = null,
                tint = MiuixTheme.colorScheme.onBackground,
            )
        },
        endActions = {
            Icon(
                imageVector = MiuixIcons.Basic.ArrowRight,
                contentDescription = null,
            )
        },
        onClick = onClick,
    )
}

private suspend fun fetchSaying(api: String, key: String): String = withContext(Dispatchers.IO) {
    val connection = URL(api.trim()).openConnection() as HttpURLConnection
    connection.connectTimeout = 6000
    connection.readTimeout = 6000
    try {
        require(connection.responseCode in 200..299)
        val body = connection.inputStream.bufferedReader().use { it.readText() }.trim()
        if (key.isBlank()) return@withContext body.trim('"').take(240)
        var current: Any = JSONObject(body)
        key.split('.').filter(String::isNotBlank).forEach { part ->
            current = (current as JSONObject).get(part)
        }
        current.toString().take(240)
    } finally {
        connection.disconnect()
    }
}

private const val DEFAULT_API = "https://uapis.cn/api/v1/saying"
private const val DEFAULT_KEY = "text"
