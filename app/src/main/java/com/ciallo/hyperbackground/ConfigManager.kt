package com.ciallo.hyperbackground

import android.content.Context
import android.content.SharedPreferences
import android.net.Uri
import io.github.libxposed.service.XposedService
import java.io.File
import java.io.FileOutputStream

/** Central local storage that mirrors hook-facing state through libxposed. */
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

    fun saveBackground(slot: String, uri: Uri, mime: String) {
        copyMedia(backgroundFile(slot), uri)
        syncMedia(BackgroundContract.remoteMediaName(slot), backgroundFile(slot))
        val file = backgroundFile(slot)
        edit()
            .putString(BackgroundContract.MIME_PREFIX + slot, mime)
            .putLong(BackgroundContract.SIZE_PREFIX + slot, file.length())
            .putLong(BackgroundContract.MODIFIED_PREFIX + slot, file.lastModified())
            .apply()
    }

    fun saveUiBackground(uri: Uri, mime: String) {
        copyMedia(uiBackgroundFile, uri)
        edit().putString(BackgroundContract.UI_BG_MIME, mime).apply()
    }

    fun clearBackground(slot: String): Boolean {
        val file = backgroundFile(slot)
        if (file.exists() && !file.delete()) return false
        syncMedia(BackgroundContract.remoteMediaName(slot), null)
        edit()
            .remove(BackgroundContract.MIME_PREFIX + slot)
            .remove(BackgroundContract.SIZE_PREFIX + slot)
            .remove(BackgroundContract.MODIFIED_PREFIX + slot)
            .apply()
        return true
    }

    fun clearUiBackground(): Boolean {
        if (uiBackgroundFile.exists() && !uiBackgroundFile.delete()) return false
        edit().remove(BackgroundContract.UI_BG_MIME).apply()
        return true
    }

    fun syncToRemote(service: XposedService? = HyperBackgroundApp.xposedService) {
        service ?: return
        val metadata = preferences.edit()
        listOf(BackgroundContract.HOME, BackgroundContract.DEVICE, BackgroundContract.GLOBAL, BackgroundContract.CONTACTS, BackgroundContract.CONTACTS_DIALPAD).forEach { slot ->
            val file = backgroundFile(slot)
            if (file.isFile) {
                metadata.putLong(BackgroundContract.SIZE_PREFIX + slot, file.length())
                metadata.putLong(BackgroundContract.MODIFIED_PREFIX + slot, file.lastModified())
            } else {
                metadata.remove(BackgroundContract.SIZE_PREFIX + slot)
                metadata.remove(BackgroundContract.MODIFIED_PREFIX + slot)
            }
        }
        metadata.commit()
        copyPreferences(preferences, service.getRemotePreferences(BackgroundContract.PREFS))
        listOf(BackgroundContract.HOME, BackgroundContract.DEVICE, BackgroundContract.GLOBAL, BackgroundContract.CONTACTS, BackgroundContract.CONTACTS_DIALPAD).forEach { slot ->
            syncMedia(BackgroundContract.remoteMediaName(slot), backgroundFile(slot).takeIf(File::isFile), service)
        }
    }

    private fun copyMedia(target: File, uri: Uri) {
        target.parentFile?.mkdirs()
        val temp = File(target.absolutePath + ".tmp")
        var total = 0L
        context.contentResolver.openInputStream(uri).use { input ->
            requireNotNull(input) { "Unable to open media: $uri" }
            FileOutputStream(temp).use { output ->
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    total += count
                    require(total <= MAX_MEDIA_BYTES) { "Media file is too large" }
                    output.write(buffer, 0, count)
                }
                output.fd.sync()
            }
        }
        if (target.exists() && !target.delete()) error("Cannot replace media")
        if (!temp.renameTo(target)) error("Cannot finish media replacement")
        target.setLastModified(System.currentTimeMillis())
    }

    private fun syncMedia(name: String, source: File?, service: XposedService? = HyperBackgroundApp.xposedService) {
        service ?: return
        if (source == null || !source.isFile) {
            service.deleteRemoteFile(name)
            return
        }
        service.openRemoteFile(name).use { descriptor ->
            FileOutputStream(descriptor.fileDescriptor).use { output ->
                output.channel.truncate(0)
                output.channel.position(0)
                source.inputStream().use { it.copyTo(output) }
                output.fd.sync()
            }
        }
    }

    override fun getAll(): MutableMap<String, *> = preferences.all
    override fun getString(key: String?, defValue: String?): String? = preferences.getString(key, defValue)
    override fun getStringSet(key: String?, defValues: MutableSet<String>?): MutableSet<String>? = preferences.getStringSet(key, defValues)
    override fun getInt(key: String?, defValue: Int): Int = preferences.getInt(key, defValue)
    override fun getLong(key: String?, defValue: Long): Long = preferences.getLong(key, defValue)
    override fun getFloat(key: String?, defValue: Float): Float = preferences.getFloat(key, defValue)
    override fun getBoolean(key: String?, defValue: Boolean): Boolean = preferences.getBoolean(key, defValue)
    override fun contains(key: String?): Boolean = preferences.contains(key)
    override fun edit(): SharedPreferences.Editor = MirroringEditor(preferences.edit())
    override fun registerOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) =
        preferences.registerOnSharedPreferenceChangeListener(listener)
    override fun unregisterOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) =
        preferences.unregisterOnSharedPreferenceChangeListener(listener)

    companion object {
        private const val MAX_MEDIA_BYTES = 200L * 1024L * 1024L
        @Volatile private var instance: ConfigManager? = null

        @JvmStatic
        fun get(context: Context): ConfigManager = instance ?: synchronized(this) {
            instance ?: ConfigManager(context.applicationContext).also { instance = it }
        }
    }

    private inner class MirroringEditor(
        private val local: SharedPreferences.Editor,
    ) : SharedPreferences.Editor {
        override fun putString(key: String?, value: String?) = apply { local.putString(key, value) }
        override fun putStringSet(key: String?, values: MutableSet<String>?) = apply { local.putStringSet(key, values) }
        override fun putInt(key: String?, value: Int) = apply { local.putInt(key, value) }
        override fun putLong(key: String?, value: Long) = apply { local.putLong(key, value) }
        override fun putFloat(key: String?, value: Float) = apply { local.putFloat(key, value) }
        override fun putBoolean(key: String?, value: Boolean) = apply { local.putBoolean(key, value) }
        override fun remove(key: String?) = apply { local.remove(key) }
        override fun clear() = apply { local.clear() }

        override fun commit(): Boolean {
            val committed = local.commit()
            if (committed) mirrorPreferences()
            return committed
        }

        override fun apply() {
            if (local.commit()) mirrorPreferences()
        }
    }

    private fun mirrorPreferences() {
        HyperBackgroundApp.xposedService?.let { service ->
            copyPreferences(preferences, service.getRemotePreferences(BackgroundContract.PREFS))
        }
    }

    private fun copyPreferences(source: SharedPreferences, target: SharedPreferences) {
        val editor = target.edit().clear()
        source.all.forEach { (key, value) ->
            when (value) {
                is String -> editor.putString(key, value)
                is Set<*> -> editor.putStringSet(key, value.filterIsInstance<String>().toMutableSet())
                is Int -> editor.putInt(key, value)
                is Long -> editor.putLong(key, value)
                is Float -> editor.putFloat(key, value)
                is Boolean -> editor.putBoolean(key, value)
            }
        }
        editor.commit()
    }
}
