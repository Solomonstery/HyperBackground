package com.ciallo.hyperbackground

import android.content.Intent
import android.content.SharedPreferences
import android.graphics.Color as AndroidColor
import android.graphics.ImageDecoder
import android.graphics.RenderEffect
import android.graphics.Shader
import android.graphics.drawable.AnimatedImageDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.ImageView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.graphics.drawable.toBitmap
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsControllerCompat
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.CardDefaults
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.Slider
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.TopAppBar
import top.yukonga.miuix.kmp.preference.SwitchPreference
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Background
import top.yukonga.miuix.kmp.icon.extended.Phone
import top.yukonga.miuix.kmp.icon.extended.Settings
import top.yukonga.miuix.kmp.theme.ColorSchemeMode
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.ThemeController
import top.yukonga.miuix.kmp.utils.overScrollVertical
import top.yukonga.miuix.kmp.utils.scrollEndHaptic
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

class ConfigActivity : ComponentActivity() {
    private lateinit var prefs: SharedPreferences
    private var pendingSlot: String? = null
    private var pendingUiBackground = false
    private var revision by mutableIntStateOf(0)
    private var uiCardOpacity by mutableFloatStateOf(1f)

    private val picker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri ?: return@registerForActivityResult
        try {
            val mime = contentResolver.getType(uri) ?: "application/octet-stream"
            if (pendingUiBackground) {
                pendingUiBackground = false
                if (!mime.startsWith("image/")) {
                    toast("模块背景仅支持图片 / GIF / WebP")
                    return@registerForActivityResult
                }
                saveFileTo(File(filesDir, "ui_background.bin"), uri)
                prefs.edit().putString(BackgroundContract.UI_BG_MIME, mime).apply()
                toast("模块背景已保存")
                revision++
                return@registerForActivityResult
            }
            val slot = pendingSlot.also { pendingSlot = null } ?: return@registerForActivityResult
            val allowVideo = slot == BackgroundContract.DEVICE
            if (!mime.startsWith("image/") && !(allowVideo && mime.startsWith("video/"))) {
                toast("不支持这种文件类型：$mime")
                return@registerForActivityResult
            }
            val dir = File(filesDir, "backgrounds").apply { mkdirs() }
            saveFileTo(File(dir, "$slot.bin"), uri)
            prefs.edit().putString(BackgroundContract.MIME_PREFIX + slot, mime).apply()
            toast("已保存；重新进入对应设置页面后生效")
            revision++
        } catch (t: Throwable) {
            toast("保存失败：${t.message ?: "未知错误"}")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = getSharedPreferences(BackgroundContract.PREFS, 0)
        uiCardOpacity = prefs.getInt(BackgroundContract.UI_CARD_OPACITY, 100).coerceIn(0, 100) / 100f
        WindowCompat.setDecorFitsSystemWindows(window, false)
        setContent { HyperBackgroundApp() }
    }

