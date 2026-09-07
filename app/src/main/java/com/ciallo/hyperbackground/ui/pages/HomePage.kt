package com.ciallo.hyperbackground.ui.pages

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ciallo.hyperbackground.BackgroundContract
import com.ciallo.hyperbackground.ConfigManager
import com.ciallo.hyperbackground.HyperBackgroundApp
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
        item { ModuleStatusCard(activity, sayingEnabled) }
        item { SectionTitle(stringResource(R.string.scope)) }
        item {
            UiCard(activity, Modifier.fillMaxWidth()) {
                ScopeEntry(
                    icon = MiuixIcons.Settings,
                    title = stringResource(R.string.background_home),
                    summary = stringResource(R.string.background_home_summary),
                ) { onOpenBackground(BackgroundContract.HOME) }
                // 「自定义我的设备」取代原「我的设备」通道，直接进入设备卡片页（其内含动态背景卡）。
                ScopeEntry(
                    icon = MiuixIcons.Phone,
                    title = stringResource(R.string.device_card_title),
                    summary = stringResource(R.string.device_card_summary),
                ) { onOpenBackground(MainActivity.ROUTE_DEVICE_CARD) }
                ScopeEntry(
                    icon = MiuixIcons.Settings,
                    title = stringResource(R.string.device_info_title),
                    summary = stringResource(R.string.device_info_summary),
                ) { onOpenBackground(MainActivity.ROUTE_DEVICE_INFO) }
                ScopeEntry(
                    icon = MiuixIcons.Background,
                    title = stringResource(R.string.background_global),
                    summary = stringResource(R.string.background_global_summary),
                ) { onOpenBackground(BackgroundContract.GLOBAL) }
                ScopeEntry(
                    icon = MiuixIcons.Phone,
                    title = stringResource(R.string.background_contacts),
                    summary = stringResource(R.string.background_contacts_summary),
                ) { onOpenBackground(BackgroundContract.CONTACTS) }
            }
        }
    }
}

@Composable
private fun ModuleStatusCard(activity: MainActivity, sayingEnabled: Boolean) {
    // 监听 XposedService 绑定状态，绑定时自动刷新，解决启动时 service 未就绪显示"未激活"的问题
    var active by remember { mutableStateOf(HyperBackgroundApp.isModuleActive()) }
    DisposableEffect(Unit) {
        val listener: (io.github.libxposed.service.XposedService?) -> Unit = {
            active = it != null
        }
        HyperBackgroundApp.addServiceListener(listener)
        onDispose { HyperBackgroundApp.removeServiceListener(listener) }
    }
    val apiVersion = HyperBackgroundApp.xposedService?.apiVersion ?: 0
    val lsposedVersion = remember { getLsposedVersion(activity) }
    val accent = if (active) Color(0xFF4CAF50) else Color(0xFFF44336)
    val statusText = if (active) "已激活" else "未激活"

    // 一言数据
    var refresh by rememberSaveable { mutableIntStateOf(0) }
    var sayingText by remember { mutableStateOf(activity.getString(R.string.saying_loading)) }
    val config = ConfigManager.get(activity)
    val api = config.getString(BackgroundContract.UI_SAYING_API, DEFAULT_API) ?: DEFAULT_API
    val key = config.getString(BackgroundContract.UI_SAYING_KEY, DEFAULT_KEY) ?: DEFAULT_KEY
    LaunchedEffect(api, key, refresh) {
        sayingText = runCatching { fetchSaying(api, key) }.getOrElse { activity.getString(R.string.saying_failed) }
    }

    UiCard(activity, Modifier.fillMaxWidth()) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(48.dp)
                        .background(accent.copy(alpha = 0.15f), CircleShape),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = if (active) "✓" else "✕",
                        color = accent,
                        fontSize = 24.sp,
                        fontWeight = FontWeight.Bold,
                    )
                }
                Spacer(Modifier.width(16.dp))
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(
                        text = "模块状态",
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        fontSize = 13.sp,
                    )
                    Text(
                        text = statusText,
                        color = accent,
                        fontSize = 22.sp,
                        fontWeight = FontWeight.Bold,
                    )
                    val versionLine = buildString {
                        if (lsposedVersion != null) append("LSPosed $lsposedVersion")
                        if (apiVersion > 0) {
                            if (isNotEmpty()) append("  ")
                            append("API: $apiVersion")
                        }
                    }
                    if (versionLine.isNotEmpty()) {
                        Text(
                            text = versionLine,
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                            fontSize = 12.sp,
                        )
                    }
                }
            }
            if (sayingEnabled) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(1.dp)
                        .background(MiuixTheme.colorScheme.onSurfaceVariantSummary.copy(alpha = 0.1f)),
                )
                Text(
                    text = sayingText,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    fontSize = 13.sp,
                    modifier = Modifier.clickable { refresh++ },
                )
            }
        }
    }
}

private fun getLsposedVersion(context: android.content.Context): String? = try {
    context.packageManager.getPackageInfo("org.lsposed.manager", 0).versionName
} catch (_: Exception) {
    null
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
