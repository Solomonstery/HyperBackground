package com.ciallo.hyperbackground.appearance

import android.app.Activity
import android.content.Context
import android.os.Bundle
import android.view.View
import com.ciallo.hyperbackground.HookRuntime
import com.ciallo.hyperbackground.util.hookMethod
import com.ciallo.hyperbackground.util.log
import java.util.Collections
import java.util.WeakHashMap

/**
 * 主题商店「系统个性化」页（PersonalizeActivity）的卡片材质接管。
 *
 * 页面文字卡 theme_personlize_card_view 是原生 miuix 白底 CardView，里面装着息屏/锁屏样式/
 * 壁纸/主题/图标/字体/息屏通知/指纹样式 8 行 ThemePreferenceView，白底卡面与自定义背景割裂。
 * 这里在页面创建/回到前台时按资源名定位这张卡，交给 [SettingsCardBackgroundHook] 的自绘卡片
 * 材质路径（柔光玻璃 → 磨砂 → 纯色 → 透明），与设置页的自绘卡片共用同一套材质与刷新时机。
 *
 * 只接管这一张卡：主题商店的其它页面（含它自己的设置页）不装任何分组绘制 hook。
 * 本进程禁挂全局 View hook，因此材质运行时以 standalone = false、groupHooks = false 安装，
 * 只要调色板与刷新监听。
 */
object ThemePersonalizeCardHook {
    private const val PACKAGE = "com.android.thememanager"
    private const val ACTIVITY =
        "com.android.thememanager.settings.personalize.activity.PersonalizeActivity"
    private const val CARD_ID_NAME = "theme_personlize_card_view"
    private const val RADIUS_DIMEN_NAME = "miuix_default_card_drawable_radius"
    // 卡片在 onCreate 内同步 inflate，正常首帧就能命中；留少量帧兜住异步重建。
    private const val MAX_WAIT_FRAMES = 30

    @Volatile
    private var cardIdCache = 0

    private val waiting = Collections.synchronizedMap(WeakHashMap<Activity, CardWaiter>())

    fun install(classLoader: ClassLoader) {
        val prefs = HookRuntime.remotePreferences(SETTINGS_APPEARANCE_PREFERENCES)
        if (prefs == null) {
            log("[HyperBackground] Personalize card: appearance preferences unavailable")
            return
        }
        val runtime = runCatching {
            SettingsCardBackgroundHook.install(
                HookRuntime.module(), classLoader, prefs,
                standalone = false, groupHooks = false,
            )
        }.onFailure {
            log("[HyperBackground] Personalize card: material runtime failed: $it")
        }
        if (runtime.isFailure) return
        try {
            hookMethod(ACTIVITY, classLoader, "onCreate", Bundle::class.java) {
                val activity = thisObject as? Activity ?: return@hookMethod
                schedule(activity)
            }
            hookMethod(ACTIVITY, classLoader, "onResume") {
                val activity = thisObject as? Activity ?: return@hookMethod
                schedule(activity)
            }
            log("[HyperBackground] Personalize page card material hook installed")
        } catch (error: Throwable) {
            log("[HyperBackground] Personalize card: activity hook failed: $error")
        }
    }

    private fun schedule(activity: Activity) {
        if (activity.isFinishing) return
        val decor = activity.window?.decorView ?: return
        val waiter = synchronized(waiting) {
            if (waiting.containsKey(activity)) return
            CardWaiter(activity, decor).also { waiting[activity] = it }
        }
        decor.post(waiter)
    }

    private fun findCard(activity: Activity): View? {
        val id = cardId(activity) ?: return null
        return activity.findViewById(id)
    }

    private fun cardId(context: Context): Int? {
        val cached = cardIdCache
        if (cached != 0) return cached
        val id = context.resources.getIdentifier(CARD_ID_NAME, "id", PACKAGE)
        if (id != 0) cardIdCache = id
        return id.takeIf { it != 0 }
    }

    private fun cardRadius(context: Context, card: View): Float {
        val fromCard = runCatching {
            card.javaClass.getMethod("getRadius").invoke(card) as Float
        }.getOrNull()
        if (fromCard != null && fromCard > 0f) return fromCard
        val id = context.resources.getIdentifier(RADIUS_DIMEN_NAME, "dimen", PACKAGE)
        return if (id != 0) context.resources.getDimension(id) else 0f
    }

    private fun applyMaterial(context: Context, card: View) {
        SettingsCardBackgroundHook.applyCustomCardMaterial(card, cardRadius(context, card))
    }

    private class CardWaiter(private val activity: Activity, private val decor: View) : Runnable {
        private var frames = 0

        override fun run() {
            val card = findCard(activity)
            if (card != null) {
                applyMaterial(activity, card)
                release()
                return
            }
            frames++
            if (frames >= MAX_WAIT_FRAMES || activity.isFinishing || activity.isDestroyed) {
                release()
                return
            }
            decor.postOnAnimation(this)
        }

        private fun release() {
            synchronized(waiting) { waiting.remove(activity) }
        }
    }
}