    @Composable
    private fun HyperBackgroundApp() {
        var themeMode by remember { mutableIntStateOf(prefs.getInt(BackgroundContract.UI_THEME_MODE, BackgroundContract.UI_THEME_FOLLOW)) }
        var monet by remember { mutableStateOf(prefs.getBoolean(BackgroundContract.UI_MONET, true)) }
        var accent by remember { mutableIntStateOf(prefs.getInt(BackgroundContract.UI_ACCENT, 0xFF6980FF.toInt())) }
        var sayingApi by remember { mutableStateOf(prefs.getString(BackgroundContract.UI_SAYING_API, DEFAULT_SAYING_API) ?: DEFAULT_SAYING_API) }
        var sayingKey by remember { mutableStateOf(prefs.getString(BackgroundContract.UI_SAYING_KEY, DEFAULT_SAYING_KEY) ?: DEFAULT_SAYING_KEY) }
        val systemDark = isSystemInDarkTheme()
        val dark = themeMode == BackgroundContract.UI_THEME_DARK ||
            (themeMode == BackgroundContract.UI_THEME_FOLLOW && systemDark)
        // Miuix 只有 Monet* 模式才会消费 keyColor。
        // 自定义调色盘关闭 Monet 后仍要走 Monet*，只是把壁纸色换成用户选择的 seed color。
        val mode = when (themeMode) {
            BackgroundContract.UI_THEME_LIGHT -> ColorSchemeMode.MonetLight
            BackgroundContract.UI_THEME_DARK -> ColorSchemeMode.MonetDark
            else -> ColorSchemeMode.MonetSystem
        }
        val controller = remember(mode, monet, accent, dark) {
            ThemeController(
                colorSchemeMode = mode,
                keyColor = if (monet) null else Color(accent),
                isDark = dark,
            )
        }

        MiuixTheme(controller = controller) {
            LaunchedEffect(dark) {
                WindowInsetsControllerCompat(window, window.decorView).apply {
                    isAppearanceLightStatusBars = !dark
                    isAppearanceLightNavigationBars = !dark
                }
            }
            Box(Modifier.fillMaxSize().background(MiuixTheme.colorScheme.surface)) {
                ModuleBackground(revision)
                MainScreen(
                    revision = revision,
                    themeMode = themeMode,
                    monet = monet,
                    accent = accent,
                    onThemeMode = {
                        themeMode = it
                        prefs.edit().putInt(BackgroundContract.UI_THEME_MODE, it).apply()
                    },
                    onMonet = {
                        monet = it
                        prefs.edit().putBoolean(BackgroundContract.UI_MONET, it).apply()
                    },
                    onAccent = {
                        accent = it
                        monet = false
                        prefs.edit().putInt(BackgroundContract.UI_ACCENT, it)
                            .putBoolean(BackgroundContract.UI_MONET, false).apply()
                    },
                    sayingApi = sayingApi,
                    sayingKey = sayingKey,
                    onSayingApi = {
                        sayingApi = it
                        prefs.edit().putString(BackgroundContract.UI_SAYING_API, it).apply()
                    },
                    onSayingKey = {
                        sayingKey = it
                        prefs.edit().putString(BackgroundContract.UI_SAYING_KEY, it).apply()
                    }
                )
            }
        }
    }

