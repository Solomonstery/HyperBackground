package com.ciallo.hyperbackground.ui

import android.content.Intent
import android.graphics.ImageDecoder
import android.graphics.RenderEffect
import android.graphics.Shader
import android.graphics.drawable.AnimatedImageDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.widget.ImageView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.EaseInOut
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.zIndex
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.ciallo.hyperbackground.BackgroundContract
import com.ciallo.hyperbackground.ConfigManager
import com.ciallo.hyperbackground.R
import com.ciallo.hyperbackground.appearance.AppearanceUiController
import com.ciallo.hyperbackground.appearance.DeviceProfileSettings
import com.ciallo.hyperbackground.appearance.SettingsAppearanceSettings
import com.ciallo.hyperbackground.ui.pages.BackgroundDetailPage
import com.ciallo.hyperbackground.ui.pages.ChangelogPage
import com.ciallo.hyperbackground.ui.pages.DeviceCardPage
import com.ciallo.hyperbackground.ui.pages.DeviceInfoPage
import com.ciallo.hyperbackground.ui.pages.HomePage
import com.ciallo.hyperbackground.ui.pages.SettingsPage
import com.ciallo.hyperbackground.ui.pages.RestartScopesDialog
import com.ciallo.hyperbackground.ui.pages.UpdateAvailableDialog
import kotlinx.coroutines.launch
import androidx.compose.runtime.rememberCoroutineScope
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.FloatingNavigationBar
import top.yukonga.miuix.kmp.basic.FloatingNavigationBarItem
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.NavigationBar
import top.yukonga.miuix.kmp.basic.NavigationBarItem
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.TopAppBar
import top.yukonga.miuix.kmp.basic.rememberTopAppBarState
import top.yukonga.miuix.kmp.blur.layerBackdrop
import top.yukonga.miuix.kmp.blur.rememberLayerBackdrop
import top.yukonga.miuix.kmp.blur.textureBlur
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Back
import top.yukonga.miuix.kmp.icon.extended.Home
import top.yukonga.miuix.kmp.icon.extended.Settings
import top.yukonga.miuix.kmp.icon.extended.Refresh
import top.yukonga.miuix.kmp.theme.ColorSchemeMode
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.ThemeController

class MainActivity : ComponentActivity() {
    lateinit var config: ConfigManager
        private set
    lateinit var appearanceController: AppearanceUiController
        private set
    var revision by mutableIntStateOf(0)
        private set
    // 外观配置版本号：外观/设备信息表单每次写库后自增，供 Compose 侧刷新（与 revision 语义相同但独立）。
    var appearanceRevision by mutableIntStateOf(0)
        private set
    var appearance by mutableStateOf(SettingsAppearanceSettings())
        private set
    var deviceProfile by mutableStateOf(DeviceProfileSettings())
        private set
    var cardOpacity by mutableFloatStateOf(1f)
        private set
    var bottomBarBlurEnabled by mutableStateOf(false)
        private set
    var floatingBottomBar by mutableStateOf(false)
        private set
    private var pendingMediaResult: ((Uri, String) -> Unit)? = null

    private val picker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri ?: return@registerForActivityResult
        runCatching {
            val mime = contentResolver.getType(uri) ?: "application/octet-stream"
            pendingMediaResult.also { pendingMediaResult = null }?.invoke(uri, mime)
        }.onFailure { toast(getString(R.string.save_failed, it.message ?: "Unknown error")) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        config = ConfigManager.get(this)
        appearanceController = AppearanceUiController(this)
        appearance = appearanceController.appearance
        deviceProfile = appearanceController.deviceProfile
        cardOpacity = config.getInt(BackgroundContract.UI_CARD_OPACITY, 100).coerceIn(0, 100) / 100f
        bottomBarBlurEnabled = config.getBoolean(BackgroundContract.UI_BOTTOM_BAR_BLUR_ENABLED, false)
        floatingBottomBar = config.getBoolean(BackgroundContract.UI_FLOATING_BOTTOM_BAR, false)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        setContent { HyperBackgroundApp() }
    }

