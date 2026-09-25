package com.mediafetch.app.engine

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder
import java.util.concurrent.TimeUnit
import java.util.regex.Pattern

data class SearchResultItem(
    val id: String,
    val title: String,
    val author: String,
    val authorUrl: String = "",
    val durationFormatted: String = "",
    val durationSeconds: Long = 0L,
    val thumbnail: String,
    val directUrl: String = "",
    val platform: String,
    val mediaType: String = "video", // "video", "image", "audio", "gif"
    val downloadUrl: String = "",
    val pageUrl: String = "",
    val resolutionBadge: String = ""
) {
    fun toJson(): JSONObject {
        return JSONObject().apply {
            put("id", id)
            put("title", title)
            put("author", author)
            put("authorUrl", authorUrl)
            put("durationFormatted", durationFormatted)
            put("durationSeconds", durationSeconds)
            put("thumbnail", thumbnail)
            put("directUrl", directUrl)
            put("platform", platform)
            put("mediaType", mediaType)
            put("downloadUrl", downloadUrl)
            put("pageUrl", pageUrl)
            put("resolutionBadge", resolutionBadge)
        }
    }
}

object MediaSearchEngine {

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(12, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    /**
     * Get real search autocomplete suggestions from Google/YouTube
     */
    suspend fun getSearchSuggestions(query: String): List<String> = withContext(Dispatchers.IO) {
        val clean = query.trim()
        if (clean.isBlank()) return@withContext emptyList<String>()

        try {
            val url = "https://suggestqueries.google.com/complete/search?client=youtube&ds=yt&client=firefox&q=" + URLEncoder.encode(clean, "UTF-8")
            val request = Request.Builder()
                .url(url)
                .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64)")
                .build()
            val response = client.newCall(request).execute()
            if (response.isSuccessful) {
                val body = response.body?.string() ?: ""
                if (body.isNotBlank()) {
                    val jsonArray = JSONArray(body)
                    if (jsonArray.length() > 1) {
                        val suggestionsArray = jsonArray.optJSONArray(1)
                        if (suggestionsArray != null) {
                            val list = mutableListOf<String>()
                            for (i in 0 until suggestionsArray.length().coerceAtMost(8)) {
                                val s = suggestionsArray.optString(i, "")
                                if (s.isNotBlank() && !list.contains(s)) list.add(s)
                            }
                            return@withContext list
                        }
                    }
                }
            }
        } catch (e: Exception) {
            // Ignore
        }
        emptyList()
    }

    /**
     * Master Search Orchestrator across selected platforms & categories
     */
    suspend fun searchAll(query: String, platforms: List<String>, page: Int = 1): List<SearchResultItem> = withContext(Dispatchers.IO) {
        val clean = query.trim()
        if (clean.isBlank()) return@withContext emptyList<SearchResultItem>()

        val deferredList = mutableListOf<kotlinx.coroutines.Deferred<List<SearchResultItem>>>()

        val isAll = platforms.isEmpty() || platforms.contains("all")

        // 1. YouTube Videos & Shorts
        if (isAll || platforms.contains("youtube") || platforms.contains("video")) {
            deferredList.add(async { searchYouTube(clean, page) })
        }

        // 2. Wikimedia Commons 4K / HD Videos
        if (isAll || platforms.contains("wikimedia") || platforms.contains("wikimedia_video") || platforms.contains("video")) {
            deferredList.add(async { searchWikimediaVideos(clean, page) })
        }

        // 3. Openverse 700M+ High-Res Photos & Art
        if (isAll || platforms.contains("openverse") || platforms.contains("photo") || platforms.contains("image")) {
            deferredList.add(async { searchOpenverseImages(clean, page) })
        }

        // 4. Wikimedia Commons High-Res Art & Photography
        if (isAll || platforms.contains("wikimedia") || platforms.contains("wikimedia_photo") || platforms.contains("photo") || platforms.contains("image")) {
            deferredList.add(async { searchWikimediaPhotos(clean, page) })
        }

        // 5. Wallhaven 4K Ultra-HD Wallpapers
        if (isAll || platforms.contains("wallhaven") || platforms.contains("photo") || platforms.contains("wallpaper")) {
            deferredList.add(async { searchWallhaven(clean, page) })
        }

        val allResults = deferredList.awaitAll()
        val interleaved = mutableListOf<SearchResultItem>()

        val maxCount = allResults.maxOfOrNull { it.size } ?: 0
        for (i in 0 until maxCount) {
            for (providerResults in allResults) {
                if (i < providerResults.size) {
                    interleaved.add(providerResults[i])
                }
            }
        }

        return@withContext interleaved
    }

