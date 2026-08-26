package com.ciallo.hyperbackground

import android.content.Context
import android.content.SharedPreferences
import java.io.File

/** Central configuration and media-storage boundary shared by the UI and provider. */
class ConfigManager private constructor(private val context: Context) : SharedPreferences {
    private val preferences = context.getSharedPreferences(BackgroundContract.PREFS, Context.MODE_PRIVATE)

    val backgroundsDir: File
        get() = File(context.filesDir, "backgrounds").apply { mkdirs() }

    val uiBackgroundFile: File
        get() = File(context.filesDir, "ui_background.bin")

    fun backgroundFile(slot: String): File = File(backgroundsDir, "$slot.bin")

    fun backgroundMime(slot: String): String =
        getString(BackgroundContract.MIME_PREFIX + slot, "application/octet-stream")
            ?: "application/octet-stream"

    fun backgroundOpacity(slot: String): Int =
        getInt(BackgroundContract.OPACITY_PREFIX + slot, 100).coerceIn(0, 100)

    fun backgroundBlurEnabled(slot: String): Boolean =
        getBoolean(BackgroundContract.BLUR_ENABLED_PREFIX + slot, false)

    fun backgroundBlurRadius(slot: String): Int =
        getInt(BackgroundContract.BLUR_RADIUS_PREFIX + slot, 20).coerceIn(0, 80)

    fun setBackgroundMime(slot: String, mime: String) {
        edit().putString(BackgroundContract.MIME_PREFIX + slot, mime).apply()
    }

    fun clearBackground(slot: String): Boolean {
        val file = backgroundFile(slot)
        if (file.exists() && !file.delete()) return false
        edit().remove(BackgroundContract.MIME_PREFIX + slot).apply()
        return true
    }

    override fun getAll(): MutableMap<String, *> = preferences.all
    override fun getString(key: String?, defValue: String?): String? = preferences.getString(key, defValue)
    override fun getStringSet(key: String?, defValues: MutableSet<String>?): MutableSet<String>? = preferences.getStringSet(key, defValues)
    override fun getInt(key: String?, defValue: Int): Int = preferences.getInt(key, defValue)
    override fun getLong(key: String?, defValue: Long): Long = preferences.getLong(key, defValue)
    override fun getFloat(key: String?, defValue: Float): Float = preferences.getFloat(key, defValue)
    override fun getBoolean(key: String?, defValue: Boolean): Boolean = preferences.getBoolean(key, defValue)
    override fun contains(key: String?): Boolean = preferences.contains(key)
    override fun edit(): SharedPreferences.Editor = preferences.edit()
    override fun registerOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) =
        preferences.registerOnSharedPreferenceChangeListener(listener)
    override fun unregisterOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) =
        preferences.unregisterOnSharedPreferenceChangeListener(listener)

    companion object {
        @Volatile private var instance: ConfigManager? = null

        @JvmStatic
        fun get(context: Context): ConfigManager = instance ?: synchronized(this) {
            instance ?: ConfigManager(context.applicationContext).also { instance = it }
        }
    }
}