    fun chooseBackground(slot: String, onSelected: (Uri, String) -> Unit) {
        pendingMediaResult = { uri, mime ->
            val videoAllowed = slot == BackgroundContract.DEVICE
            require(mime.startsWith("image/") || videoAllowed && mime.startsWith("video/")) {
                getString(R.string.unsupported_type, mime)
            }
            onSelected(uri, mime)
        }
        val types = if (slot == BackgroundContract.DEVICE) {
            arrayOf("image/*", "video/mp4", "video/webm")
        } else {
            arrayOf("image/*")
        }
        picker.launch(types)
    }

    fun chooseUiBackground(onSelected: (Uri, String) -> Unit) {
        pendingMediaResult = { uri, mime ->
            require(mime.startsWith("image/")) { getString(R.string.unsupported_type, mime) }
            onSelected(uri, mime)
        }
        picker.launch(arrayOf("image/*"))
    }

    fun saveBackground(slot: String, uri: Uri, mime: String) {
        runCatching {
            config.saveBackground(slot, uri, mime)
            revision++
            toast(R.string.saved)
        }.onFailure { toast(getString(R.string.save_failed, it.message ?: "Unknown error")) }
    }

    fun saveUiBackground(uri: Uri, mime: String) {
        runCatching {
            config.saveUiBackground(uri, mime)
            revision++
            toast(R.string.saved)
        }.onFailure { toast(getString(R.string.save_failed, it.message ?: "Unknown error")) }
    }

    fun clearBackground(slot: String) {
        if (config.clearBackground(slot)) {
            revision++
            toast(R.string.restore_default)
        } else {
            toast(getString(R.string.save_failed, "Cannot delete media"))
        }
    }

    fun clearUiBackground() {
        if (config.clearUiBackground()) revision++
    }

    /**
     * 外观图片选择：logo 类槽位允许 SVG / XML（保留高级材质），其余槽位仅图片。
     * 选中后落盘并写库，成功即自增 [appearanceRevision] 刷新表单。
     */
    fun chooseAppearanceImage(slot: String, logo: Boolean, onDone: (Boolean) -> Unit = {}) {
        pendingMediaResult = { uri, _ ->
            val ok = appearanceController.importAppearance(slot, uri)
            if (ok) {
                appearance = appearanceController.appearance
                appearanceRevision++
                toast(R.string.saved)
            } else {
                toast(getString(R.string.save_failed, "import"))
            }
            onDone(ok)
        }
        val types = if (logo) {
            arrayOf("image/*", "image/svg+xml", "text/xml", "application/xml", "*/*")
        } else {
            arrayOf("image/*")
        }
        picker.launch(types)
    }

    fun clearAppearanceImage(slot: String) {
        appearanceController.clearAppearance(slot)
        appearance = appearanceController.appearance
        appearanceRevision++
    }

    fun updateAppearance(transform: (SettingsAppearanceSettings) -> SettingsAppearanceSettings) {
        appearanceController.updateAppearance(transform)
        appearance = appearanceController.appearance
        appearanceRevision++
    }

    fun updateDeviceProfile(transform: (DeviceProfileSettings) -> DeviceProfileSettings) {
        appearanceController.updateDeviceProfile(transform)
        deviceProfile = appearanceController.deviceProfile
        appearanceRevision++
    }

    fun updateCardOpacity(value: Float) {
        cardOpacity = value.coerceIn(0f, 1f)
        config.edit().putInt(BackgroundContract.UI_CARD_OPACITY, (cardOpacity * 100).toInt()).apply()
    }

    fun updateBottomBarBlur(enabled: Boolean) {
        bottomBarBlurEnabled = enabled
        config.edit().putBoolean(BackgroundContract.UI_BOTTOM_BAR_BLUR_ENABLED, enabled).apply()
    }

    fun updateFloatingBottomBar(enabled: Boolean) {
        floatingBottomBar = enabled
        config.edit().putBoolean(BackgroundContract.UI_FLOATING_BOTTOM_BAR, enabled).apply()
    }

    fun refreshUi() { revision++ }

