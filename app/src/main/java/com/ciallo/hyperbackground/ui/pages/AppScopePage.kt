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
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
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
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.preference.SwitchPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme
import kotlin.math.roundToInt

/**
 * 「软件作用域」二级页：从 LSPosed service 动态读取当前模块启用的作用域包名，
 * 逐个开关控制该应用是否套用材质。
 *
 * 开关状态存在外观配置的「被关闭包名集合」里（默认空 = 全部启用），随外观配置一起
 * 同步到 hook 进程；hook 侧用 `DynamicMaterialPalette.enabledFor` 按当前进程包名过滤。
 */
@Composable
fun AppScopePage(
    activity: MainActivity,
    modifier: Modifier = Modifier,
    padding: PaddingValues = PaddingValues(0.dp),
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
        val service = HyperBackgroundApp.xposedService
        if (service == null) {
            scopedApps = emptyList()
            return@LaunchedEffect
        }
        scopedApps = withContext(Dispatchers.IO) {
            val packages = runCatching { service.getScope() }.getOrNull().orEmpty()
            loadScopedApps(context, packages)
        }
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
                            SwitchPreference(
                                title = app.label,
                                summary = app.packageName,
                                checked = app.packageName !in appearance.disabledAppScopes,
                                startAction = { AppScopeIcon(app) },
                                onCheckedChange = { enabled ->
                                    activity.updateAppearance {
                                        it.withAppScopeEnabled(app.packageName, enabled)
                                    }
                                },
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

private class ScopedApp(
    val packageName: String,
    val label: String,
    val icon: ImageBitmap?,
)

/**
 * 把作用域包名解析成可展示的列表项，并过滤掉不该出现在开关列表里的包名：
 * 模块自身（在自身进程里没有意义）、空包名、以及已卸载 / 无法解析 ApplicationInfo 的包。
 *
 * 不按「有无启动入口」过滤：SystemUI、桌面这类没有 launcher 入口的包同样是关键作用域，
 * 过滤掉会让用户再也无法单独关闭它们。
 */
private fun loadScopedApps(context: Context, packages: List<String>): List<ScopedApp> {
    val manager = context.packageManager
    val self = context.packageName
    val iconSize = (40 * context.resources.displayMetrics.density).roundToInt().coerceAtLeast(1)
    return packages.asSequence()
        .map { it.trim() }
        .filter { it.isNotEmpty() && it != self }
        .distinct()
        .mapNotNull { packageName ->
            val info = runCatching {
                manager.getApplicationInfo(packageName, PackageManager.ApplicationInfoFlags.of(0))
            }.getOrNull() ?: return@mapNotNull null
            ScopedApp(
                packageName = packageName,
                label = runCatching { manager.getApplicationLabel(info).toString() }
                    .getOrDefault(packageName),
                icon = runCatching {
                    manager.getApplicationIcon(info).toAppIconBitmap(iconSize).asImageBitmap()
                }.getOrNull(),
            )
        }
        .sortedBy { it.label.lowercase() }
        .toList()
}

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