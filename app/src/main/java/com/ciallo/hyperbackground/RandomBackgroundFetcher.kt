package com.ciallo.hyperbackground

import android.content.Context
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * 随机背景下载器：从图片 API 拉取一张随机图并落盘为指定槽位的 random 背景。
 *
 * 全程在模块进程内完成网络请求，被 hook 的系统进程不直接联网——它们只通过 libxposed
 * remote 读取已落盘的 random 文件，性能与手动选图一致。下载失败时不触碰任何已有文件。
 */
object RandomBackgroundFetcher {

    // 与一言默认接口同源（uapis.cn），随机图片接口返回图片二进制流。
    const val DEFAULT_API = "https://uapis.cn/api/v1/random/image"

    private const val MAX_BYTES = 200L * 1024L * 1024L
    private const val CONNECT_TIMEOUT = 15000
    private const val READ_TIMEOUT = 30000

    /** 构建请求 URL：category 作为 query 参数拼接，空值不传（全随机）。 */
    fun buildUrl(base: String, category: String): String {
        val trimmedBase = base.trim()
        val cat = category.trim()
        if (cat.isEmpty()) return trimmedBase
        val params = mutableListOf("category=" + URLEncoder.encode(cat, "UTF-8"))
        // acg 类别默认混合 pc（电脑横图）/ mb（手机竖图）。本模块用于手机背景，
        // 强制 type=mb，避免拉到电脑比例的横图。
        if (cat == "acg") params.add("type=mb")
        return "$trimmedBase?${params.joinToString("&")}"
    }

    /**
     * 阻塞式拉取一张随机图并写入 [ConfigManager] 的 random 槽位。
     * 供开机广播 / 手动按钮的后台线程调用。成功返回 null，失败返回错误信息。
     */
    fun fetchForSlotBlocking(context: Context, slot: String): String? {
        val config = ConfigManager.get(context)
        val api = config.getString(BackgroundContract.UI_RANDOM_BG_API, DEFAULT_API) ?: DEFAULT_API
        val category = config.getString(BackgroundContract.UI_RANDOM_BG_CATEGORY, "") ?: ""
        val temp = File(context.cacheDir, "random_bg_${slot}.tmp")
        try {
            val (file, mime) = download(buildUrl(api, category), temp)
            config.importRandomBackground(slot, file, mime)
            return null
        } catch (e: Throwable) {
            return e.message ?: e.javaClass.simpleName
        } finally {
            temp.delete()
        }
    }

    /** 异步拉取；[onResult] 在调用线程（后台线程）回调，UI 侧需自行切回主线程。 */
    fun fetchForSlot(context: Context, slot: String, onResult: (error: String?) -> Unit) {
        Thread({
            onResult(fetchForSlotBlocking(context, slot))
        }, "RandomBg-$slot").start()
    }

    /** 下载图片到 [target]，返回文件与响应 Content-Type（推断的 mime）。 */
    private fun download(urlString: String, target: File): Pair<File, String> {
        val connection = URL(urlString).openConnection() as HttpURLConnection
        connection.connectTimeout = CONNECT_TIMEOUT
        connection.readTimeout = READ_TIMEOUT
        connection.instanceFollowRedirects = true
        connection.setRequestProperty("User-Agent", "HyperBackground")
        try {
            val code = connection.responseCode
            require(code in 200..299) { "HTTP $code" }
            val contentType = connection.contentType.orEmpty()
            // uapis 返回图片二进制流；部分图床不返回 Content-Type 或给 octet-stream，均放行，
            // 但明确返回 json/text/html 等非图片类型时拒绝，避免把错误页当图存下。
            val lower = contentType.lowercase()
            val looksLikeImage = lower.startsWith("image/") ||
                lower.contains("octet-stream") ||
                lower.isEmpty()
            require(looksLikeImage) { "Not an image: $contentType" }
            FileOutputStream(target).use { output ->
                connection.inputStream.use { input ->
                    val buffer = ByteArray(64 * 1024)
                    var total = 0L
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        total += count
                        require(total <= MAX_BYTES) { "Media file is too large" }
                        output.write(buffer, 0, count)
                    }
                    output.fd.sync()
                }
            }
            require(target.length() > 0) { "Empty response" }
            val mime = when {
                lower.startsWith("image/") -> lower.substringBefore(";").trim()
                else -> "image/jpeg"
            }
            return target to mime
        } finally {
            connection.disconnect()
        }
    }
}
