package com.ciallo.hyperbackground

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.LruCache
import com.ciallo.hyperbackground.util.ConfigManager
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import kotlin.random.Random

/** App-private image pool used by the offline random-background source. */
object LocalRandomBackgroundStore {
    data class ImportResult(
        val imported: Int,
        val duplicates: Int,
        val failed: Int,
    )

    internal data class ImageEntry(
        val id: String,
        val size: Long,
        val lastModified: Long,
        internal val file: File,
    )

    data class DeleteResult(
        val deleted: Int,
        val failed: Int,
    )

    private const val DIRECTORY = "random_background_pool"
    private const val MAX_BYTES = 200L * 1024L * 1024L
    private const val THUMBNAIL_CACHE_BYTES = 8 * 1024 * 1024
    private val thumbnailCache = object : LruCache<String, Bitmap>(THUMBNAIL_CACHE_BYTES) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.allocationByteCount
    }

    private fun directory(context: Context): File =
        File(context.filesDir, DIRECTORY).apply { mkdirs() }

    fun imageCount(context: Context): Int = images(context).size

    @Synchronized
    internal fun listImages(context: Context): List<ImageEntry> = images(context).map { file ->
        ImageEntry(file.name, file.length(), file.lastModified(), file)
    }

    /** Decodes only a sampled thumbnail and keeps a small process-local bitmap cache. */
    @Synchronized
    internal fun loadThumbnail(entry: ImageEntry, targetPixels: Int): Bitmap? {
        val edge = targetPixels.coerceIn(64, 512)
        val key = "${entry.id}:${entry.lastModified}:$edge"
        thumbnailCache.get(key)?.let { return it }
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(entry.file.absolutePath, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= edge) {
            sample *= 2
        }
        val bitmap = BitmapFactory.decodeFile(
            entry.file.absolutePath,
            BitmapFactory.Options().apply {
                inSampleSize = sample
                inPreferredConfig = Bitmap.Config.ARGB_8888
            },
        ) ?: return null
        thumbnailCache.put(key, bitmap)
        return bitmap
    }

    @Synchronized
    fun importImages(context: Context, uris: List<Uri>): ImportResult {
        var imported = 0
        var duplicates = 0
        var failed = 0
        val resolver = context.contentResolver
        val pool = directory(context)

        uris.forEachIndexed { index, uri ->
            val temp = File(pool, ".import-${System.nanoTime()}-$index.tmp")
            runCatching {
                val digest = MessageDigest.getInstance("SHA-256")
                var total = 0L
                resolver.openInputStream(uri).use { input ->
                    requireNotNull(input) { "Unable to open image" }
                    FileOutputStream(temp).use { output ->
                        val buffer = ByteArray(64 * 1024)
                        while (true) {
                            val count = input.read(buffer)
                            if (count < 0) break
                            total += count
                            require(total <= MAX_BYTES) { "Image is too large" }
                            digest.update(buffer, 0, count)
                            output.write(buffer, 0, count)
                        }
                        output.fd.sync()
                    }
                }
                require(total > 0L) { "Empty image" }
                val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeFile(temp.absolutePath, options)
                require(options.outWidth > 0 && options.outHeight > 0) { "Unsupported image" }
                val declaredMime = resolver.getType(uri)?.substringBefore(';')?.trim()?.lowercase()
                val mime = options.outMimeType?.lowercase()
                    ?: declaredMime?.takeIf { it.startsWith("image/") }
                    ?: "image/jpeg"
                val hash = digest.digest().joinToString("") { "%02x".format(it) }
                val target = File(pool, hash + extensionForMime(mime))
                val existing = pool.listFiles().orEmpty().any {
                    it.isFile && it.name.substringBefore('.') == hash
                }
                if (existing) {
                    duplicates++
                } else {
                    require(temp.renameTo(target)) { "Cannot finish image import" }
                    target.setLastModified(System.currentTimeMillis())
                    imported++
                }
            }.onFailure {
                failed++
            }
            if (temp.exists()) temp.delete()
        }
        return ImportResult(imported, duplicates, failed)
    }

    /** Deletes source images only. Already selected slot snapshots remain active. */
    @Synchronized
    fun clear(context: Context): Boolean {
        val pool = directory(context)
        var success = true
        pool.listFiles().orEmpty().forEach { file ->
            if (file.exists() && !file.delete()) success = false
        }
        thumbnailCache.evictAll()
        return success
    }

    /** Deletes selected source images only; already copied slot snapshots stay active. */
    @Synchronized
    fun deleteImages(context: Context, ids: Set<String>): DeleteResult {
        if (ids.isEmpty()) return DeleteResult(0, 0)
        var deleted = 0
        var failed = 0
        val deletedIds = mutableSetOf<String>()
        images(context).filter { it.name in ids }.forEach { file ->
            if (file.delete()) {
                deleted++
                deletedIds += file.name
            } else {
                failed++
            }
        }
        if (deletedIds.isNotEmpty()) {
            thumbnailCache.snapshot().keys
                .filter { key -> key.substringBefore(':') in deletedIds }
                .forEach(thumbnailCache::remove)
            val config = ConfigManager.get(context)
            val editor = config.edit()
            listOf(
                BackgroundContract.HOME,
                BackgroundContract.DEVICE,
                BackgroundContract.GLOBAL,
                BackgroundContract.CONTACTS,
                BackgroundContract.MMS,
                BackgroundContract.MMS_CHAT,
                BackgroundContract.RANDOM_SLOT_UI,
            ).forEach { slot ->
                val key = BackgroundContract.UI_RANDOM_BG_LOCAL_LAST_PREFIX + slot
                if (config.getString(key, null) in deletedIds) editor.remove(key)
            }
            editor.apply()
        }
        return DeleteResult(deleted, failed)
    }

    /** Selects a pool image and copies it through the existing random-slot pipeline. */
    @Synchronized
    fun applyForSlotBlocking(context: Context, slot: String): String? {
        val available = images(context)
        if (available.isEmpty()) return "Local image library is empty"
        val config = ConfigManager.get(context)
        val lastKey = BackgroundContract.UI_RANDOM_BG_LOCAL_LAST_PREFIX + slot
        val previous = config.getString(lastKey, null)
        val candidates = if (available.size > 1) available.filter { it.name != previous } else available
        val selected = candidates[Random.nextInt(candidates.size)]
        return runCatching {
            config.importRandomBackground(slot, selected, mimeForFile(selected))
            config.edit().putString(lastKey, selected.name).apply()
        }.exceptionOrNull()?.let { it.message ?: it.javaClass.simpleName }
    }

    private fun images(context: Context): List<File> =
        directory(context).listFiles()
            .orEmpty()
            .filter { it.isFile && !it.name.startsWith('.') }
            .sortedBy { it.name }

    private fun extensionForMime(mime: String): String = when (mime) {
        "image/png" -> ".png"
        "image/webp" -> ".webp"
        "image/gif" -> ".gif"
        "image/heif", "image/heic" -> ".heic"
        "image/avif" -> ".avif"
        else -> ".jpg"
    }

    private fun mimeForFile(file: File): String = when (file.extension.lowercase()) {
        "png" -> "image/png"
        "webp" -> "image/webp"
        "gif" -> "image/gif"
        "heif", "heic" -> "image/heic"
        "avif" -> "image/avif"
        else -> "image/jpeg"
    }
}
