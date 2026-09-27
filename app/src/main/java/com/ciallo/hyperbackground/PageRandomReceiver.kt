package com.ciallo.hyperbackground

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.ciallo.hyperbackground.util.ConfigManager
import io.github.libxposed.service.XposedService
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Receives a page-open rotation request from a hooked process, updates the selected random
 * background in the module process, and leaves the currently visible page untouched. The next
 * page using the same slot naturally reads the newly prepared image.
 */
class PageRandomReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != BackgroundContract.ACTION_RANDOM_BG_PAGE_REQUEST) return
        val config = ConfigManager.get(context)
        val token = intent.getStringExtra(BackgroundContract.EXTRA_RANDOM_BG_TOKEN).orEmpty()
        val expectedToken = config.getString(BackgroundContract.UI_RANDOM_BG_PAGE_TOKEN, null)
        if (token.isBlank() || token != expectedToken) return
        if (!config.getBoolean(BackgroundContract.UI_RANDOM_BG_ENABLED, false)) return
        if (config.getInt(
                BackgroundContract.UI_RANDOM_BG_MODE,
                BackgroundContract.RANDOM_BG_MODE_MANUAL,
            ) != BackgroundContract.RANDOM_BG_MODE_PAGE
        ) return

        val slot = intent.getStringExtra(BackgroundContract.EXTRA_RANDOM_BG_SLOT).orEmpty()
        if (slot !in PAGE_SLOTS || slot !in config.refreshableRandomSlots()) return

        val shouldStart = synchronized(REQUEST_LOCK) {
            IN_FLIGHT.add(slot)
        }
        if (!shouldStart) return

        val pending = goAsync()
        val appContext = context.applicationContext
        Thread({
            try {
                runCatching {
                    RandomBackgroundFetcher.fetchForSlotBlocking(appContext, slot) == null &&
                        syncToHookStorage(appContext, slot)
                }
            } finally {
                synchronized(REQUEST_LOCK) {
                    IN_FLIGHT.remove(slot)
                }
                pending.finish()
            }
        }, "RandomBg-Page-$slot").start()
    }

    private fun syncToHookStorage(context: Context, slot: String): Boolean {
        var service: XposedService? = HyperBackgroundApp.xposedService
        if (service == null) {
            val latch = CountDownLatch(1)
            val listener: (XposedService?) -> Unit = { value ->
                if (value != null) {
                    service = value
                    latch.countDown()
                }
            }
            HyperBackgroundApp.addServiceListener(listener)
            try {
                latch.await(SERVICE_WAIT_SECONDS, TimeUnit.SECONDS)
            } finally {
                HyperBackgroundApp.removeServiceListener(listener)
            }
        }
        val connected = service ?: return false
        return runCatching {
            ConfigManager.get(context).syncRandomBackgroundToRemote(slot, connected)
            true
        }.getOrDefault(false)
    }

    private companion object {
        const val SERVICE_WAIT_SECONDS = 5L
        val PAGE_SLOTS = setOf(
            BackgroundContract.HOME,
            BackgroundContract.DEVICE,
            BackgroundContract.GLOBAL,
            BackgroundContract.CONTACTS,
            BackgroundContract.MMS,
            BackgroundContract.MMS_CHAT,
        )
        val REQUEST_LOCK = Any()
        val IN_FLIGHT = mutableSetOf<String>()
    }
}