    /**
     * 1. Supercharged YouTube Search (Real YouTube results: long-form videos + viral shorts)
     */
    suspend fun searchYouTube(query: String, page: Int = 1): List<SearchResultItem> = withContext(Dispatchers.IO) {
        val clean = query.trim()
        if (clean.isBlank()) return@withContext emptyList<SearchResultItem>()

        val items = mutableListOf<SearchResultItem>()
        val seenIds = mutableSetOf<String>()

        try {
            val cleanQ = URLEncoder.encode(clean, "UTF-8")
            // Query both default search and video-filtered search in parallel
            val d1 = async { fetchYtInitialData("https://www.youtube.com/results?search_query=$cleanQ") }
            val d2 = async { fetchYtInitialData("https://www.youtube.com/results?search_query=$cleanQ&sp=EgIQAQ%253D%253D") }

            val json1 = d1.await()
            val json2 = d2.await()

            if (json2 != null) {
                extractYouTubeVideosFromData(json2, items, seenIds)
            }
            if (json1 != null) {
                extractYouTubeVideosFromData(json1, items, seenIds)
            }
        } catch (e: Exception) {
            // Fallback to InnerTube API
        }

        // Reliable fallback to InnerTube API if web results were empty
        if (items.isEmpty()) {
            try {
                val payload = JSONObject().apply {
                    put("context", JSONObject().apply {
                        put("client", JSONObject().apply {
                            put("clientName", "WEB")
                            put("clientVersion", "2.20241001.01.00")
                            put("hl", "en")
                            put("gl", "US")
                        })
                    })
                    put("query", clean)
                }
                val request = Request.Builder()
                    .url("https://www.youtube.com/youtubei/v1/search")
                    .post(payload.toString().toRequestBody("application/json; charset=utf-8".toMediaType()))
                    .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
                    .build()
                val response = client.newCall(request).execute()
                if (response.isSuccessful) {
                    val body = response.body?.string() ?: ""
                    if (body.isNotBlank()) {
                        extractYouTubeVideosFromData(JSONObject(body), items, seenIds)
                    }
                }
            } catch (_: Exception) {}
        }

        items
    }

    private fun fetchYtInitialData(url: String): JSONObject? {
        try {
            val request = Request.Builder()
                .url(url)
                .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36")
                .header("Accept-Language", "en-US,en;q=0.9")
                .header("Cookie", "CONSENT=YES+1")
                .build()

            val response = client.newCall(request).execute()
            if (response.isSuccessful) {
                val html = response.body?.string() ?: ""
                val m = Pattern.compile("(?:var\\s+)?ytInitialData\\s*=\\s*(\\{.+?\\});<").matcher(html)
                if (m.find()) {
                    val jsonStr = m.group(1) ?: return null
                    return JSONObject(jsonStr)
                }
                val m2 = Pattern.compile("ytInitialData\\s*=\\s*(\\{.+?\\});").matcher(html)
                if (m2.find()) {
                    val jsonStr = m2.group(1) ?: return null
                    return JSONObject(jsonStr)
                }
            }
        } catch (_: Exception) {}
        return null
    }

