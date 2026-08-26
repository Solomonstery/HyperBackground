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
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.ciallo.hyperbackground.BackgroundContract
import com.ciallo.hyperbackground.ConfigManager
import com.ciallo.hyperbackground.R
import com.ciallo.hyperbackground.ui.pages.BackgroundDetailPage
import com.ciallo.hyperbackground.ui.pages.HomePage
import com.ciallo.hyperbackground.ui.pages.SettingsPage
import java.io.File
import java.io.FileOutputStream
import kotlinx.coroutines.launch
import androidx.compose.runtime.rememberCoroutineScope
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.NavigationBar
import top.yukonga.miuix.kmp.basic.NavigationBarItem
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.TopAppBar
import top.yukonga.miuix.kmp.basic.rememberTopAppBarState
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Back
import top.yukonga.miuix.kmp.icon.extended.Home
import top.yukonga.miuix.kmp.icon.extended.Settings
import top.yukonga.miuix.kmp.theme.ColorSchemeMode
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.ThemeController

class MainActivity : ComponentActivity() {
    lateinit var config: ConfigManager
        private set
    var revision by mutableIntStateOf(0)
        private set
    var cardOpacity by mutableFloatStateOf(1f)
        private set
    private var pendingSlot: String? = null
    private var pendingUiBackground = false

    private val picker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri ?: return@registerForActivityResult
        runCatching {
            val mime = contentResolver.getType(uri) ?: "application/octet-stream"
            if (pendingUiBackground) {
                pendingUiBackground = false
                require(mime.startsWith("image/")) { getString(R.string.unsupported_type, mime) }
                copyMedia(config.uiBackgroundFile, uri)
                config.edit().putString(BackgroundContract.UI_BG_MIME, mime).apply()
                toast(R.string.saved)
            } else {
                val slot = pendingSlot.also { pendingSlot = null } ?: return@runCatching
                val videoAllowed = slot == BackgroundContract.DEVICE
                require(mime.startsWith("image/") || videoAllowed && mime.startsWith("video/")) {
                    getString(R.string.unsupported_type, mime)
                }
                copyMedia(config.backgroundFile(slot), uri)
                config.setBackgroundMime(slot, mime)
                toast(R.string.saved)
            }
            revision++
        }.onFailure { toast(getString(R.string.save_failed, it.message ?: "Unknown error")) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        config = ConfigManager.get(this)
        cardOpacity = config.getInt(BackgroundContract.UI_CARD_OPACITY, 100).coerceIn(0, 100) / 100f
        WindowCompat.setDecorFitsSystemWindows(window, false)
        setContent { HyperBackgroundApp() }
    }

    fun chooseBackground(slot: String) {
        pendingSlot = slot
        val types = if (slot == BackgroundContract.DEVICE) {
            arrayOf("image/*", "video/mp4", "video/webm")
        } else {
            arrayOf("image/*")
        }
        picker.launch(types)
    }

    fun chooseUiBackground() {
        pendingUiBackground = true
        picker.launch(arrayOf("image/*"))
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
        config.uiBackgroundFile.delete()
        config.edit().remove(BackgroundContract.UI_BG_MIME).apply()
        revision++
    }

    fun updateCardOpacity(value: Float) {
        cardOpacity = value.coerceIn(0f, 1f)
        config.edit().putInt(BackgroundContract.UI_CARD_OPACITY, (cardOpacity * 100).toInt()).apply()
    }

    fun refreshUi() { revision++ }

    fun openUrl(url: String) {
        runCatching { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
    }

    private fun copyMedia(target: File, uri: Uri) {
        target.parentFile?.mkdirs()
        val temp = File(target.absolutePath + ".tmp")
        var total = 0L
        contentResolver.openInputStream(uri).use { input ->
            requireNotNull(input)
            FileOutputStream(temp).use { output ->
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    total += count
                    require(total <= 200L * 1024L * 1024L) { getString(R.string.file_too_large) }
                    output.write(buffer, 0, count)
                }
                output.fd.sync()
            }
        }
        if (target.exists() && !target.delete()) error("Cannot replace media")
        if (!temp.renameTo(target)) error("Cannot finish media replacement")
        target.setLastModified(System.currentTimeMillis())
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
                ModuleBackground(revision)
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
            if (slot == null) {
                MainTabs(
                    themeMode = themeMode,
                    themeColorEnabled = themeColorEnabled,
                    monet = monet,
                    accent = accent,
                    onThemeMode = onThemeMode,
                    onThemeColorEnabled = onThemeColorEnabled,
                    onMonet = onMonet,
                    onAccent = onAccent,
                    onOpenBackground = { detailSlot = it },
                )
            } else {
                BackgroundDetailScreen(slot = slot, onBack = { detailSlot = null })
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
    ) {
        val pagerState = rememberPagerState(pageCount = { 2 })
        val scope = rememberCoroutineScope()
        Scaffold(
            containerColor = Color.Transparent,
            bottomBar = {
                NavigationBar(
                    color = MiuixTheme.colorScheme.surface.copy(alpha = cardOpacity),
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
            },
        ) { bottomPadding ->
            HorizontalPager(
                state = pagerState,
                modifier = Modifier.fillMaxSize(),
                beyondViewportPageCount = 1,
            ) { page ->
                when (page) {
                    0 -> MainPageScaffold(
                        title = getString(R.string.nav_home),
                        bottomPadding = bottomPadding,
                    ) { padding, scrollModifier ->
                        HomePage(
                            modifier = scrollModifier,
                            padding = padding,
                            onOpenBackground = onOpenBackground,
                        )
                    }
                    else -> MainPageScaffold(
                        title = getString(R.string.nav_settings),
                        bottomPadding = bottomPadding,
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
                        )
                    }
                }
            }
        }
    }

    @Composable
    private fun MainPageScaffold(
        title: String,
        bottomPadding: PaddingValues,
        content: @Composable (PaddingValues, Modifier) -> Unit,
    ) {
        val scrollBehavior = MiuixScrollBehavior(rememberTopAppBarState())
        Scaffold(
            containerColor = Color.Transparent,
            topBar = {
                TopAppBar(
                    color = Color.Transparent,
                    title = title,
                    largeTitle = title,
                    scrollBehavior = scrollBehavior,
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
        val title = when (slot) {
            BackgroundContract.HOME -> getString(R.string.background_home)
            BackgroundContract.DEVICE -> getString(R.string.background_device)
            else -> getString(R.string.background_global)
        }
        Scaffold(
            containerColor = Color.Transparent,
            topBar = {
                TopAppBar(
                    color = Color.Transparent,
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
}