    fun openUrl(url: String) {
        runCatching { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
    }

    private fun toast(resId: Int) = toast(getString(resId))
    private fun toast(message: String) = Toast.makeText(this, message, Toast.LENGTH_SHORT).show()

    @Composable
    private fun HyperBackgroundApp() {
        var themeMode by remember { mutableIntStateOf(config.getInt(BackgroundContract.UI_THEME_MODE, 0)) }
        var themeColorEnabled by remember {
            mutableStateOf(config.getBoolean(BackgroundContract.UI_THEME_COLOR_ENABLED, true))
        }
        var monet by remember { mutableStateOf(config.getBoolean(BackgroundContract.UI_MONET, true)) }
        var accent by remember { mutableIntStateOf(config.getInt(BackgroundContract.UI_ACCENT, 0xFF6980FF.toInt())) }
        val systemDark = isSystemInDarkTheme()
        val dark = themeMode == 2 || themeMode == 0 && systemDark
        val baseColorMode = when (themeMode) {
            1 -> ColorSchemeMode.Light
            2 -> ColorSchemeMode.Dark
            else -> ColorSchemeMode.System
        }
        val colorMode = if (themeColorEnabled) {
            when (themeMode) {
                1 -> ColorSchemeMode.MonetLight
                2 -> ColorSchemeMode.MonetDark
                else -> ColorSchemeMode.MonetSystem
            }
        } else {
            baseColorMode
        }
        val controller = remember(colorMode, themeColorEnabled, monet, accent, dark) {
            ThemeController(
                colorSchemeMode = colorMode,
                keyColor = if (themeColorEnabled && !monet) Color(accent) else null,
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
                AppNavigation(
                    themeMode = themeMode,
                    themeColorEnabled = themeColorEnabled,
                    monet = monet,
                    accent = accent,
                    onThemeMode = {
                        themeMode = it
                        config.edit().putInt(BackgroundContract.UI_THEME_MODE, it).apply()
                    },
                    onThemeColorEnabled = {
                        themeColorEnabled = it
                        config.edit().putBoolean(BackgroundContract.UI_THEME_COLOR_ENABLED, it).apply()
                    },
                    onMonet = {
                        monet = it
                        config.edit().putBoolean(BackgroundContract.UI_MONET, it).apply()
                    },
                    onAccent = {
                        accent = it
                        monet = false
                        config.edit().putInt(BackgroundContract.UI_ACCENT, it)
                            .putBoolean(BackgroundContract.UI_MONET, false).apply()
                    },
                )
                UpdateAvailableDialog(this@MainActivity)
            }
        }
    }

    @Composable
    private fun AppNavigation(
        themeMode: Int,
        themeColorEnabled: Boolean,
        monet: Boolean,
        accent: Int,
        onThemeMode: (Int) -> Unit,
        onThemeColorEnabled: (Boolean) -> Unit,
        onMonet: (Boolean) -> Unit,
        onAccent: (Int) -> Unit,
    ) {
        var detailSlot by rememberSaveable { mutableStateOf<String?>(null) }
        BackHandler(enabled = detailSlot != null) { detailSlot = null }
        AnimatedContent(
            targetState = detailSlot,
            modifier = Modifier.fillMaxSize(),
            transitionSpec = {
                if (targetState != null) {
                    (slideIntoContainer(
                        AnimatedContentTransitionScope.SlideDirection.Left,
                        animationSpec = tween(360, easing = EaseInOut),
                    ) + fadeIn(tween(240))) togetherWith
                        (slideOutOfContainer(
                            AnimatedContentTransitionScope.SlideDirection.Left,
                            animationSpec = tween(360, easing = EaseInOut),
                        ) + fadeOut(tween(180)))
                } else {
                    (slideIntoContainer(
                        AnimatedContentTransitionScope.SlideDirection.Right,
                        animationSpec = tween(360, easing = EaseInOut),
                    ) + fadeIn(tween(240))) togetherWith
                        (slideOutOfContainer(
                            AnimatedContentTransitionScope.SlideDirection.Right,
                            animationSpec = tween(360, easing = EaseInOut),
                        ) + fadeOut(tween(180)))
                }.using(SizeTransform(clip = true))
            },
            label = "screen-navigation",
        ) { slot ->
            when (slot) {
                null -> MainTabs(
                    themeMode = themeMode,
                    themeColorEnabled = themeColorEnabled,
                    monet = monet,
                    accent = accent,
                    onThemeMode = onThemeMode,
                    onThemeColorEnabled = onThemeColorEnabled,
                    onMonet = onMonet,
                    onAccent = onAccent,
                    onOpenBackground = { detailSlot = it },
                    onOpenChangelog = { detailSlot = ROUTE_CHANGELOG },
                )
                ROUTE_CHANGELOG -> Box(Modifier.fillMaxSize()) {
                    ModuleBackground(revision)
                    ChangelogScreen(onBack = { detailSlot = null })
                }
                ROUTE_DEVICE_CARD -> Box(Modifier.fillMaxSize()) {
                    ModuleBackground(revision)
                    DeviceCardScreen(onBack = { detailSlot = null })
                }
                ROUTE_DEVICE_INFO -> Box(Modifier.fillMaxSize()) {
                    ModuleBackground(revision)
                    DeviceInfoScreen(onBack = { detailSlot = null })
                }
                else -> Box(Modifier.fillMaxSize()) {
                    ModuleBackground(revision)
                    BackgroundDetailScreen(slot = slot, onBack = { detailSlot = null })
                }
            }
        }
    }

    @Composable
    private fun MainTabs(
        themeMode: Int,
        themeColorEnabled: Boolean,
        monet: Boolean,
        accent: Int,
        onThemeMode: (Int) -> Unit,
        onThemeColorEnabled: (Boolean) -> Unit,
        onMonet: (Boolean) -> Unit,
        onAccent: (Int) -> Unit,
        onOpenBackground: (String) -> Unit,
        onOpenChangelog: () -> Unit,
    ) {
        val pagerState = rememberPagerState(pageCount = { 2 })
        val scope = rememberCoroutineScope()
        val backgroundColor = MiuixTheme.colorScheme.surface
        val backdrop = if (bottomBarBlurEnabled) {
            rememberLayerBackdrop {
                drawRect(backgroundColor)
                drawContent()
            }
        } else {
            null
        }
        var showRestartDialog by remember { mutableStateOf(false) }
        Scaffold(
            containerColor = Color.Transparent,
            bottomBar = {
                val barModifier = if (backdrop != null) {
                    Modifier.textureBlur(
                        backdrop = backdrop,
                        shape = RoundedCornerShape(if (floatingBottomBar) 50.dp else 0.dp),
                    )
                } else {
                    Modifier
                }
                val color = if (backdrop != null) {
                    Color.Transparent
                } else {
                    MiuixTheme.colorScheme.surface.copy(alpha = cardOpacity)
                }
                if (floatingBottomBar) {
                    FloatingNavigationBar(
                        modifier = barModifier.zIndex(2f),
                        color = color,
                    ) {
                        FloatingNavigationBarItem(
                            selected = pagerState.currentPage == 0,
                            onClick = { scope.launch { pagerState.animateScrollToPage(0) } },
                            icon = MiuixIcons.Home,
                            label = getString(R.string.nav_home),
                        )
                        FloatingNavigationBarItem(
                            selected = pagerState.currentPage == 1,
                            onClick = { scope.launch { pagerState.animateScrollToPage(1) } },
                            icon = MiuixIcons.Settings,
                            label = getString(R.string.nav_settings),
                        )
                    }
                } else {
                    NavigationBar(
                        modifier = barModifier.zIndex(2f),
                        color = color,
                        showDivider = false,
                    ) {
                        NavigationBarItem(
                            selected = pagerState.currentPage == 0,
                            onClick = { scope.launch { pagerState.animateScrollToPage(0) } },
                            icon = MiuixIcons.Home,
                            label = getString(R.string.nav_home),
                        )
                        NavigationBarItem(
                            selected = pagerState.currentPage == 1,
                            onClick = { scope.launch { pagerState.animateScrollToPage(1) } },
                            icon = MiuixIcons.Settings,
                            label = getString(R.string.nav_settings),
                        )
                    }
                }
            },
        ) { bottomPadding ->
            Box(
                Modifier.fillMaxSize()
                    .then(if (backdrop != null) Modifier.layerBackdrop(backdrop) else Modifier),
            ) {
                ModuleBackground(revision)
                HorizontalPager(
                    state = pagerState,
                    modifier = Modifier.fillMaxSize(),
                    beyondViewportPageCount = 1,
                ) { page ->
                    when (page) {
                        0 -> MainPageScaffold(
                            title = getString(R.string.nav_home),
                            bottomPadding = bottomPadding,
                            actions = {
                                IconButton(onClick = { showRestartDialog = true }) {
                                    Icon(MiuixIcons.Refresh, contentDescription = getString(R.string.restart_scope))
                                }
                            },
                        ) { padding, scrollModifier ->
                            HomePage(
                                modifier = scrollModifier,
                                padding = padding,
                                revision = revision,
                                onOpenBackground = onOpenBackground,
                            )
                        }
                        else -> MainPageScaffold(
                            title = getString(R.string.nav_settings),
                            bottomPadding = bottomPadding,
                            actions = {
                                IconButton(onClick = { showRestartDialog = true }) {
                                    Icon(MiuixIcons.Refresh, contentDescription = getString(R.string.restart_scope))
                                }
                            },
                        ) { padding, scrollModifier ->
                            SettingsPage(
                                activity = this@MainActivity,
                                modifier = scrollModifier,
                                padding = padding,
                                themeMode = themeMode,
                                themeColorEnabled = themeColorEnabled,
                                monet = monet,
                                accent = accent,
                                onThemeMode = onThemeMode,
                                onThemeColorEnabled = onThemeColorEnabled,
                                onMonet = onMonet,
                                onAccent = onAccent,
                                onOpenChangelog = onOpenChangelog,
                            )
                        }
                    }
                }
            }
            RestartScopesDialog(
                activity = this@MainActivity,
                show = showRestartDialog,
                onDismissRequest = { showRestartDialog = false },
            )
        }
    }

    @Composable
    private fun MainPageScaffold(
        title: String,
        bottomPadding: PaddingValues,
        actions: @Composable androidx.compose.foundation.layout.RowScope.() -> Unit = {},
        content: @Composable (PaddingValues, Modifier) -> Unit,
    ) {
        val scrollBehavior = MiuixScrollBehavior(rememberTopAppBarState())
        val hasUiBackground = remember(revision) { config.uiBackgroundFile.isFile }
        val topBarColor = if (hasUiBackground) {
            Color.Transparent
        } else {
            MiuixTheme.colorScheme.surface.copy(alpha = cardOpacity)
        }
        Scaffold(
            containerColor = Color.Transparent,
            topBar = {
                TopAppBar(
                    color = topBarColor,
                    title = title,
                    largeTitle = title,
                    scrollBehavior = scrollBehavior,
                    actions = actions,
                )
            },
        ) { topPadding ->
            content(
                PaddingValues(
                    top = topPadding.calculateTopPadding(),
                    bottom = bottomPadding.calculateBottomPadding(),
                ),
                Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
            )
        }
    }

    @Composable
    private fun BackgroundDetailScreen(slot: String, onBack: () -> Unit) {
        val scrollBehavior = MiuixScrollBehavior(rememberTopAppBarState())
        val hasUiBackground = remember(revision) { config.uiBackgroundFile.isFile }
        val topBarColor = if (hasUiBackground) {
            Color.Transparent
        } else {
            MiuixTheme.colorScheme.surface.copy(alpha = cardOpacity)
        }
        val title = when (slot) {
            BackgroundContract.HOME -> getString(R.string.background_home)
            BackgroundContract.DEVICE -> getString(R.string.background_device)
            BackgroundContract.CONTACTS -> getString(R.string.background_contacts)
            else -> getString(R.string.background_global)
        }
        Scaffold(
            containerColor = Color.Transparent,
            topBar = {
                TopAppBar(
                    color = topBarColor,
                    title = title,
                    largeTitle = title,
                    scrollBehavior = scrollBehavior,
                    navigationIcon = {
                        IconButton(onClick = onBack) {
                            Icon(MiuixIcons.Back, contentDescription = getString(R.string.back))
                        }
                    },
                )
            },
        ) { padding ->
            BackgroundDetailPage(
                activity = this@MainActivity,
                modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
                padding = padding,
                slot = slot,
                revision = revision,
            )
        }
    }

    @Composable
    private fun ChangelogScreen(onBack: () -> Unit) {
        val scrollBehavior = MiuixScrollBehavior(rememberTopAppBarState())
        val hasUiBackground = remember(revision) { config.uiBackgroundFile.isFile }
        val topBarColor = if (hasUiBackground) {
            Color.Transparent
        } else {
            MiuixTheme.colorScheme.surface.copy(alpha = cardOpacity)
        }
        val title = getString(R.string.changelog)
        Scaffold(
            containerColor = Color.Transparent,
            topBar = {
                TopAppBar(
                    color = topBarColor,
                    title = title,
                    largeTitle = title,
                    scrollBehavior = scrollBehavior,
                    navigationIcon = {
                        IconButton(onClick = onBack) {
                            Icon(MiuixIcons.Back, contentDescription = getString(R.string.back))
                        }
                    },
                )
            },
        ) { padding ->
            ChangelogPage(
                activity = this@MainActivity,
                modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
                padding = padding,
            )
        }
    }

    @Composable
    private fun DeviceCardScreen(onBack: () -> Unit) {
        val scrollBehavior = MiuixScrollBehavior(rememberTopAppBarState())
        val hasUiBackground = remember(revision) { config.uiBackgroundFile.isFile }
        val topBarColor = if (hasUiBackground) {
            Color.Transparent
        } else {
            MiuixTheme.colorScheme.surface.copy(alpha = cardOpacity)
        }
        val title = getString(R.string.device_card_title)
        Scaffold(
            containerColor = Color.Transparent,
            topBar = {
                TopAppBar(
                    color = topBarColor,
                    title = title,
                    largeTitle = title,
                    scrollBehavior = scrollBehavior,
                    navigationIcon = {
                        IconButton(onClick = onBack) {
                            Icon(MiuixIcons.Back, contentDescription = getString(R.string.back))
                        }
                    },
                )
            },
        ) { padding ->
            DeviceCardPage(
                activity = this@MainActivity,
                modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
                padding = padding,
            )
        }
    }

    @Composable
    private fun DeviceInfoScreen(onBack: () -> Unit) {
        val scrollBehavior = MiuixScrollBehavior(rememberTopAppBarState())
        val hasUiBackground = remember(revision) { config.uiBackgroundFile.isFile }
        val topBarColor = if (hasUiBackground) {
            Color.Transparent
        } else {
            MiuixTheme.colorScheme.surface.copy(alpha = cardOpacity)
        }
        val title = getString(R.string.device_info_title)
        Scaffold(
            containerColor = Color.Transparent,
            topBar = {
                TopAppBar(
                    color = topBarColor,
                    title = title,
                    largeTitle = title,
                    scrollBehavior = scrollBehavior,
                    navigationIcon = {
                        IconButton(onClick = onBack) {
                            Icon(MiuixIcons.Back, contentDescription = getString(R.string.back))
                        }
                    },
                )
            },
        ) { padding ->
            DeviceInfoPage(
                activity = this@MainActivity,
                modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
                padding = padding,
            )
        }
    }

    @Composable
    private fun ModuleBackground(revision: Int) {
        val file = remember(revision) { config.uiBackgroundFile }
        if (!file.isFile) return
        val opacity = config.getInt(BackgroundContract.UI_BG_OPACITY, 100) / 100f
        val blur = config.getBoolean(BackgroundContract.UI_BG_BLUR_ENABLED, false)
        val radius = config.getInt(BackgroundContract.UI_BG_BLUR_RADIUS, 20).toFloat()
        key(revision, file.lastModified(), opacity, blur, radius) {
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { context ->
                    ImageView(context).apply {
                        scaleType = ImageView.ScaleType.CENTER_CROP
                        alpha = opacity
                        runCatching {
                            val drawable = ImageDecoder.decodeDrawable(ImageDecoder.createSource(file))
                            setImageDrawable(drawable)
                            if (drawable is AnimatedImageDrawable) drawable.start()
                            if (Build.VERSION.SDK_INT >= 31 && blur && radius > 0f) {
                                setRenderEffect(RenderEffect.createBlurEffect(radius, radius, Shader.TileMode.CLAMP))
                            }
                        }
                    }
                },
            )
        }
    }

    companion object {
        // 二级页导航哨兵：复用 detailSlot 的 AnimatedContent/返回动画承载更新日志与外观页，
        // 取一个不会与背景 slot（home/device/global）冲突的值。
        const val ROUTE_CHANGELOG = "__changelog__"
        const val ROUTE_DEVICE_CARD = "__appearance_device_card__"
        const val ROUTE_DEVICE_INFO = "__appearance_device_info__"
    }
}