    private fun extractYouTubeVideosFromData(
        data: JSONObject,
        outList: MutableList<SearchResultItem>,
        seenIds: MutableSet<String>
    ) {
        fun addVideo(vid: String, title: String, author: String, durStr: String, thumb: String, isShort: Boolean) {
            if (vid.isBlank() || seenIds.contains(vid)) return
            seenIds.add(vid)
            val cleanTitle = title.replace(Regex("[\\r\\n\\t]+"), " ").trim()
            val durSec = if (isShort) 30L else parseDurationToSeconds(durStr)
            outList.add(
                SearchResultItem(
                    id = "yt_$vid",
                    title = cleanTitle.ifBlank { "YouTube Video" },
                    author = author.ifBlank { if (isShort) "YouTube Shorts" else "YouTube Creator" },
                    durationFormatted = if (isShort) "Shorts" else durStr.ifBlank { "HD Video" },
                    durationSeconds = durSec,
                    thumbnail = if (thumb.isNotBlank()) thumb else "https://i.ytimg.com/vi/$vid/hqdefault.jpg",
                    directUrl = "https://www.youtube.com/watch?v=$vid",
                    platform = "YouTube",
                    mediaType = "video",
                    pageUrl = "https://www.youtube.com/watch?v=$vid",
                    resolutionBadge = if (isShort) "Shorts HD" else "1080p / 4K"
                )
            )
        }

        fun traverse(obj: Any?) {
            when (obj) {
                is JSONObject -> {
                    if (obj.has("videoRenderer")) {
                        val vr = obj.optJSONObject("videoRenderer")
                        if (vr != null) {
                            val vid = vr.optString("videoId", "")
                            val title = vr.optJSONObject("title")?.optJSONArray("runs")?.optJSONObject(0)?.optString("text", "") ?: ""
                            val author = vr.optJSONObject("ownerText")?.optJSONArray("runs")?.optJSONObject(0)?.optString("text", "") ?: ""
                            val dur = vr.optJSONObject("lengthText")?.optString("simpleText", "") ?: ""
                            val thumbs = vr.optJSONObject("thumbnail")?.optJSONArray("thumbnails")
                            val thumb = thumbs?.optJSONObject(thumbs.length() - 1)?.optString("url", "") ?: ""
                            addVideo(vid, title, author, dur, thumb, false)
                        }
                    } else if (obj.has("shortsLockupViewModel")) {
                        val sl = obj.optJSONObject("shortsLockupViewModel")
                        if (sl != null) {
                            var vid = ""
                            val eid = sl.optString("entityId", "")
                            if (eid.contains("shorts-shelf-item-")) {
                                vid = eid.replace("shorts-shelf-item-", "")
                            }
                            if (vid.isBlank()) {
                                val url = sl.optJSONObject("onTap")
                                    ?.optJSONObject("innertubeCommand")
                                    ?.optJSONObject("commandMetadata")
                                    ?.optJSONObject("webCommandMetadata")
                                    ?.optString("url", "") ?: ""
                                val m = Pattern.compile("/shorts/([a-zA-Z0-9_-]+)").matcher(url)
                                if (m.find()) vid = m.group(1) ?: ""
                            }
                            var title = sl.optJSONObject("overlayMetadata")?.optJSONObject("primaryText")?.optString("content", "") ?: ""
                            if (title.isBlank()) {
                                title = sl.optString("accessibilityText", "")
                            }
                            if (title.contains(", ") && title.contains("views")) {
                                title = title.split(", ")[0]
                            }
                            val author = sl.optJSONObject("overlayMetadata")?.optJSONObject("secondaryText")?.optString("content", "YouTube Short") ?: "YouTube Short"
                            val thumbSources = sl.optJSONObject("thumbnailViewModel")
                                ?.optJSONObject("thumbnailViewModel")
                                ?.optJSONObject("image")
                                ?.optJSONArray("sources")
                            val thumb = thumbSources?.optJSONObject(0)?.optString("url", "") ?: ""
                            addVideo(vid, title, author, "Shorts", thumb, true)
                        }
                    } else if (obj.has("compactVideoRenderer")) {
                        val cvr = obj.optJSONObject("compactVideoRenderer")
                        if (cvr != null) {
                            val vid = cvr.optString("videoId", "")
                            val title = cvr.optJSONObject("title")?.optJSONArray("runs")?.optJSONObject(0)?.optString("text", "") ?: ""
                            val author = cvr.optJSONObject("shortBylineText")?.optJSONArray("runs")?.optJSONObject(0)?.optString("text", "") ?: ""
                            val dur = cvr.optJSONObject("lengthText")?.optString("simpleText", "") ?: ""
                            val thumbs = cvr.optJSONObject("thumbnail")?.optJSONArray("thumbnails")
                            val thumb = thumbs?.optJSONObject(thumbs.length() - 1)?.optString("url", "") ?: ""
                            addVideo(vid, title, author, dur, thumb, false)
                        }
                    }

                    val keys = obj.keys()
                    while (keys.hasNext()) {
                        traverse(obj.opt(keys.next()))
                    }
                }
                is JSONArray -> {
                    for (i in 0 until obj.length()) {
                        traverse(obj.opt(i))
                    }
                }
            }
        }

        traverse(data)
    }

