package com.ciallo.hyperbackground.ui.pages

import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.AdaptiveIconDrawable
import android.graphics.drawable.Drawable
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
import com.ciallo.hyperbackground.HyperBackgroundApp
import com.ciallo.hyperbackground.R
import com.ciallo.hyperbackground.appearance.withAppScopeEnabled
import com.ciallo.hyperbackground.ui.MainActivity
import com.ciallo.hyperbackground.ui.components.SectionTitle
import com.ciallo.hyperbackground.ui.components.UiCard
import io.github.libxposed.service.XposedService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.Switch
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.basic.ArrowRight
import top.yukonga.miuix.kmp.theme.MiuixTheme
import kotlin.math.roundToInt

/**
 * 「软件作用域」二级页：从 LSPosed service 动态读取当前模块启用的作用域包名，
 * 逐行控制该应用是否套用动态适配。
 *
 * 每行是「整包开关 + 箭头」：点开关切换整包开关（存进外观配置的「被关闭包名集合」，
 * 默认空 = 全部启用，随外观配置同步到 hook 进程）；点行内其它区域（含箭头）进入该应用的
 * 详情页，逐组件控制它启用哪些动态适配。hook 侧用
 * `DynamicMaterialPalette.enabledFor(packageName, component)` 同时按包与组件过滤。
 */
@Composable
fun AppScopePage(
    activity: MainActivity,
    modifier: Modifier = Modifier,
    padding: PaddingValues = PaddingValues(0.dp),
    onOpenApp: (packageName: String) -> Unit = {},
) {
    val context = LocalContext.current
    val appearance = activity.appearance
    var moduleActive by remember { mutableStateOf(HyperBackgroundApp.isModuleActive()) }
    var reloadToken by remember { mutableIntStateOf(0) }
    var scopedApps by remember { mutableStateOf<List<ScopedApp>?>(null) }

    // 作用域列表由 LSPosed service 持有，服务绑定 / 断开时都要重读。
    // addServiceListener 注册时会立即回调一次当前状态，所以首帧也能拿到数据。
    DisposableEffect(Unit) {
        val listener: (XposedService?) -> Unit = { service ->
            moduleActive = service != null
            reloadToken++
        }
        HyperBackgroundApp.addServiceListener(listener)
        onDispose { HyperBackgroundApp.removeServiceListener(listener) }
    }

    LaunchedEffect(reloadToken) {
        val packages = readScopePackages()
        scopedApps = withContext(Dispatchers.IO) { loadScopedApps(context, packages) }
    }

    val apps = scopedApps
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
        item { SectionTitle(stringResource(R.string.app_scope_apps_title)) }
        when {
            !moduleActive -> item {
                AppScopeHint(activity, stringResource(R.string.app_scope_inactive))
            }
            apps == null -> Unit
            apps.isEmpty() -> item {
                AppScopeHint(activity, stringResource(R.string.app_scope_empty))
            }
            else -> item {
                UiCard(activity, Modifier.fillMaxWidth()) {
                    Column {
                        apps.forEach { app ->
                            val enabled = app.packageName !in appearance.disabledAppScopes
                            BasicComponent(
                                title = app.label,
                                summary = app.packageName,
                                startAction = { AppScopeIcon(app) },
                                // Miuix 的 endActions 外层是「Column(居中) > Row(默认 Top 对齐)」，
                                // 直接平铺 Switch 与箭头会让 24dp 的箭头贴顶、和开关中心错位；
                                // 这里再包一层 CenterVertically 的 Row 把它们对齐。
                                endActions = {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Switch(
                                            checked = enabled,
                                            onCheckedChange = { value ->
                                                activity.updateAppearance {
                                                    it.withAppScopeEnabled(app.packageName, value)
                                                }
                                            },
                                        )
                                        Spacer(Modifier.width(8.dp))
                                        Icon(
                                            imageVector = MiuixIcons.Basic.ArrowRight,
                                            contentDescription = null,
                                        )
                                    }
                                },
                                onClick = { onOpenApp(app.packageName) },
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun AppScopeIcon(app: ScopedApp) {
    val bitmap = app.icon ?: return
    Image(
        bitmap = bitmap,
        contentDescription = null,
        modifier = Modifier.size(40.dp).clip(RoundedCornerShape(10.dp)),
    )
}

@Composable
private fun AppScopeHint(activity: MainActivity, text: String) {
    UiCard(activity, Modifier.fillMaxWidth()) {
        Text(
            text = text,
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            modifier = Modifier.padding(horizontal = 18.dp, vertical = 17.dp),
        )
    }
}

internal class ScopedApp(
    val packageName: String,
    val label: String,
    val icon: ImageBitmap?,
)

/**
 * 把作用域包名解析成可展示的列表项，只过滤掉模块自身与空包名。
 *
 * 不按「有无启动入口」过滤：SystemUI、桌面这类没有 launcher 入口的包同样是关键作用域，
 * 过滤掉会让用户再也无法单独关闭它们。
 *
 * 也不按「能否解析 ApplicationInfo」过滤：解析失败时退回包名本身作为标题、图标留空。
 * 早期版本在这里直接丢弃解析失败的包，结果在 Android 11+ 的包可见性限制下会静默少一截，
 * 列表数量和作用域对不上，用户也没法再单独关闭这些包。
 */
internal fun loadScopedApps(context: Context, packages: List<String>): List<ScopedApp> {
    val manager = context.packageManager
    val self = context.packageName
    val iconSize = (40 * context.resources.displayMetrics.density).roundToInt().coerceAtLeast(1)
    return packages.asSequence()
        .map { it.trim() }
        .filter { it.isNotEmpty() && it != self }
        .distinct()
        .map { packageName ->
            val info = runCatching {
                manager.getApplicationInfo(packageName, PackageManager.ApplicationInfoFlags.of(0))
            }.getOrNull()
            ScopedApp(
                packageName = packageName,
                label = info
                    ?.let { runCatching { manager.getApplicationLabel(it).toString() }.getOrNull() }
                    ?.takeIf { it.isNotBlank() }
                    ?: packageName,
                icon = info?.let {
                    runCatching {
                        manager.getApplicationIcon(it).toAppIconBitmap(iconSize).asImageBitmap()
                    }.getOrNull()
                },
            )
        }
        .sortedBy { it.label.lowercase() }
        .toList()
}

/** 单个作用域包的可展示标题：解析不到应用名时退回包名。 */
internal fun scopedAppLabel(context: Context, packageName: String): String =
    runCatching {
        val manager = context.packageManager
        val info = manager.getApplicationInfo(packageName, PackageManager.ApplicationInfoFlags.of(0))
        manager.getApplicationLabel(info).toString()
    }.getOrNull()?.takeIf { it.isNotBlank() } ?: packageName

/**
 * 自适应图标的图层画布是 108dp，可见内容只占中央 72dp，直接绘制会带一圈留白；
 * 裁掉外圈再放大填满。普通位图图标直接缩放。
 */
private fun Drawable.toAppIconBitmap(size: Int): Bitmap {
    if (this !is AdaptiveIconDrawable) return toBitmap(size, size)
    val full = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
    setBounds(0, 0, size, size)
    draw(Canvas(full))
    val inset = (size * 18f / 108f).roundToInt()
    val cropped = Bitmap.createBitmap(full, inset, inset, size - inset * 2, size - inset * 2)
    return Bitmap.createScaledBitmap(cropped, size, size, true)
}