    @Composable
    private fun ModuleBackground(revision: Int) {
        val file = remember(revision) { File(filesDir, "ui_background.bin") }
        if (!file.isFile) return
        val opacity = prefs.getInt(BackgroundContract.UI_BG_OPACITY, 100) / 100f
        val blurEnabled = prefs.getBoolean(BackgroundContract.UI_BG_BLUR_ENABLED, false)
        val blurRadius = prefs.getInt(BackgroundContract.UI_BG_BLUR_RADIUS, 20).toFloat()

        // AndroidView 的 factory 不会因为普通重组自动执行；用 key 确保换图/透明度/模糊后立即刷新。
        key(revision, file.lastModified(), opacity, blurEnabled, blurRadius) {
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { ctx ->
                    ImageView(ctx).apply {
                        scaleType = ImageView.ScaleType.CENTER_CROP
                        alpha = opacity
                        runCatching {
                            val drawable = ImageDecoder.decodeDrawable(ImageDecoder.createSource(file))
                            setImageDrawable(drawable)
                            if (drawable is AnimatedImageDrawable) drawable.start()
                            if (Build.VERSION.SDK_INT >= 31 && blurEnabled && blurRadius > 0f) {
                                setRenderEffect(RenderEffect.createBlurEffect(blurRadius, blurRadius, Shader.TileMode.CLAMP))
                            }
                        }
                    }
                }
            )
        }
    }

    @Composable
    private fun MainScreen(
        revision: Int,
        themeMode: Int,
        monet: Boolean,
        accent: Int,
        onThemeMode: (Int) -> Unit,
        onMonet: (Boolean) -> Unit,
        onAccent: (Int) -> Unit,
        sayingApi: String,
        sayingKey: String,
        onSayingApi: (String) -> Unit,
        onSayingKey: (String) -> Unit,
    ) {
        var page by rememberSaveable { mutableIntStateOf(0) }
        Scaffold(
            containerColor = Color.Transparent,
            topBar = {
                TopAppBar(
                    color = Color.Transparent,
                    title = "HyperBG",
                )
            },
            popupHost = { },
            contentWindowInsets = WindowInsets.systemBars
        ) { innerPadding ->
            Column(
                Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
            ) {
                SayingHeader(sayingApi, sayingKey)
                PageSwitcher(page) { page = it }
                PageContent(
                    page = page,
                    revision = revision,
                    themeMode = themeMode,
                    monet = monet,
                    accent = accent,
                    onThemeMode = onThemeMode,
                    onMonet = onMonet,
                    onAccent = onAccent,
                    sayingApi = sayingApi,
                    sayingKey = sayingKey,
                    onSayingApi = onSayingApi,
                    onSayingKey = onSayingKey
                )
            }
        }
    }

    @Composable
    private fun PageSwitcher(selected: Int, onSelected: (Int) -> Unit) {
        val labels = listOf("背景", "设置", "外观", "关于")
        UiCard(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp)
        ) {
            Row(
                modifier = Modifier.padding(5.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                labels.forEachIndexed { index, label ->
                    val active = selected == index
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(14.dp))
                            .background(if (active) MiuixTheme.colorScheme.primaryContainer else Color.Transparent)
                            .clickable { onSelected(index) }
                            .padding(vertical = 10.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            label,
                            color = if (active) MiuixTheme.colorScheme.onPrimaryContainer
                            else MiuixTheme.colorScheme.onSurfaceVariantActions
                        )
                    }
                }
            }
        }
    }

    @Composable
    private fun PageContent(
        page: Int,
        revision: Int,
        themeMode: Int,
        monet: Boolean,
        accent: Int,
        onThemeMode: (Int) -> Unit,
        onMonet: (Boolean) -> Unit,
        onAccent: (Int) -> Unit,
        sayingApi: String,
        sayingKey: String,
        onSayingApi: (String) -> Unit,
        onSayingKey: (String) -> Unit,
    ) {
        LazyColumn(
            modifier = Modifier
                .fillMaxHeight()
                .scrollEndHaptic()
                .overScrollVertical()
                .padding(horizontal = 12.dp),
            overscrollEffect = null,
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item { Spacer(Modifier.height(2.dp)) }
            when (page) {
                0 -> {
                    item { BackgroundCard(BackgroundContract.HOME, "设置主页", "独立控制 HyperOS 设置首页", MiuixIcons.Settings, false, revision) }
                    item { BackgroundCard(BackgroundContract.DEVICE, "我的设备", "图片 / GIF / WebP / MP4 / WebM", MiuixIcons.Phone, true, revision) }
                    item { BackgroundCard(BackgroundContract.GLOBAL, "全局背景", "设置二级页 + 系统设置组件", MiuixIcons.Background, false, revision) }
                }
                1 -> item { TextAndSettingsAppearanceCard() }
                2 -> {
                    item { ModuleAppearanceCard(themeMode, monet, accent, onThemeMode, onMonet, onAccent, revision) }
                    item { SayingSettingsCard(sayingApi, sayingKey, onSayingApi, onSayingKey) }
                }
                3 -> {
                    item { VersionCheckCard() }
                    item { CurrentReleaseNotesCard() }
                    item { AuthorCard() }
                }
            }
            item { Spacer(Modifier.height(28.dp)) }
        }
    }


    @Composable
    private fun BackgroundCard(slot: String, title: String, summary: String, icon: ImageVector, allowVideo: Boolean, revision: Int) {
        var expanded by rememberSaveable(slot) { mutableStateOf(false) }
        var opacity by remember(slot, revision) { mutableFloatStateOf(prefs.getInt(BackgroundContract.OPACITY_PREFIX + slot, 100).toFloat()) }
        var blur by remember(slot, revision) { mutableStateOf(prefs.getBoolean(BackgroundContract.BLUR_ENABLED_PREFIX + slot, false)) }
        var radius by remember(slot, revision) { mutableFloatStateOf(prefs.getInt(BackgroundContract.BLUR_RADIUS_PREFIX + slot, 20).toFloat()) }
        val file = remember(slot, revision) { File(File(filesDir, "backgrounds"), "$slot.bin") }
        val status = if (file.isFile) "已启用 · ${humanSize(file.length())}" else "跟随系统默认"

        UiCard(Modifier.fillMaxWidth()) {
            Column {
                PreferenceHeader(icon, title, summary, status, expanded) { expanded = !expanded }
                if (expanded) {
                    Row(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        TextButton(
                            modifier = Modifier.weight(1f),
                            text = if (allowVideo) "选择媒体" else "选择图片",
                            onClick = {
                                pendingSlot = slot
                                picker.launch(if (allowVideo) arrayOf("image/*", "video/mp4", "video/webm") else arrayOf("image/*"))
                            }
                        )
                        TextButton(modifier = Modifier.weight(1f), text = "恢复默认", onClick = { resetSlot(slot) })
                    }
                    SliderPreference("透明度", opacity, 0f..100f, "%") {
                        opacity = it
                        prefs.edit().putInt(BackgroundContract.OPACITY_PREFIX + slot, it.toInt()).apply()
                    }
                    SwitchPreference(
                        title = "背景模糊",
                        summary = "仅模糊背景媒体，不影响设置内容",
                        checked = blur,
                        onCheckedChange = {
                            blur = it
                            prefs.edit().putBoolean(BackgroundContract.BLUR_ENABLED_PREFIX + slot, it).apply()
                        }
                    )
                    SliderPreference("模糊强度", radius, 0f..80f, "") {
                        radius = it
                        prefs.edit().putInt(BackgroundContract.BLUR_RADIUS_PREFIX + slot, it.toInt()).apply()
                    }
                    Spacer(Modifier.height(8.dp))
                }
            }
        }
    }

    @Composable
    private fun PreferenceHeader(icon: ImageVector, title: String, summary: String, status: String, expanded: Boolean? = null, onClick: (() -> Unit)? = null) {
        Row(
            Modifier
                .fillMaxWidth()
                .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                Modifier.size(42.dp).clip(RoundedCornerShape(13.dp)).background(MiuixTheme.colorScheme.secondaryContainer),
                contentAlignment = Alignment.Center
            ) { Icon(icon, contentDescription = title, tint = MiuixTheme.colorScheme.onSecondaryContainer, modifier = Modifier.size(24.dp)) }
            Column(Modifier.padding(start = 12.dp).weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(title, style = MiuixTheme.textStyles.headline1)
                Text(summary, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                Text(status, color = MiuixTheme.colorScheme.primary)
            }
            if (expanded != null) {
                Text(if (expanded) "收起" else "展开", color = MiuixTheme.colorScheme.onSurfaceVariantActions)
            }
        }
    }

    @Composable
    private fun SliderPreference(label: String, value: Float, range: ClosedFloatingPointRange<Float>, suffix: String, onValue: (Float) -> Unit) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 5.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(label)
                Text("${value.toInt()}$suffix", color = MiuixTheme.colorScheme.onSurfaceVariantActions)
            }
            Slider(value = value, onValueChange = onValue, valueRange = range)
        }
    }

    @Composable
    private fun TextAndSettingsAppearanceCard() {
        var fontMode by remember { mutableIntStateOf(prefs.getInt(BackgroundContract.FONT_MODE, BackgroundContract.FONT_FOLLOW)) }
        var settingsMode by remember { mutableIntStateOf(prefs.getInt(BackgroundContract.SETTINGS_THEME_MODE, BackgroundContract.SETTINGS_THEME_FOLLOW)) }
        UiCard(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(vertical = 10.dp)) {
                PreferenceHeader(MiuixIcons.Settings, "设置应用外观", "深浅模式与文字颜色互相独立", "切换后重新打开“设置”")
                SectionChoice("Settings 深浅模式", listOf("跟随", "浅色", "深色"), settingsMode) {
                    settingsMode = it
                    prefs.edit().putInt(BackgroundContract.SETTINGS_THEME_MODE, it).apply()
                }
                SectionChoice("文字颜色", listOf("跟随", "浅色字", "深色字"), fontMode) {
                    fontMode = it
                    prefs.edit().putInt(BackgroundContract.FONT_MODE, it).apply()
                }
            }
        }
    }

    @Composable
    private fun SectionChoice(title: String, labels: List<String>, selected: Int, onSelected: (Int) -> Unit) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, color = MiuixTheme.colorScheme.onSurface)
            Row(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(16.dp))
                    .background(MiuixTheme.colorScheme.secondaryContainer.copy(alpha = 0.45f))
                    .padding(4.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                labels.forEachIndexed { index, label ->
                    val active = selected.coerceIn(labels.indices) == index
                    Box(
                        Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(13.dp))
                            .background(if (active) MiuixTheme.colorScheme.primaryContainer else Color.Transparent)
                            .clickable { onSelected(index) }
                            .padding(vertical = 11.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            label,
                            color = if (active) MiuixTheme.colorScheme.onPrimaryContainer
                            else MiuixTheme.colorScheme.onSurfaceVariantActions
                        )
                    }
                }
            }
        }
    }

    @Composable
    private fun ModuleAppearanceCard(
        themeMode: Int,
        monet: Boolean,
        accent: Int,
        onThemeMode: (Int) -> Unit,
        onMonet: (Boolean) -> Unit,
        onAccent: (Int) -> Unit,
        revision: Int,
    ) {
        var bgOpacity by remember(revision) { mutableFloatStateOf(prefs.getInt(BackgroundContract.UI_BG_OPACITY, 100).toFloat()) }
        var bgBlur by remember(revision) { mutableStateOf(prefs.getBoolean(BackgroundContract.UI_BG_BLUR_ENABLED, false)) }
        var bgRadius by remember(revision) { mutableFloatStateOf(prefs.getInt(BackgroundContract.UI_BG_BLUR_RADIUS, 20).toFloat()) }
        val uiFile = remember(revision) { File(filesDir, "ui_background.bin") }

        UiCard(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(vertical = 10.dp)) {
                PreferenceHeader(
                    MiuixIcons.Background, "模块外观", "Compose + MIUIX",
                    if (monet) "Monet 壁纸取色" else String.format("自定义色 #%06X", accent and 0xFFFFFF)
                )
                SectionChoice("界面模式", listOf("跟随", "浅色", "深色"), themeMode, onThemeMode)
                SwitchPreference(
                    title = "Monet 壁纸取色",
                    summary = "跟随当前壁纸生成 MIUIX 动态色",
                    checked = monet,
                    onCheckedChange = onMonet
                )
                ModernAccentPicker(accent, onAccent)
                Row(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    TextButton(
                        modifier = Modifier.weight(1f),
                        text = if (uiFile.isFile) "更换模块背景" else "选择模块背景",
                        onClick = {
                            pendingUiBackground = true
                            picker.launch(arrayOf("image/*"))
                        }
                    )
                    TextButton(
                        modifier = Modifier.weight(1f),
                        text = "清除背景",
                        onClick = {
                            if (uiFile.exists()) uiFile.delete()
                            prefs.edit().remove(BackgroundContract.UI_BG_MIME).apply()
                            this@ConfigActivity.revision++
                        }
                    )
                }
                SliderPreference("背景图透明度", bgOpacity, 0f..100f, "%") {
                    bgOpacity = it
                    prefs.edit().putInt(BackgroundContract.UI_BG_OPACITY, it.toInt()).apply()
                    this@ConfigActivity.revision++
                }
                SliderPreference("卡片透明度", uiCardOpacity * 100f, 0f..100f, "%") {
                    uiCardOpacity = it.coerceIn(0f, 100f) / 100f
                    prefs.edit().putInt(BackgroundContract.UI_CARD_OPACITY, it.toInt()).apply()
                }
                SwitchPreference(
                    title = "模块背景模糊",
                    checked = bgBlur,
                    onCheckedChange = {
                        bgBlur = it
                        prefs.edit().putBoolean(BackgroundContract.UI_BG_BLUR_ENABLED, it).apply()
                        this@ConfigActivity.revision++
                    }
                )
                SliderPreference("模块模糊强度", bgRadius, 0f..80f, "") {
                    bgRadius = it
                    prefs.edit().putInt(BackgroundContract.UI_BG_BLUR_RADIUS, it.toInt()).apply()
                    this@ConfigActivity.revision++
                }
            }
        }
    }

    @Composable
    private fun ModernAccentPicker(current: Int, onAccent: (Int) -> Unit) {
        val defaultAccent = 0xFF6980FF.toInt()
        val presets = listOf(
            0xFF6980FF, 0xFF5E8BFF, 0xFF45B6FE, 0xFF22B8A7,
            0xFF45C46B, 0xFF98C93C, 0xFFF0C24D, 0xFFF79A47,
            0xFFF46F56, 0xFFEA5A89, 0xFFCA67E8, 0xFF8D6BE8,
        ).map { it.toInt() }
        val initialHsv = remember(current) {
            FloatArray(3).also { AndroidColor.colorToHSV(current, it) }
        }
        var hue by remember(current) { mutableFloatStateOf(initialHsv[0]) }
        var saturation by remember(current) { mutableFloatStateOf(initialHsv[1] * 100f) }
        var brightness by remember(current) { mutableFloatStateOf(initialHsv[2] * 100f) }
        var hexText by remember(current) { mutableStateOf(formatHexColor(current)) }
        val parsedHex = remember(hexText) { parseHexColor(hexText) }

        fun applyHsv(nextHue: Float = hue, nextSaturation: Float = saturation, nextBrightness: Float = brightness) {
            val alpha = AndroidColor.alpha(current)
            onAccent(
                AndroidColor.HSVToColor(
                    alpha,
                    floatArrayOf(
                        nextHue.coerceIn(0f, 360f),
                        (nextSaturation / 100f).coerceIn(0f, 1f),
                        (nextBrightness / 100f).coerceIn(0f, 1f),
                    ),
                ),
            )
        }

        Column(
            Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text("主题调色盘", color = MiuixTheme.colorScheme.onSurface)
            presets.chunked(6).forEach { rowColors ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    rowColors.forEach { color ->
                        Box(
                            Modifier
                                .size(if ((color and 0xFFFFFF) == (current and 0xFFFFFF)) 36.dp else 32.dp)
                                .clip(CircleShape)
                                .background(Color(color))
                                .clickable { onAccent(color) },
                        )
                    }
                }
            }

            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Box(
                    Modifier
                        .size(42.dp)
                        .clip(RoundedCornerShape(13.dp))
                        .background(Color(current)),
                )
                Text(formatHexColor(current), color = MiuixTheme.colorScheme.onSurfaceVariantActions)
            }

            SliderPreference("色相", hue, 0f..360f, "°") {
                hue = it
                applyHsv(nextHue = it)
            }
            SliderPreference("饱和度", saturation, 0f..100f, "%") {
                saturation = it
                applyHsv(nextSaturation = it)
            }
            SliderPreference("明度", brightness, 0f..100f, "%") {
                brightness = it
                applyHsv(nextBrightness = it)
            }

            Box(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp))
                    .background(MiuixTheme.colorScheme.surfaceContainer)
                    .padding(horizontal = 14.dp, vertical = 12.dp),
            ) {
                BasicTextField(
                    value = hexText,
                    onValueChange = { raw ->
                        hexText = sanitizeHexInput(raw)
                        parseHexColor(hexText)?.let(onAccent)
                    },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii),
                    textStyle = MiuixTheme.textStyles.body1.copy(color = MiuixTheme.colorScheme.onSurface),
                )
            }
            Text(
                if (parsedHex != null) "支持 #RRGGBB 或 #AARRGGBB" else "请输入 6 位或 8 位 HEX 颜色代码",
                color = if (parsedHex != null) MiuixTheme.colorScheme.onSurfaceVariantSummary else Color(0xFFFF6B6B),
            )
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                TextButton(
                    modifier = Modifier.weight(1f),
                    text = "应用 HEX",
                    onClick = { parsedHex?.let(onAccent) },
                )
                TextButton(
                    modifier = Modifier.weight(1f),
                    text = "恢复默认",
                    onClick = { onAccent(defaultAccent) },
                )
            }
        }
    }

    private fun formatHexColor(color: Int): String {
        return if (AndroidColor.alpha(color) == 0xFF) {
            String.format("#%06X", color and 0xFFFFFF)
        } else {
            String.format("#%08X", color)
        }
    }

    private fun sanitizeHexInput(raw: String): String {
        val digits = raw.trim().removePrefix("#")
            .filter { it.isDigit() || it.lowercaseChar() in 'a'..'f' }
            .take(8)
            .uppercase()
        return "#$digits"
    }

    private fun parseHexColor(text: String): Int? {
        val digits = text.trim().removePrefix("#")
        if (digits.length != 6 && digits.length != 8) return null
        return runCatching {
            val value = digits.toLong(16)
            if (digits.length == 6) (0xFF000000L or value).toInt() else value.toInt()
        }.getOrNull()
    }

    @Composable
    private fun UiCard(
        modifier: Modifier = Modifier,
        content: @Composable ColumnScope.() -> Unit,
    ) {
        Card(
            modifier = modifier,
            colors = CardDefaults.defaultColors(
                color = MiuixTheme.colorScheme.surfaceContainer.copy(alpha = uiCardOpacity),
                contentColor = MiuixTheme.colorScheme.onSurfaceContainer,
            ),
            content = content,
        )
    }

    @Composable
    private fun SayingHeader(api: String, key: String) {
        var refresh by rememberSaveable { mutableIntStateOf(0) }
        var saying by remember(api, key, refresh) { mutableStateOf("正在获取一言…") }
        LaunchedEffect(api, key, refresh) {
            saying = runCatching { fetchSaying(api, key) }
                .getOrElse { "一言获取失败 · 点这里重试" }
        }
        Column(
            Modifier
                .fillMaxWidth()
                .clickable { refresh++ }
                .padding(horizontal = 18.dp, vertical = 6.dp),
            verticalArrangement = Arrangement.spacedBy(3.dp),
        ) {
            Text("一言", color = MiuixTheme.colorScheme.primary)
            Text(saying, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
        }
    }

    @Composable
    private fun SayingSettingsCard(
        api: String,
        key: String,
        onApi: (String) -> Unit,
        onKey: (String) -> Unit,
    ) {
        var apiDraft by remember(api) { mutableStateOf(api) }
        var keyDraft by remember(key) { mutableStateOf(key) }
        UiCard(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("一言设置", style = MiuixTheme.textStyles.headline1)
                Text("支持自定义 API；读取字段支持点路径，例如 data.text。返回纯文本时可留空。", color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                SimpleInput("API 地址", apiDraft) { apiDraft = it }
                SimpleInput("读取字段", keyDraft) { keyDraft = it }
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    TextButton(
                        modifier = Modifier.weight(1f),
                        text = "恢复默认",
                        onClick = {
                            apiDraft = DEFAULT_SAYING_API
                            keyDraft = DEFAULT_SAYING_KEY
                            onApi(apiDraft)
                            onKey(keyDraft)
                        }
                    )
                    TextButton(
                        modifier = Modifier.weight(1f),
                        text = "保存并刷新",
                        onClick = {
                            val cleanApi = apiDraft.trim().ifBlank { DEFAULT_SAYING_API }
                            val cleanKey = keyDraft.trim()
                            apiDraft = cleanApi
                            keyDraft = cleanKey
                            onApi(cleanApi)
                            onKey(cleanKey)
                        }
                    )
                }
            }
        }
    }

    @Composable
    private fun SimpleInput(label: String, value: String, onValueChange: (String) -> Unit) {
        Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Text(label, color = MiuixTheme.colorScheme.onSurfaceVariantActions)
            Box(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp))
                    .background(MiuixTheme.colorScheme.secondaryContainer.copy(alpha = 0.55f))
                    .padding(horizontal = 12.dp, vertical = 11.dp)
            ) {
                BasicTextField(
                    value = value,
                    onValueChange = onValueChange,
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    textStyle = MiuixTheme.textStyles.body1.copy(color = MiuixTheme.colorScheme.onSurface),
                )
            }
        }
    }

    private suspend fun fetchSaying(api: String, key: String): String = withContext(Dispatchers.IO) {
        val url = URL(api.trim())
        val connection = (url.openConnection() as HttpURLConnection).apply {
            connectTimeout = 6000
            readTimeout = 6000
            requestMethod = "GET"
            setRequestProperty("Accept", "application/json, text/plain, */*")
            setRequestProperty("User-Agent", "HyperBG/${BuildConfig.VERSION_NAME}")
        }
        try {
            val code = connection.responseCode
            if (code !in 200..299) error("HTTP $code")
            val body = connection.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }.trim()
            if (body.isBlank()) error("空响应")
            extractSaying(body, key).take(240).ifBlank { error("读取结果为空") }
        } finally {
            connection.disconnect()
        }
    }

    private fun extractSaying(body: String, key: String): String {
        val path = key.trim()
        if (path.isBlank()) return body.trim().trim('"')
        var current: Any = JSONObject(body)
        for (part in path.split('.').filter { it.isNotBlank() }) {
            current = (current as? JSONObject)?.opt(part) ?: error("找不到字段 $part")
        }
        return when (current) {
            JSONObject.NULL -> ""
            is String -> current
            else -> current.toString()
        }.trim()
    }

    @Composable
    private fun VersionCheckCard() {
        var refresh by rememberSaveable { mutableIntStateOf(0) }
        var latest by remember { mutableStateOf<String?>(null) }
        var message by remember { mutableStateOf("正在检查正式版…") }
        var checking by remember { mutableStateOf(true) }
        val current = BuildConfig.VERSION_NAME

        LaunchedEffect(refresh) {
            checking = true
            val result = runCatching { fetchLatestStableVersion() }
            result.onSuccess { remote ->
                latest = remote
                val isPreview = current.contains(Regex("(?i)(test|alpha|beta|rc|dev)"))
                message = when {
                    isPreview -> "当前为测试版 · 正式版最新 $remote"
                    compareVersions(current, remote) < 0 -> "发现新正式版 $remote"
                    else -> "当前已是最新正式版"
                }
            }.onFailure {
                message = "版本检查失败 · ${it.message ?: "网络异常"}"
            }
            checking = false
        }

        UiCard(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("版本验证", style = MiuixTheme.textStyles.headline1)
                Text("当前版本  $current", color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                latest?.let { Text("最新正式版  $it", color = MiuixTheme.colorScheme.onSurfaceVariantSummary) }
                Text(message, color = if (message.startsWith("发现新正式版")) MiuixTheme.colorScheme.primary else MiuixTheme.colorScheme.onSurfaceVariantActions)
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    TextButton(
                        modifier = Modifier.weight(1f),
                        text = if (checking) "检查中…" else "重新检查",
                        onClick = { if (!checking) refresh++ },
                    )
                    TextButton(
                        modifier = Modifier.weight(1f),
                        text = "GitHub Releases",
                        onClick = { openUrl(RELEASES_URL) },
                    )
                }
            }
        }
    }

    @Composable
    private fun CurrentReleaseNotesCard() {
        UiCard(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("本次版本说明", style = MiuixTheme.textStyles.headline1)
                Text("HyperBG ${BuildConfig.VERSION_NAME}", color = MiuixTheme.colorScheme.primary)
                Text("• 全局背景扩展到电话、账号、主题、桌面、手机管家与省电设置。", color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                Text("• 调色盘新增 12 色预设、HSV 滑杆与 6/8 位 HEX 输入。", color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                Text("• 登录、授权、凭据、支付、拨号等敏感窗口保持系统原样。", color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                Text("• 从本版起使用固定私有签名；首次升级需要卸载旧签名版本。", color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
            }
        }
    }

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
            if (tag.isBlank()) error("未找到正式版版本号")
            tag
        } finally {
            connection.disconnect()
        }
    }

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
    private fun AuthorCard() {
        val context = LocalContext.current
        UiCard(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    val bitmap = remember { runCatching { getDrawable(R.drawable.app_icon)!!.toBitmap(120, 120).asImageBitmap() }.getOrNull() }
                    if (bitmap != null) androidx.compose.foundation.Image(bitmap = bitmap, contentDescription = null, modifier = Modifier.size(58.dp).clip(RoundedCornerShape(18.dp)))
                    Column(Modifier.padding(start = 14.dp).weight(1f)) {
                        Text("制作者 · 苍簇", style = MiuixTheme.textStyles.headline1)
                        Text("HyperBG ${BuildConfig.VERSION_NAME}", color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    TextButton(modifier = Modifier.weight(1f), text = "酷安主页", onClick = { openUrl("https://www.coolapk.com/u/18795532") })
                    TextButton(modifier = Modifier.weight(1f), text = "GitHub", onClick = { openUrl("https://github.com/Solomonstery/HyperBackground") })
                }
                TextButton(modifier = Modifier.fillMaxWidth(), text = "打开模块应用信息", onClick = {
                    context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
                })
            }
        }
    }

    private fun resetSlot(slot: String) {
        val f = File(File(filesDir, "backgrounds"), "$slot.bin")
        if (f.exists() && !f.delete()) {
            toast("恢复失败")
            return
        }
        prefs.edit().remove(BackgroundContract.MIME_PREFIX + slot).apply()
        revision++
        toast("已恢复系统默认")
    }

    private fun saveFileTo(target: File, uri: Uri) {
        target.parentFile?.mkdirs()
        val temp = File(target.absolutePath + ".tmp")
        if (temp.exists()) temp.delete()
        var total = 0L
        contentResolver.openInputStream(uri).use { input ->
            requireNotNull(input) { "无法读取文件" }
            FileOutputStream(temp).use { output ->
                val buffer = ByteArray(65536)
                while (true) {
                    val n = input.read(buffer)
                    if (n < 0) break
                    total += n
                    require(total <= 200L * 1024L * 1024L) { "文件不能超过 200 MB" }
                    output.write(buffer, 0, n)
                }
                output.fd.sync()
            }
        }
        if (target.exists() && !target.delete()) error("无法覆盖旧背景")
        if (!temp.renameTo(target)) error("无法完成文件替换")
        target.setLastModified(System.currentTimeMillis())
    }

    private fun openUrl(url: String) {
        runCatching { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }.onFailure { toast("无法打开链接") }
    }

    private fun toast(text: String) = Toast.makeText(this, text, Toast.LENGTH_SHORT).show()

    companion object {
        private const val DEFAULT_SAYING_API = "https://uapis.cn/api/v1/saying"
        private const val DEFAULT_SAYING_KEY = "text"
        private const val LATEST_RELEASE_API = "https://api.github.com/repos/Solomonstery/HyperBackground/releases/latest"
        private const val RELEASES_URL = "https://github.com/Solomonstery/HyperBackground/releases"

        private fun humanSize(b: Long): String = when {
            b >= 1048576L -> String.format("%.1f MB", b / 1048576f)
            b >= 1024L -> String.format("%.1f KB", b / 1024f)
            else -> "$b B"
        }
    }


}