    /**
     * 2. Wikimedia Commons 4K / HD Video Search (Real 4K Stock Clips with Direct URLs & Durations)
     */
    suspend fun searchWikimediaVideos(query: String, page: Int = 1): List<SearchResultItem> = withContext(Dispatchers.IO) {
        val items = mutableListOf<SearchResultItem>()
        try {
            val limit = 12
            val url = "https://commons.wikimedia.org/w/api.php?action=query&generator=search&gsrnamespace=6&gsrsearch=filetype:video%20" +
                    URLEncoder.encode(query, "UTF-8") + "&gsrlimit=$limit&prop=imageinfo&iiprop=url|size|mime|dimensions&iiurlwidth=480&format=json"

            val request = Request.Builder()
                .url(url)
                .header("User-Agent", "Mozilla/5.0 MediaFetch/1.0")
                .build()

            val response = client.newCall(request).execute()
            if (response.isSuccessful) {
                val body = response.body?.string() ?: ""
                val json = JSONObject(body)
                val pages = json.optJSONObject("query")?.optJSONObject("pages")
                if (pages != null) {
                    val keys = pages.keys()
                    while (keys.hasNext()) {
                        val pageId = keys.next()
                        val pObj = pages.optJSONObject(pageId) ?: continue
                        val imageInfoArr = pObj.optJSONArray("imageinfo") ?: continue
                        if (imageInfoArr.length() == 0) continue

                        val info = imageInfoArr.getJSONObject(0)
                        val rawUrl = info.optString("url", "")
                        if (rawUrl.isBlank()) continue

                        val titleRaw = pObj.optString("title", "Stock Video")
                            .replace(Regex("^File:", RegexOption.IGNORE_CASE), "")
                            .replace(Regex("\\.[^/.]+$"), "")
                            .replace(Regex("[_-]"), " ")
                            .trim()

                        val durationSeconds = info.optDouble("duration", 0.0).toLong()
                        val durationStr = if (durationSeconds > 0) formatSecondsToDuration(durationSeconds) else "HD Video"

                        val width = info.optInt("width", 1920)
                        val height = info.optInt("height", 1080)
                        val badge = if (width >= 3840 || height >= 2160) "4K UHD" else "${width}x${height}"

                        val thumb = info.optString("thumburl", "")

                        items.add(
                            SearchResultItem(
                                id = "wiki_v_$pageId",
                                title = titleRaw.capitalizeWords(),
                                author = "Wikimedia Commons",
                                durationFormatted = durationStr,
                                durationSeconds = durationSeconds,
                                thumbnail = thumb.ifBlank { "https://images.unsplash.com/photo-1579546929518-9e396f3cc809?w=400" },
                                directUrl = rawUrl,
                                platform = "Wikimedia 4K",
                                mediaType = "video",
                                downloadUrl = rawUrl,
                                pageUrl = info.optString("descriptionurl", rawUrl),
                                resolutionBadge = badge
                            )
                        )
                    }
                }
            }
        } catch (e: Exception) {
            // Ignore
        }
        items
    }

