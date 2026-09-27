package com.ciallo.hyperbackground

import android.app.Activity
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import java.util.Collections
import java.util.WeakHashMap

/** Runtime bridge used by hooked pages for the "rotate when a page opens" mode. */
object PageRandomRefreshHook {
    private const val REQUEST_DEBOUNCE_MS = 1200L
    private const val REQUEST_DELAY_MS = 350L

    private data class RequestStamp(val slot: String, val uptime: Long)

    private val requestStamps: MutableMap<Any, RequestStamp> =
        Collections.synchronizedMap(WeakHashMap())

    fun requestHome(activity: Activity) =
        request(activity, activity, BackgroundContract.HOME)

    fun requestDevice(activity: Activity, fragment: Any) =
        request(activity, fragment, BackgroundContract.DEVICE)

    fun requestContacts(activity: Activity) =
        request(activity, activity, BackgroundContract.CONTACTS)

    fun requestMmsHome(activity: Activity) =
        request(activity, activity, BackgroundContract.MMS)

    fun requestMmsChat(activity: Activity) {
        if (!pageModeEnabled()) return
        val selectedSlots = runCatching {
            HookRuntime.preferences().getStringSet(
                BackgroundContract.UI_RANDOM_BG_SLOTS,
                emptySet(),
            )
        }.getOrNull().orEmpty()
        val displayedSlot = if (
            BackgroundContract.MMS_CHAT in selectedSlots ||
            BackgroundContract.query(activity, BackgroundContract.MMS_CHAT).exists
        ) {
            BackgroundContract.MMS_CHAT
        } else {
            BackgroundContract.MMS
        }
        request(activity, activity, displayedSlot)
    }

    fun requestGlobal(activity: Activity) {
        if (activity.packageName == BackgroundContract.PACKAGE_CONTACTS ||
            activity.packageName == BackgroundContract.PACKAGE_MMS ||
            activity.javaClass.name == "com.android.settings.MiuiSettings"
        ) return
        request(activity, activity, BackgroundContract.GLOBAL) {
            BackgroundApplier.hasGlobalBackground(activity) ||
                BackgroundApplier.usesGlobalBackground(activity)
        }
    }

    private fun request(
        context: Activity,
        target: Any,
        slot: String,
        eligibility: () -> Boolean = { true },
    ) {
        if (!canRequest(slot)) return
        val now = SystemClock.uptimeMillis()
        synchronized(requestStamps) {
            val previous = requestStamps[target]
            if (previous?.slot == slot && now - previous.uptime < REQUEST_DEBOUNCE_MS) return
            requestStamps[target] = RequestStamp(slot, now)
        }
        val task = Runnable {
            if (canRequest(slot) && eligibility()) sendRequest(context, slot)
        }
        val decor = runCatching { context.window.decorView }.getOrNull()
        if (decor == null || !decor.postDelayed(task, REQUEST_DELAY_MS)) task.run()
    }

    private fun pageModeEnabled(): Boolean {
        val prefs = runCatching { HookRuntime.preferences() }.getOrNull() ?: return false
        return prefs.getBoolean(BackgroundContract.UI_RANDOM_BG_ENABLED, false) &&
            prefs.getInt(
                BackgroundContract.UI_RANDOM_BG_MODE,
                BackgroundContract.RANDOM_BG_MODE_MANUAL,
            ) == BackgroundContract.RANDOM_BG_MODE_PAGE
    }

    private fun canRequest(slot: String): Boolean {
        val prefs = runCatching { HookRuntime.preferences() }.getOrNull() ?: return false
        if (!prefs.getBoolean(BackgroundContract.UI_RANDOM_BG_ENABLED, false)) return false
        if (prefs.getInt(
                BackgroundContract.UI_RANDOM_BG_MODE,
                BackgroundContract.RANDOM_BG_MODE_MANUAL,
            ) != BackgroundContract.RANDOM_BG_MODE_PAGE
        ) return false
        val enabledSlots = prefs.getStringSet(BackgroundContract.UI_RANDOM_BG_SLOTS, emptySet())
            ?: emptySet()
        val pinnedSlots = prefs.getStringSet(BackgroundContract.UI_RANDOM_BG_PINNED, emptySet())
            ?: emptySet()
        return slot in enabledSlots && slot !in pinnedSlots
    }

    private fun sendRequest(context: Context, slot: String) {
        val prefs = runCatching { HookRuntime.preferences() }.getOrNull() ?: return
        val token = prefs.getString(BackgroundContract.UI_RANDOM_BG_PAGE_TOKEN, null).orEmpty()
        if (token.isBlank()) return
        val appContext = context.applicationContext
        runCatching {
            appContext.sendBroadcast(
                Intent(BackgroundContract.ACTION_RANDOM_BG_PAGE_REQUEST)
                    .setComponent(ComponentName(BuildConfig.APPLICATION_ID, PageRandomReceiver::class.java.name))
                    .putExtra(BackgroundContract.EXTRA_RANDOM_BG_SLOT, slot)
                    .putExtra(BackgroundContract.EXTRA_RANDOM_BG_TOKEN, token),
            )
        }.onFailure { HookRuntime.log("[HyperBackground] Page random request failed: $it") }
    }
}
