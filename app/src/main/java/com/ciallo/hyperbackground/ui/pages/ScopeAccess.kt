package com.ciallo.hyperbackground.ui.pages

import com.ciallo.hyperbackground.BackgroundContract
import com.ciallo.hyperbackground.HyperBackgroundApp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 读取 LSPosed 当前为该模块启用的作用域包名。
 *
 * 作用域由框架侧持有，只能通过 service 查询；service 未绑定（模块未激活）或调用失败时返回空列表，
 * 由调用方决定是展示「未读取到」还是退回模块声明的默认作用域。
 */
internal suspend fun readScopePackages(): List<String> {
    val service = HyperBackgroundApp.xposedService ?: return emptyList()
    return withContext(Dispatchers.IO) {
        runCatching { service.getScope() }.getOrNull().orEmpty()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .distinct()
    }
}

/**
 * 模块声明的默认作用域。仅在 service 不可用（模块未激活）时作为「重启」的兜底名单，
 * 保证模块尚未被框架拉起时该按钮仍然可用。
 */
internal val DECLARED_SCOPE_PACKAGES = listOf(
    BackgroundContract.PACKAGE_SETTINGS,
    BackgroundContract.PACKAGE_MILINK,
    BackgroundContract.PACKAGE_PHONE,
    BackgroundContract.PACKAGE_ACCOUNT,
    BackgroundContract.PACKAGE_THEME_MANAGER,
    BackgroundContract.PACKAGE_HOME,
    BackgroundContract.PACKAGE_SECURITY_CENTER,
    BackgroundContract.PACKAGE_POWER_KEEPER,
    BackgroundContract.PACKAGE_MI_SETTINGS,
)