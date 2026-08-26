package com.ciallo.hyperbackground.ui.pages

import android.content.Context
import com.ciallo.hyperbackground.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/** 更新检查与 CHANGELOG 解析的公共逻辑，供更新日志页与全局更新弹窗复用。 */
internal const val LATEST_RELEASE_API =
    "https://api.github.com/repos/Solomonstery/HyperBackground/releases/latest"
internal const val RELEASES_URL = "https://github.com/Solomonstery/HyperBackground/releases"

/** 一个版本章节：版本标题 + 该版本下的条目列表。 */
internal data class ReleaseNotesEntry(val version: String, val notes: List<String>)

/** 归一化版本号用于匹配：去 v 前缀、去首尾空白、转小写。 */
internal fun normalizeVersion(v: String) = v.trim().removePrefix("v").removePrefix("V").trim().lowercase()

/** 当前构建是否为预览版（test/alpha/beta/rc/dev）。 */
internal fun isPreviewVersion(v: String) = v.contains(Regex("(?i)(test|alpha|beta|rc|dev)"))

/** 从全部章节里按版本号取一节；找不到返回 null（宽松匹配：标题包含该版本号即可）。 */
internal fun findEntry(all: List<ReleaseNotesEntry>, version: String): ReleaseNotesEntry? {
    val target = normalizeVersion(version)
    return all.firstOrNull { normalizeVersion(it.version) == target }
        ?: all.firstOrNull { normalizeVersion(it.version).contains(target) || target.contains(normalizeVersion(it.version)) }
}

/**
 * 解析打包进 assets 的 CHANGELOG.md，返回文件中出现的全部版本章节（按文件顺序，即从新到旧）。
 * 兼容 `-`/`*`/`+` 列表符号，跳过代码块围栏。
 */
internal fun loadAllReleaseNotes(context: Context): List<ReleaseNotesEntry> {
    val text = runCatching {
        context.assets.open("CHANGELOG.md").bufferedReader(Charsets.UTF_8).use { it.readText() }
    }.getOrNull() ?: return emptyList()

    val headingRegex = Regex("""^##\s+(.+)$""")
    val bulletRegex = Regex("""^[-*+]\s+(.+)$""")

    val sections = mutableListOf<ReleaseNotesEntry>()
    var currentVersion: String? = null
    val currentNotes = mutableListOf<String>()
    var inFence = false
    fun flush() {
        currentVersion?.let { sections.add(ReleaseNotesEntry(it, currentNotes.toList())) }
        currentNotes.clear()
    }
    for (raw in text.lineSequence()) {
        val line = raw.trim()
        if (line.startsWith("```") || line.startsWith("~~~")) {
            inFence = !inFence
            continue
        }
        if (inFence) continue
        val heading = headingRegex.matchEntire(line)
        if (heading != null) {
            flush()
            currentVersion = heading.groupValues[1].trim()
            continue
        }
        val bullet = bulletRegex.matchEntire(line)
        if (bullet != null && currentVersion != null) {
            val note = bullet.groupValues[1].trim()
            if (note.isNotEmpty()) currentNotes.add(note)
        }
    }
    flush()
    return sections
}

/** 请求 GitHub releases/latest，返回最新正式版版本号（去掉 v 前缀）。 */
internal suspend fun fetchLatestStableVersion(): String = withContext(Dispatchers.IO) {
    val connection = (URL(LATEST_RELEASE_API).openConnection() as HttpURLConnection).apply {
        connectTimeout = 7000
        readTimeout = 7000
        requestMethod = "GET"
        setRequestProperty("Accept", "application/vnd.github+json")
        setRequestProperty("User-Agent", "HyperBG/${BuildConfig.VERSION_NAME}")
    }
    try {
        val code = connection.responseCode
        if (code !in 200..299) error("GitHub HTTP $code")
        val body = connection.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
        val tag = JSONObject(body).optString("tag_name").trim().removePrefix("v")
        if (tag.isBlank()) error("empty tag")
        tag
    } finally {
        connection.disconnect()
    }
}

/** 比较两个语义化版本号，忽略 `v` 前缀与 `-` 之后的预发布后缀。 */
internal fun compareVersions(a: String, b: String): Int {
    fun parts(v: String) = v.removePrefix("v").substringBefore('-').split('.').map { it.toIntOrNull() ?: 0 }
    val ap = parts(a)
    val bp = parts(b)
    for (i in 0 until maxOf(ap.size, bp.size)) {
        val av = ap.getOrElse(i) { 0 }
        val bv = bp.getOrElse(i) { 0 }
        if (av != bv) return av.compareTo(bv)
    }
    return 0
}