    /**
     * 7. Openverse 700M+ Creative Commons Photos & Art
     */
    suspend fun searchOpenverseImages(query: String, page: Int = 1): List<SearchResultItem> = withContext(Dispatchers.IO) {
        val items = mutableListOf<SearchResultItem>()
        try {
            val url = "https://api.openverse.org/v1/images/?q=" + URLEncoder.encode(query, "UTF-8") + "&page_size=12&page=$page"
            val request = Request.Builder()
                .url(url)
                .header("User-Agent", "Mozilla/5.0")
                .build()

            val response = client.newCall(request).execute()
            if (response.isSuccessful) {
                val body = response.body?.string() ?: ""
                val json = JSONObject(body)
                val results = json.optJSONArray("results")
                if (results != null) {
                    for (i in 0 until results.length()) {
                        val r = results.optJSONObject(i) ?: continue
                        val id = r.optString("id", "")
                        val title = r.optString("title", "Creative Photo").capitalizeWords()
                        val author = r.optString("creator", "Photographer")
                        val imgUrl = r.optString("url", "")
                        val thumb = r.optString("thumbnail", imgUrl)

                        val width = r.optInt("width", 0)
                        val height = r.optInt("height", 0)
                        val badge = if (width > 0 && height > 0) "${width}x${height}" else "Full Photo"

                        items.add(
                            SearchResultItem(
                                id = "openverse_p_$id",
                                title = title.take(75),
                                author = author,
                                durationFormatted = badge,
                                thumbnail = thumb,
                                directUrl = imgUrl,
                                platform = "Openverse",
                                mediaType = "image",
                                downloadUrl = imgUrl,
                                pageUrl = r.optString("foreign_landing_url", imgUrl),
                                resolutionBadge = badge
                            )
                        )
                    }
                }
            }
        } catch (e: Exception) {
            // Ignore
        }
        items
    }

    /**
     * 8. Wikimedia Commons High-Res Art, Illustrations & Photos
     */
    suspend fun searchWikimediaPhotos(query: String, page: Int = 1): List<SearchResultItem> = withContext(Dispatchers.IO) {
        val items = mutableListOf<SearchResultItem>()
        try {
            val limit = 12
            val url = "https://commons.wikimedia.org/w/api.php?action=query&generator=search&gsrnamespace=6&gsrsearch=filetype:bitmap%20" +
                    URLEncoder.encode(query, "UTF-8") + "&gsrlimit=$limit&prop=imageinfo&iiprop=url|size|mime|dimensions&iiurlwidth=480&format=json"

            val request = Request.Builder()
                .url(url)
                .header("User-Agent", "Mozilla/5.0 MediaFetch/1.0")
                .build()

            val response = client.newCall(request).execute()
            if (response.isSuccessful) {
                val body = response.body?.string() ?: ""
                val json = JSONObject(body)
                val pages = json.optJSONObject("query")?.optJSONObject("pages")
                if (pages != null) {
                    val keys = pages.keys()
                    while (keys.hasNext()) {
                        val pageId = keys.next()
                        val pObj = pages.optJSONObject(pageId) ?: continue
                        val imageInfoArr = pObj.optJSONArray("imageinfo") ?: continue
                        if (imageInfoArr.length() == 0) continue

                        val info = imageInfoArr.getJSONObject(0)
                        val rawUrl = info.optString("url", "")
                        if (rawUrl.isBlank()) continue

                        val titleRaw = pObj.optString("title", "Artwork")
                            .replace(Regex("^File:", RegexOption.IGNORE_CASE), "")
                            .replace(Regex("\\.[^/.]+$"), "")
                            .replace(Regex("[_-]"), " ")
                            .trim()

                        val width = info.optInt("width", 1920)
                        val height = info.optInt("height", 1080)
                        val thumb = info.optString("thumburl", rawUrl)

                        items.add(
                            SearchResultItem(
                                id = "wiki_p_$pageId",
                                title = titleRaw.capitalizeWords(),
                                author = "Wikimedia Commons",
                                durationFormatted = "${width}x${height}",
                                thumbnail = thumb,
                                directUrl = rawUrl,
                                platform = "Wikimedia Art",
                                mediaType = "image",
                                downloadUrl = rawUrl,
                                pageUrl = info.optString("descriptionurl", rawUrl),
                                resolutionBadge = "${width}x${height}"
                            )
                        )
                    }
                }
            }
        } catch (e: Exception) {
            // Ignore
        }
        items
    }

