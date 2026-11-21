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

    var pexelsApiKey: String = ""
    var pixabayApiKey: String = ""
    var unsplashApiKey: String = ""

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

        val orientation = platforms.find { it.startsWith("orientation:") }?.substringAfter(":")
            ?: if (platforms.contains("landscape")) "landscape" else if (platforms.contains("portrait")) "portrait" else "all"

        val songFilter = platforms.find { it.startsWith("songs:") }?.substringAfter(":")
            ?: when {
                platforms.contains("songs") || platforms.contains("song") -> "songs"
                platforms.contains("albums") || platforms.contains("album") -> "albums"
                platforms.contains("artists") || platforms.contains("artist") -> "artists"
                platforms.contains("playlists") || platforms.contains("playlist") -> "playlists"
                else -> "all"
            }

        val deferredList = mutableListOf<kotlinx.coroutines.Deferred<List<SearchResultItem>>>()

        val isAll = platforms.isEmpty() || platforms.contains("all")

        // 1. YouTube Videos & Shorts
        if (isAll || platforms.contains("youtube") || platforms.contains("video")) {
            deferredList.add(async { searchYouTube(clean, page, orientation) })
        }

        // 2. Pexels HD / 4K Free Videos
        if (isAll || platforms.contains("pexels") || platforms.contains("pexels_video") || platforms.contains("video")) {
            deferredList.add(async { searchPexelsVideos(clean, page, orientation) })
        }

        // 3. Pixabay HD / 4K Stock Videos
        if (isAll || platforms.contains("pixabay") || platforms.contains("pixabay_video") || platforms.contains("video")) {
            deferredList.add(async { searchPixabayVideos(clean, page, orientation) })
        }

        // 4. Wikimedia Commons 4K / HD Videos
        if (isAll || platforms.contains("wikimedia") || platforms.contains("wikimedia_video") || platforms.contains("video")) {
            deferredList.add(async { searchWikimediaVideos(clean, page, orientation) })
        }

        // 5. Pexels High-Res Photos
        if (isAll || platforms.contains("pexels") || platforms.contains("pexels_photo") || platforms.contains("photo") || platforms.contains("image")) {
            deferredList.add(async { searchPexelsPhotos(clean, page, orientation) })
        }

        // 6. Pixabay High-Res Photos
        if (isAll || platforms.contains("pixabay") || platforms.contains("pixabay_photo") || platforms.contains("photo") || platforms.contains("image")) {
            deferredList.add(async { searchPixabayPhotos(clean, page, orientation) })
        }

        // 7. Openverse 700M+ High-Res Photos & Art
        if (isAll || platforms.contains("openverse") || platforms.contains("photo") || platforms.contains("image")) {
            deferredList.add(async { searchOpenverseImages(clean, page, orientation) })
        }

        // 8. Wikimedia Commons High-Res Art & Photography
        if (isAll || platforms.contains("wikimedia") || platforms.contains("wikimedia_photo") || platforms.contains("photo") || platforms.contains("image")) {
            deferredList.add(async { searchWikimediaPhotos(clean, page, orientation) })
        }

        // 9. Wallhaven 4K Ultra-HD Wallpapers
        if (isAll || platforms.contains("wallhaven") || platforms.contains("photo") || platforms.contains("wallpaper")) {
            deferredList.add(async { searchWallhaven(clean, page, orientation) })
        }

        // 10. Universal Keyless Songs & Music Catalog
        val hasSongFilter = platforms.any { it.contains("song") || it.contains("music") || it.contains("audio") || it.contains("album") || it.contains("artist") || it.contains("playlist") }
        if (isAll || hasSongFilter) {
            deferredList.add(async { searchSongs(clean, songFilter, page) })
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
     * 1. Supercharged YouTube Search (Real YouTube results: long-form videos + viral shorts + orientation support)
     */
    suspend fun searchYouTube(query: String, page: Int = 1, orientation: String = "all"): List<SearchResultItem> = withContext(Dispatchers.IO) {
        val clean = query.trim()
        if (clean.isBlank()) return@withContext emptyList<SearchResultItem>()

        val items = mutableListOf<SearchResultItem>()
        val seenIds = mutableSetOf<String>()

        try {
            val cleanQ = URLEncoder.encode(clean, "UTF-8")
            val isPortrait = orientation.equals("portrait", ignoreCase = true)
            val isLandscape = orientation.equals("landscape", ignoreCase = true)

            val d1 = async {
                val qUrl = if (isPortrait) {
                    "https://www.youtube.com/results?search_query=$cleanQ+%23shorts&sp=EgIYAQ%253D%253D"
                } else if (isLandscape) {
                    "https://www.youtube.com/results?search_query=$cleanQ&sp=EgIYAw%253D%253D"
                } else {
                    "https://www.youtube.com/results?search_query=$cleanQ"
                }
                fetchYtInitialData(qUrl)
            }
            val d2 = async {
                val qUrl = if (isPortrait) {
                    "https://www.youtube.com/results?search_query=$cleanQ+shorts"
                } else if (isLandscape) {
                    "https://www.youtube.com/results?search_query=$cleanQ&sp=EgIQAQ%253D%253D"
                } else {
                    "https://www.youtube.com/results?search_query=$cleanQ&sp=EgIQAQ%253D%253D"
                }
                fetchYtInitialData(qUrl)
            }

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
                val isPortrait = orientation.equals("portrait", ignoreCase = true)
                val finalQuery = if (isPortrait) "$clean #shorts" else clean
                val payload = JSONObject().apply {
                    put("context", JSONObject().apply {
                        put("client", JSONObject().apply {
                            put("clientName", "WEB")
                            put("clientVersion", "2.20241001.01.00")
                            put("hl", "en")
                            put("gl", "US")
                        })
                    })
                    put("query", finalQuery)
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

        // Apply strict orientation filtering if required
        if (orientation.equals("portrait", ignoreCase = true)) {
            val shortsOnly = items.filter { it.durationFormatted.contains("Short", ignoreCase = true) || it.title.contains("#shorts", ignoreCase = true) || it.durationSeconds <= 60L }
            if (shortsOnly.isNotEmpty()) return@withContext shortsOnly
        } else if (orientation.equals("landscape", ignoreCase = true)) {
            val longOnly = items.filter { !it.durationFormatted.contains("Short", ignoreCase = true) && !it.title.contains("#shorts", ignoreCase = true) && it.durationSeconds > 60L }
            if (longOnly.isNotEmpty()) return@withContext longOnly
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
                    } else if (obj.has("lockupViewModel")) {
                        val vm = obj.optJSONObject("lockupViewModel")
                        if (vm != null) {
                            val vid = vm.optString("contentId", "")
                            if (vid.isNotBlank()) {
                                val meta = vm.optJSONObject("metadata")?.optJSONObject("lockupMetadataViewModel")
                                val title = meta?.optJSONObject("title")?.optString("content", "") ?: ""
                                val channel = meta?.optJSONObject("metadata")?.optJSONObject("contentMetadataViewModel")
                                    ?.optJSONArray("metadataRows")?.optJSONObject(0)?.optJSONArray("parts")?.optJSONObject(0)?.optString("text", "") ?: ""
                                val dur = vm.optJSONObject("contentImage")?.optJSONObject("thumbnailOverlayBadgeViewModel")
                                    ?.optJSONObject("badge")?.optJSONObject("badgeViewModel")?.optString("label", "") ?: ""
                                val thumbSources = vm.optJSONObject("contentImage")?.optJSONObject("collectionThumbnailViewModel")
                                    ?.optJSONObject("primaryThumbnail")?.optJSONObject("thumbnailViewModel")?.optJSONObject("image")?.optJSONArray("sources")
                                val thumb = if (thumbSources != null && thumbSources.length() > 0) thumbSources.optJSONObject(thumbSources.length() - 1)?.optString("url", "") ?: "" else ""
                                addVideo(vid, title, channel, dur, thumb, false)
                            }
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
    /**
     * 2. Wikimedia Commons 4K / HD Video Search (Real 4K Stock Clips with Direct URLs & Durations)
     */
    suspend fun searchWikimediaVideos(query: String, page: Int = 1, orientation: String = "all"): List<SearchResultItem> = withContext(Dispatchers.IO) {
        val items = mutableListOf<SearchResultItem>()
        try {
            val limit = 30
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

                        val width = info.optInt("width", 1920)
                        val height = info.optInt("height", 1080)

                        if (orientation.equals("landscape", ignoreCase = true) && width < height) continue
                        if (orientation.equals("portrait", ignoreCase = true) && height <= width) continue

                        val titleRaw = pObj.optString("title", "Stock Video")
                            .replace(Regex("^File:", RegexOption.IGNORE_CASE), "")
                            .replace(Regex("\\.[^/.]+$"), "")
                            .replace(Regex("[_-]"), " ")
                            .trim()

                        val durationSeconds = info.optDouble("duration", 0.0).toLong()
                        val durationStr = if (durationSeconds > 0) formatSecondsToDuration(durationSeconds) else "HD Video"
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
    suspend fun searchOpenverseImages(query: String, page: Int = 1, orientation: String = "all"): List<SearchResultItem> = withContext(Dispatchers.IO) {
        val items = mutableListOf<SearchResultItem>()
        try {
            val aspectParam = if (orientation.equals("landscape", ignoreCase = true)) "&aspect_ratio=wide" else if (orientation.equals("portrait", ignoreCase = true)) "&aspect_ratio=tall" else ""
            val url = "https://api.openverse.org/v1/images/?q=" + URLEncoder.encode(query, "UTF-8") + "&page_size=35&page=$page$aspectParam"
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

                        if (width > 0 && height > 0) {
                            if (orientation.equals("landscape", ignoreCase = true) && width < height) continue
                            if (orientation.equals("portrait", ignoreCase = true) && height <= width) continue
                        }

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
    suspend fun searchWikimediaPhotos(query: String, page: Int = 1, orientation: String = "all"): List<SearchResultItem> = withContext(Dispatchers.IO) {
        val items = mutableListOf<SearchResultItem>()
        try {
            val limit = 35
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

                        val width = info.optInt("width", 1920)
                        val height = info.optInt("height", 1080)

                        if (orientation.equals("landscape", ignoreCase = true) && width < height) continue
                        if (orientation.equals("portrait", ignoreCase = true) && height <= width) continue

                        val titleRaw = pObj.optString("title", "Artwork")
                            .replace(Regex("^File:", RegexOption.IGNORE_CASE), "")
                            .replace(Regex("\\.[^/.]+$"), "")
                            .replace(Regex("[_-]"), " ")
                            .trim()

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
    suspend fun searchWallhaven(query: String, page: Int = 1, orientation: String = "all"): List<SearchResultItem> = withContext(Dispatchers.IO) {
        val items = mutableListOf<SearchResultItem>()
        try {
            val ratiosParam = if (orientation.equals("landscape", ignoreCase = true)) "&ratios=landscape,16x9,16x10,21x9"
                else if (orientation.equals("portrait", ignoreCase = true)) "&ratios=portrait,9x16"
                else ""
            val url = "https://wallhaven.cc/api/v1/search?q=" + URLEncoder.encode(query, "UTF-8") + "$ratiosParam&sorting=views&page=$page"
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
                    for (i in 0 until data.length().coerceAtMost(30)) {
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

    /**
     * Pexels HD / 4K Stock Video Search (100% Free & Direct MP4 Streams)
     */
    suspend fun searchPexelsVideos(query: String, page: Int = 1, orientation: String = "all"): List<SearchResultItem> = withContext(Dispatchers.IO) {
        val items = mutableListOf<SearchResultItem>()
        if (pexelsApiKey.isBlank()) return@withContext items
        try {
            val orientParam = if (orientation.equals("landscape", ignoreCase = true) || orientation.equals("portrait", ignoreCase = true)) "&orientation=$orientation" else ""
            val url = "https://api.pexels.com/videos/search?query=" + URLEncoder.encode(query, "UTF-8") + "$orientParam&per_page=40&page=$page"
            val request = Request.Builder()
                .url(url)
                .header("Authorization", pexelsApiKey)
                .header("User-Agent", "Mozilla/5.0 MediaFetch/1.0")
                .build()

            val response = client.newCall(request).execute()
            if (response.isSuccessful) {
                val body = response.body?.string() ?: ""
                val json = JSONObject(body)
                val videos = json.optJSONArray("videos")
                if (videos != null) {
                    for (i in 0 until videos.length()) {
                        val v = videos.optJSONObject(i) ?: continue
                        val id = v.optLong("id", 0L)
                        val pageUrl = v.optString("url", "")
                        val thumb = v.optString("image", "")
                        val dur = v.optInt("duration", 0)
                        val userObj = v.optJSONObject("user")
                        val author = userObj?.optString("name", "Pexels Creator") ?: "Pexels Creator"
                        val authorUrl = userObj?.optString("url", "") ?: ""

                        val videoFiles = v.optJSONArray("video_files")
                        var bestUrl = ""
                        var bestRes = "HD"
                        var maxHeight = 0

                        if (videoFiles != null) {
                            for (j in 0 until videoFiles.length()) {
                                val vf = videoFiles.optJSONObject(j) ?: continue
                                val link = vf.optString("link", "")
                                val height = vf.optInt("height", 0)
                                if (link.isNotBlank() && height > maxHeight) {
                                    maxHeight = height
                                    bestUrl = link
                                    bestRes = if (height >= 2160) "4K UHD" else if (height >= 1440) "2K QHD" else if (height >= 1080) "1080p FHD" else "${height}p"
                                }
                            }
                        }

                        if (bestUrl.isBlank() && videoFiles != null && videoFiles.length() > 0) {
                            bestUrl = videoFiles.optJSONObject(0)?.optString("link", "") ?: ""
                        }

                        val durFormatted = if (dur > 0) formatSecondsToDuration(dur.toLong()) else "HD Video"

                        items.add(
                            SearchResultItem(
                                id = "pexels_v_$id",
                                title = "$query Stock Video ($bestRes)".capitalizeWords(),
                                author = author,
                                authorUrl = authorUrl,
                                durationFormatted = durFormatted,
                                durationSeconds = dur.toLong(),
                                thumbnail = thumb,
                                directUrl = bestUrl,
                                platform = "Pexels 4K",
                                mediaType = "video",
                                downloadUrl = bestUrl,
                                pageUrl = pageUrl,
                                resolutionBadge = bestRes
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
     * Pexels Ultra High-Res Photos & Wallpapers
     */
    suspend fun searchPexelsPhotos(query: String, page: Int = 1, orientation: String = "all"): List<SearchResultItem> = withContext(Dispatchers.IO) {
        val items = mutableListOf<SearchResultItem>()
        if (pexelsApiKey.isBlank()) return@withContext items
        try {
            val orientParam = if (orientation.equals("landscape", ignoreCase = true) || orientation.equals("portrait", ignoreCase = true)) "&orientation=$orientation" else ""
            val url = "https://api.pexels.com/v1/search?query=" + URLEncoder.encode(query, "UTF-8") + "$orientParam&per_page=40&page=$page"
            val request = Request.Builder()
                .url(url)
                .header("Authorization", pexelsApiKey)
                .header("User-Agent", "Mozilla/5.0 MediaFetch/1.0")
                .build()

            val response = client.newCall(request).execute()
            if (response.isSuccessful) {
                val body = response.body?.string() ?: ""
                val json = JSONObject(body)
                val photos = json.optJSONArray("photos")
                if (photos != null) {
                    for (i in 0 until photos.length()) {
                        val p = photos.optJSONObject(i) ?: continue
                        val id = p.optLong("id", 0L)
                        val pageUrl = p.optString("url", "")
                        val author = p.optString("photographer", "Pexels Photographer")
                        val authorUrl = p.optString("photographer_url", "")
                        val width = p.optInt("width", 1920)
                        val height = p.optInt("height", 1080)
                        val src = p.optJSONObject("src")
                        val original = src?.optString("original", "") ?: ""
                        val large = src?.optString("large2x", "") ?: src?.optString("large", "") ?: original
                        val medium = src?.optString("medium", "") ?: large

                        val bestUrl = if (original.isNotBlank()) original else large

                        items.add(
                            SearchResultItem(
                                id = "pexels_p_$id",
                                title = "$query Photo by $author".capitalizeWords(),
                                author = author,
                                authorUrl = authorUrl,
                                durationFormatted = "${width}x${height}",
                                thumbnail = medium,
                                directUrl = bestUrl,
                                platform = "Pexels Photo",
                                mediaType = "image",
                                downloadUrl = bestUrl,
                                pageUrl = pageUrl,
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
     * Pixabay 4K / HD Stock Videos
     */
    suspend fun searchPixabayVideos(query: String, page: Int = 1, orientation: String = "all"): List<SearchResultItem> = withContext(Dispatchers.IO) {
        val items = mutableListOf<SearchResultItem>()
        if (pixabayApiKey.isBlank()) return@withContext items
        try {
            val orientParam = if (orientation.equals("landscape", ignoreCase = true)) "&orientation=horizontal"
                else if (orientation.equals("portrait", ignoreCase = true)) "&orientation=vertical"
                else ""
            val url = "https://pixabay.com/api/videos/?key=" + pixabayApiKey +
                    "&q=" + URLEncoder.encode(query, "UTF-8") + "$orientParam&per_page=40&page=$page"
            val request = Request.Builder()
                .url(url)
                .header("User-Agent", "Mozilla/5.0 MediaFetch/1.0")
                .build()

            val response = client.newCall(request).execute()
            if (response.isSuccessful) {
                val body = response.body?.string() ?: ""
                val json = JSONObject(body)
                val hits = json.optJSONArray("hits")
                if (hits != null) {
                    for (i in 0 until hits.length()) {
                        val h = hits.optJSONObject(i) ?: continue
                        val id = h.optLong("id", 0L)
                        val pageUrl = h.optString("pageURL", "")
                        val dur = h.optInt("duration", 0)
                        val user = h.optString("user", "Pixabay Creator")
                        val tags = h.optString("tags", query)

                        val videosObj = h.optJSONObject("videos")
                        var bestUrl = ""
                        var bestRes = "HD"

                        val largeObj = videosObj?.optJSONObject("large")
                        val mediumObj = videosObj?.optJSONObject("medium")
                        val smallObj = videosObj?.optJSONObject("small")
                        val tinyObj = videosObj?.optJSONObject("tiny")

                        if (largeObj != null && largeObj.optString("url", "").isNotBlank()) {
                            bestUrl = largeObj.optString("url", "")
                            val hVal = largeObj.optInt("height", 1080)
                            bestRes = if (hVal >= 2160) "4K UHD" else "${hVal}p"
                        } else if (mediumObj != null && mediumObj.optString("url", "").isNotBlank()) {
                            bestUrl = mediumObj.optString("url", "")
                            bestRes = "${mediumObj.optInt("height", 720)}p"
                        } else if (smallObj != null && smallObj.optString("url", "").isNotBlank()) {
                            bestUrl = smallObj.optString("url", "")
                            bestRes = "${smallObj.optInt("height", 480)}p"
                        } else if (tinyObj != null && tinyObj.optString("url", "").isNotBlank()) {
                            bestUrl = tinyObj.optString("url", "")
                            bestRes = "${tinyObj.optInt("height", 360)}p"
                        }

                        val picId = h.optString("picture_id", "")
                        val thumb = (mediumObj?.optString("thumbnail", "")?.takeIf { it.isNotBlank() }
                            ?: smallObj?.optString("thumbnail", "")?.takeIf { it.isNotBlank() }
                            ?: tinyObj?.optString("thumbnail", "")?.takeIf { it.isNotBlank() }
                            ?: largeObj?.optString("thumbnail", "")?.takeIf { it.isNotBlank() }
                            ?: (if (picId.isNotBlank()) "https://i.vimeocdn.com/video/${picId}_640x360.jpg" else "")
                                .takeIf { it.isNotBlank() }
                            ?: h.optString("userImageURL", ""))

                        val durFormatted = if (dur > 0) formatSecondsToDuration(dur.toLong()) else "HD Video"

                        items.add(
                            SearchResultItem(
                                id = "pixabay_v_$id",
                                title = "$tags ($bestRes)".capitalizeWords(),
                                author = user,
                                authorUrl = "https://pixabay.com/users/$user-${h.optLong("user_id", 0L)}/",
                                durationFormatted = durFormatted,
                                durationSeconds = dur.toLong(),
                                thumbnail = thumb,
                                directUrl = bestUrl,
                                platform = "Pixabay HD",
                                mediaType = "video",
                                downloadUrl = bestUrl,
                                pageUrl = pageUrl,
                                resolutionBadge = bestRes
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
     * Pixabay High-Res Photos & Vector Art
     */
    suspend fun searchPixabayPhotos(query: String, page: Int = 1, orientation: String = "all"): List<SearchResultItem> = withContext(Dispatchers.IO) {
        val items = mutableListOf<SearchResultItem>()
        if (pixabayApiKey.isBlank()) return@withContext items
        try {
            val orientParam = if (orientation.equals("landscape", ignoreCase = true)) "&orientation=horizontal"
                else if (orientation.equals("portrait", ignoreCase = true)) "&orientation=vertical"
                else ""
            val url = "https://pixabay.com/api/?key=" + pixabayApiKey +
                    "&q=" + URLEncoder.encode(query, "UTF-8") + "$orientParam&per_page=40&page=$page&image_type=photo"
            val request = Request.Builder()
                .url(url)
                .header("User-Agent", "Mozilla/5.0 MediaFetch/1.0")
                .build()

            val response = client.newCall(request).execute()
            if (response.isSuccessful) {
                val body = response.body?.string() ?: ""
                val json = JSONObject(body)
                val hits = json.optJSONArray("hits")
                if (hits != null) {
                    for (i in 0 until hits.length()) {
                        val h = hits.optJSONObject(i) ?: continue
                        val id = h.optLong("id", 0L)
                        val pageUrl = h.optString("pageURL", "")
                        val user = h.optString("user", "Pixabay Artist")
                        val tags = h.optString("tags", query)
                        val width = h.optInt("imageWidth", 1920)
                        val height = h.optInt("imageHeight", 1080)
                        val largeImageURL = h.optString("largeImageURL", "")
                        val webformatURL = h.optString("webformatURL", largeImageURL)
                        val previewURL = h.optString("previewURL", webformatURL)

                        val bestUrl = if (largeImageURL.isNotBlank()) largeImageURL else webformatURL

                        items.add(
                            SearchResultItem(
                                id = "pixabay_p_$id",
                                title = "$tags Photo".capitalizeWords(),
                                author = user,
                                authorUrl = "https://pixabay.com/users/$user-${h.optLong("user_id", 0L)}/",
                                durationFormatted = "${width}x${height}",
                                thumbnail = webformatURL.ifBlank { previewURL },
                                directUrl = bestUrl,
                                platform = "Pixabay Photo",
                                mediaType = "image",
                                downloadUrl = bestUrl,
                                pageUrl = pageUrl,
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
     * Universal Songs & Music Search (100M+ Songs, Albums, Artists, Playlists - Keyless)
     */
    suspend fun searchSongs(query: String, filter: String = "all", page: Int = 1): List<SearchResultItem> = withContext(Dispatchers.IO) {
        val clean = query.trim()
        if (clean.isBlank()) return@withContext emptyList<SearchResultItem>()
        val results = mutableListOf<SearchResultItem>()

        try {
            val encQuery = URLEncoder.encode(clean, "UTF-8")

            // 0. Jamendo 320kbps MP3 Free Tracks (Full-length streams & direct downloads)
            if (filter == "songs" || filter == "all") {
                try {
                    val jUrl = "https://api.jamendo.com/v3.0/tracks/?client_id=3dce8b55&format=json&limit=50&namesearch=$encQuery&include=musicinfo&audioformat=mp32"
                    val req = Request.Builder().url(jUrl).build()
                    client.newCall(req).execute().use { resp ->
                        if (resp.isSuccessful) {
                            val body = resp.body?.string()
                            if (body != null) {
                                val json = JSONObject(body)
                                val arr = json.optJSONArray("results") ?: JSONArray()
                                for (i in 0 until arr.length()) {
                                    val item = arr.getJSONObject(i)
                                    val trackName = item.optString("name", "Track")
                                    val artistName = item.optString("artist_name", "Artist")
                                    val trackId = item.optString("id", "$i")
                                    val audioUrl = item.optString("audio", "")
                                    val downloadUrl = item.optString("audiodownload", audioUrl)
                                    val artUrl = item.optString("image", "").ifBlank { "https://images.unsplash.com/photo-1511671782779-c97d3d27a1d4?w=600" }
                                    val durSec = item.optLong("duration", 180L)
                                    val durFmt = String.format("%d:%02d", durSec / 60, durSec % 60)

                                    results.add(SearchResultItem(
                                        id = "jamendo_$trackId",
                                        title = trackName,
                                        author = artistName,
                                        durationFormatted = durFmt,
                                        durationSeconds = durSec,
                                        thumbnail = artUrl,
                                        directUrl = audioUrl,
                                        platform = "Songs",
                                        mediaType = "audio",
                                        downloadUrl = downloadUrl.ifBlank { audioUrl },
                                        pageUrl = item.optString("shareurl", audioUrl),
                                        resolutionBadge = "320k MP3"
                                    ))
                                }
                            }
                        }
                    }
                } catch (_: Exception) {}
            }

            // 1. iTunes Songs
            if (filter == "songs" || filter == "all") {
                val url = "https://itunes.apple.com/search?term=$encQuery&entity=song&limit=${if (filter == "all") 30 else 50}"
                val req = Request.Builder().url(url).build()
                client.newCall(req).execute().use { resp ->
                    if (resp.isSuccessful) {
                        val body = resp.body?.string()
                        if (body != null) {
                            val json = JSONObject(body)
                            val arr = json.optJSONArray("results") ?: JSONArray()
                            for (i in 0 until arr.length()) {
                                val item = arr.getJSONObject(i)
                                val trackName = item.optString("trackName", "Track")
                                val artistName = item.optString("artistName", "Artist")
                                val trackId = item.optLong("trackId", 0L)
                                val previewUrl = item.optString("previewUrl", "")
                                val artUrl = item.optString("artworkUrl100", "").replace("100x100bb", "600x600bb")
                                val durMs = item.optLong("trackTimeMillis", 0L)
                                val durSec = durMs / 1000
                                val durFmt = String.format("%d:%02d", durSec / 60, durSec % 60)

                                results.add(SearchResultItem(
                                    id = "song_$trackId",
                                    title = trackName,
                                    author = artistName,
                                    durationFormatted = durFmt,
                                    durationSeconds = durSec,
                                    thumbnail = artUrl,
                                    directUrl = previewUrl,
                                    platform = "Songs",
                                    mediaType = "audio",
                                    downloadUrl = previewUrl,
                                    pageUrl = item.optString("trackViewUrl", ""),
                                    resolutionBadge = "SONG"
                                ))
                            }
                        }
                    }
                }
            }

            // 2. Artists
            if (filter == "artists" || filter == "all") {
                val url = "https://itunes.apple.com/search?term=$encQuery&entity=musicArtist&limit=${if (filter == "all") 15 else 35}"
                val req = Request.Builder().url(url).build()
                client.newCall(req).execute().use { resp ->
                    if (resp.isSuccessful) {
                        val body = resp.body?.string()
                        if (body != null) {
                            val json = JSONObject(body)
                            val arr = json.optJSONArray("results") ?: JSONArray()
                            for (i in 0 until arr.length()) {
                                val item = arr.getJSONObject(i)
                                val artistName = item.optString("artistName", "Artist")
                                val artistId = item.optLong("artistId", 0L)

                                results.add(SearchResultItem(
                                    id = "artist_$artistId",
                                    title = artistName,
                                    author = item.optString("primaryGenreName", "Artist"),
                                    durationFormatted = "Top Hits",
                                    durationSeconds = 0L,
                                    thumbnail = "https://images.unsplash.com/photo-1514525253161-7a46d19cd819?w=600",
                                    directUrl = "",
                                    platform = "Songs",
                                    mediaType = "audio",
                                    downloadUrl = "",
                                    pageUrl = item.optString("artistLinkUrl", ""),
                                    resolutionBadge = "ARTIST"
                                ))
                            }
                        }
                    }
                }
            }

            // 3. Albums
            if (filter == "albums" || filter == "all") {
                val url = "https://itunes.apple.com/search?term=$encQuery&entity=album&limit=${if (filter == "all") 20 else 45}"
                val req = Request.Builder().url(url).build()
                client.newCall(req).execute().use { resp ->
                    if (resp.isSuccessful) {
                        val body = resp.body?.string()
                        if (body != null) {
                            val json = JSONObject(body)
                            val arr = json.optJSONArray("results") ?: JSONArray()
                            for (i in 0 until arr.length()) {
                                val item = arr.getJSONObject(i)
                                val colName = item.optString("collectionName", "Album")
                                val artistName = item.optString("artistName", "Artist")
                                val colId = item.optLong("collectionId", 0L)
                                val artUrl = item.optString("artworkUrl100", "").replace("100x100bb", "600x600bb")
                                val trackCount = item.optInt("trackCount", 0)

                                results.add(SearchResultItem(
                                    id = "album_$colId",
                                    title = colName,
                                    author = artistName,
                                    durationFormatted = "$trackCount Tracks",
                                    durationSeconds = 0L,
                                    thumbnail = artUrl,
                                    directUrl = "",
                                    platform = "Songs",
                                    mediaType = "audio",
                                    downloadUrl = "",
                                    pageUrl = item.optString("collectionViewUrl", ""),
                                    resolutionBadge = "ALBUM"
                                ))
                            }
                        }
                    }
                }
            }

            // 4. Playlists / Sets
            if (filter == "playlists" || filter == "all") {
                val url = "https://itunes.apple.com/search?term=$encQuery&entity=album&limit=${if (filter == "all") 15 else 35}"
                val req = Request.Builder().url(url).build()
                client.newCall(req).execute().use { resp ->
                    if (resp.isSuccessful) {
                        val body = resp.body?.string()
                        if (body != null) {
                            val json = JSONObject(body)
                            val arr = json.optJSONArray("results") ?: JSONArray()
                            for (i in 0 until arr.length()) {
                                val item = arr.getJSONObject(i)
                                val colName = item.optString("collectionName", clean)
                                val artistName = item.optString("artistName", "Curated")
                                val colId = item.optLong("collectionId", 0L)
                                val artUrl = item.optString("artworkUrl100", "").replace("100x100bb", "600x600bb")
                                val trackCount = item.optInt("trackCount", 12)

                                results.add(SearchResultItem(
                                    id = "playlist_$colId",
                                    title = "$colName (Set)",
                                    author = artistName,
                                    durationFormatted = "$trackCount Songs",
                                    durationSeconds = 0L,
                                    thumbnail = artUrl,
                                    directUrl = "",
                                    platform = "Songs",
                                    mediaType = "audio",
                                    downloadUrl = "",
                                    pageUrl = item.optString("collectionViewUrl", ""),
                                    resolutionBadge = "PLAYLIST"
                                ))
                            }
                        }
                    }
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }

        results
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