    /**
     * 9. Wallhaven 4K Ultra-HD Wallpapers
     */
    suspend fun searchWallhaven(query: String, page: Int = 1): List<SearchResultItem> = withContext(Dispatchers.IO) {
        val items = mutableListOf<SearchResultItem>()
        try {
            val url = "https://wallhaven.cc/api/v1/search?q=" + URLEncoder.encode(query, "UTF-8") + "&sorting=views&page=$page"
            val request = Request.Builder()
                .url(url)
                .header("User-Agent", "Mozilla/5.0 MediaFetch/1.0")
                .build()

            val response = client.newCall(request).execute()
            if (response.isSuccessful) {
                val body = response.body?.string() ?: ""
                val json = JSONObject(body)
                val data = json.optJSONArray("data")
                if (data != null) {
                    for (i in 0 until data.length().coerceAtMost(12)) {
                        val d = data.optJSONObject(i) ?: continue
                        val id = d.optString("id", "")
                        val fullPath = d.optString("path", "")
                        val res = d.optString("resolution", "4K UHD")
                        val thumbs = d.optJSONObject("thumbs")
                        val thumb = thumbs?.optString("large") ?: thumbs?.optString("original") ?: fullPath
                        val pageUrl = d.optString("url", fullPath)

                        items.add(
                            SearchResultItem(
                                id = "wh_$id",
                                title = "$query Wallpaper ($res)".capitalizeWords(),
                                author = "Wallhaven Artist",
                                durationFormatted = res,
                                thumbnail = thumb,
                                directUrl = fullPath,
                                platform = "Wallhaven 4K",
                                mediaType = "image",
                                downloadUrl = fullPath,
                                pageUrl = pageUrl,
                                resolutionBadge = res
                            )
                        )
                    }
                }
            }
        } catch (e: Exception) {
            // Ignore
        }
        items
    }

    private fun parseDurationToSeconds(durationText: String): Long {
        if (durationText.isBlank()) return 0L
        return try {
            val parts = durationText.split(":").map { it.trim().toLongOrNull() ?: 0L }
            when (parts.size) {
                3 -> parts[0] * 3600 + parts[1] * 60 + parts[2]
                2 -> parts[0] * 60 + parts[1]
                1 -> parts[0]
                else -> 0L
            }
        } catch (e: Exception) {
            0L
        }
    }

    private fun formatSecondsToDuration(seconds: Long): String {
        val hrs = seconds / 3600
        val mins = (seconds % 3600) / 60
        val secs = seconds % 60
        return if (hrs > 0) {
            String.format("%d:%02d:%02d", hrs, mins, secs)
        } else {
            String.format("%02d:%02d", mins, secs)
        }
    }

    private fun String.capitalizeWords(): String {
        return split(" ").joinToString(" ") { word ->
            word.lowercase().replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }
        }
    }
}