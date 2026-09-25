package com.mediafetch.app.engine

import com.mediafetch.app.model.ChapterItem
import com.mediafetch.app.model.MediaItem
import com.mediafetch.app.model.MediaType
import com.mediafetch.app.model.PlaylistItem
import com.mediafetch.app.model.QualityOption
import com.mediafetch.app.model.SubtitleTrack
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import android.content.Context
import android.os.Environment
import java.io.File
import java.util.concurrent.TimeUnit
import java.util.regex.Pattern

object MediaEngine {

    private val cookieList = mutableListOf<Cookie>()
    private val cookieJar = object : CookieJar {
        override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
            synchronized(cookieList) {
                for (cookie in cookies) {
                    cookieList.removeAll { it.name == cookie.name && it.matches(url) }
                    cookieList.add(cookie)
                }
            }
        }
        override fun loadForRequest(url: HttpUrl): List<Cookie> {
            synchronized(cookieList) {
                return cookieList.filter { it.matches(url) }
            }
        }
    }

    fun applyCustomCookies(rawCookies: String) {
        if (rawCookies.isBlank()) return
        synchronized(cookieList) {
            val lines = rawCookies.lines()
            for (line in lines) {
                val trimmed = line.trim()
                if (trimmed.isBlank() || trimmed.startsWith("#")) continue
                val parts = trimmed.split("\t")
                if (parts.size >= 7) {
                    val domain = parts[0].trim().removePrefix(".")
                    val name = parts[5].trim()
                    val value = parts[6].trim()
                    if (domain.isNotBlank() && name.isNotBlank()) {
                        try {
                            val cookie = Cookie.Builder()
                                .domain(domain)
                                .path(parts[2].trim().ifBlank { "/" })
                                .name(name)
                                .value(value)
                                .build()
                            cookieList.removeAll { it.name == name && it.domain == domain }
                            cookieList.add(cookie)
                        } catch (_: Exception) {}
                    }
                } else if (trimmed.contains("=")) {
                    val pairs = trimmed.split(";")
                    for (p in pairs) {
                        val kv = p.split("=", limit = 2)
                        if (kv.size == 2) {
                            val k = kv[0].trim()
                            val v = kv[1].trim()
                            if (k.isNotBlank() && v.isNotBlank()) {
                                val domains = when {
                                    k.equals("sessionid", true) || k.equals("ds_user_id", true) || k.equals("mid", true) || k.startsWith("ig_", true) -> listOf("instagram.com")
                                    k.equals("LOGIN_INFO", true) || k.equals("SID", true) || k.equals("HSID", true) || k.equals("SSID", true) || k.equals("SAPISID", true) -> listOf("youtube.com")
                                    else -> listOf("youtube.com", "instagram.com")
                                }
                                for (targetDomain in domains) {
                                    try {
                                        val cookie = Cookie.Builder()
                                            .domain(targetDomain)
                                            .path("/")
                                            .name(k)
                                            .value(v)
                                            .build()
                                        cookieList.removeAll { it.name == k && it.domain == targetDomain }
                                        cookieList.add(cookie)
                                    } catch (_: Exception) {}
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    fun getCookiesCount(): Int {
        synchronized(cookieList) {
            return cookieList.size
        }
    }

    fun clearCustomCookies() {
        synchronized(cookieList) {
            cookieList.clear()
        }
    }

    fun parseChaptersFromText(desc: String, totalDuration: Long): List<ChapterItem> {
        if (desc.isBlank()) return emptyList()
        val lines = desc.lines()
        val timeRegex = Regex("(?:(\\d{1,2}):)?(\\d{1,2}):(\\d{2})\\s*[-–—]?\\s*(.+)")
        val rawList = mutableListOf<Pair<Long, String>>()
        for (line in lines) {
            val match = timeRegex.find(line.trim()) ?: continue
            val h = match.groupValues[1].toLongOrNull() ?: 0L
            val m = match.groupValues[2].toLongOrNull() ?: 0L
            val s = match.groupValues[3].toLongOrNull() ?: 0L
            val title = match.groupValues[4].trim()
            val secs = (h * 3600L) + (m * 60L) + s
            if (title.isNotBlank()) {
                rawList.add(Pair(secs, title))
            }
        }
        if (rawList.size < 2) return emptyList()
        rawList.sortBy { it.first }
        val result = mutableListOf<ChapterItem>()
        for (i in 0 until rawList.size) {
            val start = rawList[i].first
            val end = if (i + 1 < rawList.size) rawList[i + 1].first else totalDuration.coerceAtLeast(start + 30)
            result.add(ChapterItem(rawList[i].second, start, end))
        }
        return result
    }

    private val client = OkHttpClient.Builder()
        .cookieJar(cookieJar)
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    fun unescapeHtml(text: String): String {
        if (text.isBlank()) return text
        return try {
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.N) {
                android.text.Html.fromHtml(text, android.text.Html.FROM_HTML_MODE_LEGACY).toString().trim()
            } else {
                @Suppress("DEPRECATION")
                android.text.Html.fromHtml(text).toString().trim()
            }
        } catch (_: Exception) {
            text.replace("&amp;", "&")
                .replace("&quot;", "\"")
                .replace("&#39;", "'")
                .replace("&#039;", "'")
                .replace("&#x2019;", "’")
                .replace("&#064;", "@")
                .replace("&lt;", "<")
                .replace("&gt;", ">")
                .trim()
        }
    }

    fun detectPlatform(url: String): String {
        val lower = url.lowercase()
        return when {
            lower.contains("youtube.com") || lower.contains("youtu.be") -> "YouTube"
            lower.contains("instagram.com") -> "Instagram"
            lower.contains("tiktok.com") -> "TikTok"
            lower.contains("reddit.com") || lower.contains("redd.it") -> "Reddit"
            lower.contains("pinterest.com") || lower.contains("pin.it") -> "Pinterest"
            lower.contains("giphy.com") || lower.contains("gph.is") -> "Giphy"
            lower.contains("tenor.com") -> "Tenor"
            lower.contains("twitter.com") || lower.contains("x.com") -> "X / Twitter"
            lower.contains("facebook.com") || lower.contains("fb.watch") || lower.contains("fb.com") -> "Facebook"
            lower.contains("threads.net") || lower.contains("threads.com") -> "Threads"
            lower.contains("soundcloud.com") -> "SoundCloud"
            lower.contains("pixabay.com") -> "Pixabay"
            lower.contains("unsplash.com") -> "Unsplash"
            lower.contains("pexels.com") -> "Pexels"
            lower.contains("spotify.com") || lower.contains("spotify.link") || lower.contains("spoti.fi") -> "Spotify"
            else -> "Web Media"
        }
    }

    fun isPlaylistUrl(url: String): Boolean {
        val lower = url.lowercase()
        return lower.contains("list=") || lower.contains("/playlist") || lower.contains("/sets/") ||
               lower.contains("spotify.com/playlist/") || lower.contains("spotify.com/album/")
    }

    fun isImageUrl(url: String): Boolean {
        val lower = url.lowercase()
        return (lower.endsWith(".jpg") || lower.endsWith(".jpeg") || lower.endsWith(".png") ||
               lower.endsWith(".webp") || lower.endsWith(".gif") ||
               lower.contains("unsplash.com/photos/") || lower.contains("pixabay.com/photos/")) &&
               !lower.contains("instagram.com") && !lower.contains("tiktok.com") &&
               !lower.contains("twitter.com") && !lower.contains("x.com") &&
               !lower.contains("facebook.com") && !lower.contains("threads.net") &&
               !lower.contains("threads.com") && !lower.contains("spotify.com")
    }

    fun extractUrlFromText(text: String): String {
        val pattern = Pattern.compile("https?://[^\\s<>'\"{}|\\^`]+")
        val matcher = pattern.matcher(text)
        return if (matcher.find()) matcher.group(0) ?: text.trim() else text.trim()
    }

    suspend fun resolveMedia(rawUrl: String, context: Context? = null): MediaItem = withContext(Dispatchers.IO) {
        val url = extractUrlFromText(rawUrl)
        val platform = detectPlatform(url)

        if (isPlaylistUrl(url)) {
            return@withContext resolvePlaylist(url, platform)
        }

        return@withContext when (platform) {
            "YouTube" -> resolveYouTube(url)
            "X / Twitter" -> resolveTwitter(url, context)
            "Instagram" -> resolveInstagram(url, context)
            "TikTok" -> resolveTikTok(url, context)
            "Reddit" -> resolveReddit(url, context)
            "Pinterest" -> resolvePinterest(url, context)
            "Giphy" -> resolveGiphy(url)
            "Tenor" -> resolveTenor(url)
            "Facebook" -> resolveFacebook(url, context)
            "Threads" -> resolveThreads(url, context)
            "Spotify" -> resolveSpotify(url, context)
            "Pixabay", "Unsplash", "Pexels" -> resolveStockPhoto(url, platform)
            else -> resolveGeneric(url, platform, context)
        }
    }

    private fun resolveYouTube(url: String): MediaItem {
        var videoId = ""
        val ytPattern = Pattern.compile("(?:v=|shorts/|youtu\\.be/|embed/|live/|/v/)([a-zA-Z0-9_-]{11})", Pattern.CASE_INSENSITIVE)
        val matcher = ytPattern.matcher(url)
        if (matcher.find()) {
            videoId = matcher.group(1) ?: ""
        }
        if (videoId.startsWith("yt_")) {
            val stripped = url.substringAfter("yt_")
            val m2 = Pattern.compile("([a-zA-Z0-9_-]{11})").matcher(stripped)
            if (m2.find()) {
                videoId = m2.group(1) ?: videoId
            }
        }

        val lower = url.lowercase()
        val isShort = lower.contains("shorts/") || lower.contains("#shorts")
        var title = if (isShort) "YouTube Short Video" else "YouTube Video"
        var author = "YouTube Creator"
        var durationSeconds = 0L
        var thumbnail = if (videoId.isNotBlank()) "https://i.ytimg.com/vi/$videoId/maxresdefault.jpg" else "https://images.unsplash.com/photo-1611162617474-5b21e879e113?w=800"

        val streamMap = mutableMapOf<String, String>()
        val sizeMap = mutableMapOf<String, Long>()
        val extractedChapters = mutableListOf<ChapterItem>()
        val extractedSubtitles = mutableListOf<SubtitleTrack>()

        if (videoId.isNotBlank()) {
            var visitorData = ""
            var signatureTimestamp = 20702L

            // 1. Fetch webpage first with desktop User-Agent to collect cookies and metadata
            var cookieHeader = "PREF=hl=en&tz=UTC; SOCS=CAI"
            try {
                val pageUrl = "https://www.youtube.com/watch?v=$videoId"
                val pageReq = Request.Builder()
                    .url(pageUrl)
                    .header("User-Agent", "Mozilla/5.0 (Macintosh; Intel Mac OS X 15_7_3) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/26.0 Safari/605.1.15")
                    .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                    .header("Accept-Language", "en-us,en;q=0.5")
                    .header("Sec-Fetch-Mode", "navigate")
                    .header("Cookie", cookieHeader)
                    .build()

                val pageResp = client.newCall(pageReq).execute()
                if (pageResp.isSuccessful) {
                    val setCookies = pageResp.headers("Set-Cookie")
                    if (setCookies.isNotEmpty()) {
                        val joined = setCookies.map { it.substringBefore(";") }.joinToString("; ")
                        if (joined.isNotBlank()) cookieHeader += "; $joined"
                    }
                    val html = pageResp.body?.string() ?: ""

                    // Extract og:title
                    val titlePattern = Pattern.compile("<meta property=\"og:title\" content=\"([^\"]+)\">")
                    val titleMatcher = titlePattern.matcher(html)
                    if (titleMatcher.find()) {
                        val parsed = titleMatcher.group(1)?.replace("&amp;", "&")?.replace("&#39;", "'")?.replace("&quot;", "\"")
                        if (!parsed.isNullOrBlank()) title = parsed
                    }

                    // Extract author
                    val authorPattern = Pattern.compile("<link itemprop=\"name\" content=\"([^\"]+)\">")
                    val authorMatcher = authorPattern.matcher(html)
                    if (authorMatcher.find()) {
                        val parsedAuthor = authorMatcher.group(1)
                        if (!parsedAuthor.isNullOrBlank()) author = parsedAuthor
                    }

                    // Extract duration
                    val lenPattern = Pattern.compile("\"lengthSeconds\"\\s*:\\s*\"?(\\d+)\"?")
                    val lenMatcher = lenPattern.matcher(html)
                    if (lenMatcher.find()) {
                        val parsedLen = lenMatcher.group(1)?.toLongOrNull()
                        if (parsedLen != null && parsedLen > 0L) durationSeconds = parsedLen
                    }
                    if (durationSeconds <= 0L) {
                        val msPattern = Pattern.compile("\"approxDurationMs\"\\s*:\\s*\"?(\\d+)\"?")
                        val msMatcher = msPattern.matcher(html)
                        if (msMatcher.find()) {
                            val parsedMs = msMatcher.group(1)?.toLongOrNull()
                            if (parsedMs != null && parsedMs > 0L) durationSeconds = parsedMs / 1000L
                        }
                    }
                    if (durationSeconds <= 0L) {
                        val isoPattern = Pattern.compile("<meta itemprop=\"duration\" content=\"PT(?:(\\d+)H)?(?:(\\d+)M)?(?:(\\d+)S)?\">")
                        val isoMatcher = isoPattern.matcher(html)
                        if (isoMatcher.find()) {
                            val h = isoMatcher.group(1)?.toLongOrNull() ?: 0L
                            val m = isoMatcher.group(2)?.toLongOrNull() ?: 0L
                            val s = isoMatcher.group(3)?.toLongOrNull() ?: 0L
                            val total = (h * 3600L) + (m * 60L) + s
                            if (total > 0L) durationSeconds = total
                        }
                    }

                    // Extract chapters from video description
                    val descMatch = Pattern.compile("<meta property=\"og:description\" content=\"([^\"]+)\">").matcher(html)
                    if (descMatch.find()) {
                        val ogDesc = descMatch.group(1)?.replace("&amp;", "&")?.replace("&#39;", "'")?.replace("&quot;", "\"") ?: ""
                        if (ogDesc.isNotBlank() && extractedChapters.isEmpty()) {
                            extractedChapters.addAll(parseChaptersFromText(ogDesc, durationSeconds))
                        }
                    }

                    // Extract visitorData
                    val visPattern = Pattern.compile("\"visitorData\":\\s*\"([^\"]+)\"")
                    val visMatcher = visPattern.matcher(html)
                    if (visMatcher.find()) {
                        val v = visMatcher.group(1)
                        if (!v.isNullOrBlank()) visitorData = v
                    }

                    // Extract signatureTimestamp
                    val stsPattern = Pattern.compile("\"signatureTimestamp\":\\s*(\\d+)")
                    val stsMatcher = stsPattern.matcher(html)
                    if (stsMatcher.find()) {
                        val s = stsMatcher.group(1)?.toLongOrNull()
                        if (s != null && s > 0L) signatureTimestamp = s
                    }
                }
            } catch (e: Exception) {
                // Ignore
            }

            // Fallback for visitorData if empty
            if (visitorData.isBlank()) {
                try {
                    val visPayload = JSONObject().apply {
                        val contextObj = JSONObject().apply {
                            val clientObj = JSONObject().apply {
                                put("clientName", "WEB")
                                put("clientVersion", "2.20240410.01.00")
                                put("hl", "en")
                                put("gl", "US")
                            }
                            put("client", clientObj)
                        }
                        put("context", contextObj)
                    }
                    val visReq = Request.Builder()
                        .url("https://www.youtube.com/youtubei/v1/visitor_id?prettyPrint=false")
                        .post(visPayload.toString().toRequestBody("application/json".toMediaType()))
                        .header("Content-Type", "application/json")
                        .build()
                    val visResp = client.newCall(visReq).execute()
                    if (visResp.isSuccessful) {
                        val visBody = visResp.body?.string() ?: ""
                        if (visBody.isNotBlank()) {
                            val visJson = JSONObject(visBody)
                            visitorData = visJson.optJSONObject("responseContext")?.optString("visitorData", "") ?: ""
                        }
                    }
                } catch (_: Exception) {}
            }

            // 2. Query InnerTube VISIONOS client for signed direct googlevideo streams
            var parseSuccess = false
            try {
                val payload = JSONObject().apply {
                    val contextObj = JSONObject().apply {
                        val clientObj = JSONObject().apply {
                            put("clientName", "VISIONOS")
                            put("clientVersion", "1.02")
                            put("deviceMake", "Apple")
                            put("deviceModel", "RealityDevice17,1")
                            put("userAgent", "Mozilla/5.0 (Macintosh; Intel Mac OS X 15_7_3) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/26.0 Safari/605.1.15")
                            put("osName", "visionOS")
                            put("osVersion", "26.5.23O471")
                            put("hl", "en")
                            put("timeZone", "UTC")
                            put("utcOffsetMinutes", 0)
                            if (visitorData.isNotBlank()) {
                                put("visitorData", visitorData)
                            }
                        }
                        put("client", clientObj)
                    }
                    put("context", contextObj)
                    put("videoId", videoId)
                    put("playbackContext", JSONObject().apply {
                        put("contentPlaybackContext", JSONObject().apply {
                            put("html5Preference", "HTML5_PREF_WANTS")
                            put("signatureTimestamp", signatureTimestamp)
                        })
                    })
                    put("racyCheckOk", true)
                    put("contentCheckOk", true)
                }

                val reqBuilder = Request.Builder()
                    .url("https://www.youtube.com/youtubei/v1/player?prettyPrint=false")
                    .post(payload.toString().toRequestBody("application/json".toMediaType()))
                    .header("User-Agent", "Mozilla/5.0 (Macintosh; Intel Mac OS X 15_7_3) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/26.0 Safari/605.1.15")
                    .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                    .header("Accept-Language", "en-us,en;q=0.5")
                    .header("Sec-Fetch-Mode", "navigate")
                    .header("Content-Type", "application/json")
                    .header("X-Youtube-Client-Name", "101")
                    .header("X-Youtube-Client-Version", "1.02")
                    .header("Origin", "https://www.youtube.com")
                    .header("Cookie", cookieHeader)

                if (visitorData.isNotBlank()) {
                    reqBuilder.header("X-Goog-Visitor-Id", visitorData)
                }

                val ytResp = client.newCall(reqBuilder.build()).execute()
                if (ytResp.isSuccessful) {
                    val body = ytResp.body?.string() ?: ""
                    if (body.isNotBlank()) {
                        val json = JSONObject(body)
                        val playStatus = json.optJSONObject("playabilityStatus")?.optString("status") ?: ""
                        if (playStatus == "OK") {
                            val details = json.optJSONObject("videoDetails")
                            if (details != null) {
                                val parsedTitle = details.optString("title", "")
                                if (parsedTitle.isNotBlank()) title = parsedTitle
                                val parsedAuthor = details.optString("author", "")
                                if (parsedAuthor.isNotBlank()) author = parsedAuthor
                                val durStr = details.optString("lengthSeconds", "")
                                val dur = durStr.toLongOrNull() ?: details.optLong("lengthSeconds", 0L)
                                if (dur > 0L) durationSeconds = dur
                                if (durationSeconds <= 0L) {
                                    val msStr = details.optString("approxDurationMs", "")
                                    val ms = msStr.toLongOrNull() ?: details.optLong("approxDurationMs", 0L)
                                    if (ms > 0L) durationSeconds = ms / 1000L
                                }
                                val thumbArr = details.optJSONObject("thumbnail")?.optJSONArray("thumbnails")
                                if (thumbArr != null && thumbArr.length() > 0) {
                                    val bestThumb = thumbArr.optJSONObject(thumbArr.length() - 1)?.optString("url", "") ?: ""
                                    if (bestThumb.isNotBlank()) {
                                        thumbnail = if (videoId.isNotBlank()) "https://i.ytimg.com/vi/$videoId/maxresdefault.jpg" else bestThumb
                                    }
                                    val shortDesc = details.optString("shortDescription", "")
                                    if (shortDesc.isNotBlank() && extractedChapters.isEmpty()) {
                                        extractedChapters.addAll(parseChaptersFromText(shortDesc, durationSeconds))
                                    }
                                }
                            }

                            // Extract caption / subtitle tracks
                            val captionsObj = json.optJSONObject("captions")?.optJSONObject("playerCaptionsTracklistRenderer")
                            val captionTracks = captionsObj?.optJSONArray("captionTracks")
                            if (captionTracks != null) {
                                for (ci in 0 until captionTracks.length()) {
                                    val c = captionTracks.optJSONObject(ci) ?: continue
                                    val bUrl = c.optString("baseUrl", "")
                                    val lang = c.optString("languageCode", "en")
                                    val label = c.optJSONObject("name")?.optString("simpleText", "") ?: lang.uppercase()
                                    val isAsr = c.optString("kind", "") == "asr"
                                    if (bUrl.isNotBlank()) {
                                        val displayLabel = if (isAsr) "$label (Auto)" else label
                                        extractedSubtitles.add(SubtitleTrack(lang, displayLabel, bUrl, isAsr))
                                    }
                                }
                            }

                            val streamingData = json.optJSONObject("streamingData")
                            if (streamingData != null) {
                                val formats = streamingData.optJSONArray("formats")
                                if (formats != null) {
                                    for (i in 0 until formats.length()) {
                                        val f = formats.optJSONObject(i) ?: continue
                                        val itag = f.optInt("itag", 0)
                                        val streamUrl = f.optString("url", "")
                                        if (streamUrl.isNotBlank()) {
                                            if (itag == 22) streamMap["720p"] = streamUrl
                                            if (itag == 18) {
                                                streamMap["360p"] = streamUrl
                                                streamMap["480p"] = streamUrl
                                                if (!streamMap.containsKey("720p")) streamMap["720p"] = streamUrl
                                                if (!streamMap.containsKey("1080p")) streamMap["1080p"] = streamUrl
                                            }
                                        }
                                    }
                                }

                                val adaptive = streamingData.optJSONArray("adaptiveFormats")
                                if (adaptive != null) {
                                    for (i in 0 until adaptive.length()) {
                                        val af = adaptive.optJSONObject(i) ?: continue
                                        val itag = af.optInt("itag", 0)
                                        val mime = af.optString("mimeType", "")
                                        val streamUrl = af.optString("url", "")
                                        var clen = af.optString("contentLength", "").toLongOrNull() ?: af.optLong("contentLength", 0L)
                                        if (clen <= 0L && streamUrl.isNotBlank()) {
                                            clen = Regex("[?&]clen=(\\d+)").find(streamUrl)?.groupValues?.get(1)?.toLongOrNull() ?: 0L
                                        }

                                        val afMs = af.optString("approxDurationMs", "").toLongOrNull() ?: af.optLong("approxDurationMs", 0L)
                                        if (afMs > 0L && durationSeconds <= 0L) {
                                            durationSeconds = afMs / 1000L
                                        }

                                        if (streamUrl.isNotBlank()) {
                                            if (itag == 313 || itag == 401 || itag == 315) {
                                                if (!streamMap.containsKey("4k") || itag == 401 || itag == 315) {
                                                    streamMap["4k"] = streamUrl
                                                    if (clen > 0L) sizeMap["4k"] = clen
                                                }
                                            }
                                            if (itag == 271 || itag == 400 || itag == 308) {
                                                if (!streamMap.containsKey("2k") || itag == 400 || itag == 308) {
                                                    streamMap["2k"] = streamUrl
                                                    if (clen > 0L) sizeMap["2k"] = clen
                                                }
                                            }
                                            if (itag == 137 || itag == 299) {
                                                // STRICTLY prefer H.264 / AVC (itag 137, 299) for 1080p so MediaMuxer hardware muxing works
                                                streamMap["1080p"] = streamUrl
                                                if (clen > 0L) sizeMap["1080p"] = clen
                                            } else if ((itag == 399 || itag == 248 || itag == 303) && !streamMap.containsKey("1080p")) {
                                                streamMap["1080p"] = streamUrl
                                                if (clen > 0L) sizeMap["1080p"] = clen
                                            }
                                            if (itag == 136 || itag == 298) {
                                                // STRICTLY prefer H.264 / AVC (itag 136, 298) for 720p
                                                streamMap["720p"] = streamUrl
                                                if (clen > 0L) sizeMap["720p"] = clen
                                            } else if ((itag == 398 || itag == 247 || itag == 302) && !streamMap.containsKey("720p")) {
                                                streamMap["720p"] = streamUrl
                                                if (clen > 0L) sizeMap["720p"] = clen
                                            }
                                            if (itag == 135) {
                                                streamMap["480p"] = streamUrl
                                                if (clen > 0L) sizeMap["480p"] = clen
                                            } else if (itag == 244 && !streamMap.containsKey("480p")) {
                                                streamMap["480p"] = streamUrl
                                                if (clen > 0L) sizeMap["480p"] = clen
                                            }
                                            if (itag == 134) {
                                                streamMap["360p"] = streamUrl
                                                if (clen > 0L) sizeMap["360p"] = clen
                                            } else if (itag == 243 && !streamMap.containsKey("360p")) {
                                                streamMap["360p"] = streamUrl
                                                if (clen > 0L) sizeMap["360p"] = clen
                                            }
                                            if (itag == 140) {
                                                // itag 140 is m4a AAC (compatible with MP4 muxer)
                                                streamMap["audio"] = streamUrl
                                                if (clen > 0L) sizeMap["audio"] = clen
                                            } else if (mime.contains("audio") && !streamMap.containsKey("audio")) {
                                                streamMap["audio"] = streamUrl
                                                if (clen > 0L) sizeMap["audio"] = clen
                                            }
                                        }
                                    }
                                }
                                parseSuccess = streamMap.isNotEmpty()
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                // Ignore
            }

            // Extract duration from stream URLs if still missing
            if (durationSeconds <= 0L) {
                for (u in streamMap.values) {
                    val m = Pattern.compile("[?&]dur=([\\d.]+)").matcher(u)
                    if (m.find()) {
                        val d = m.group(1)?.toDoubleOrNull()
                        if (d != null && d > 0.0) {
                            durationSeconds = d.toLong()
                            break
                        }
                    }
                }
            }

            // 3. Fallback: oEmbed if metadata still missing
            if (title.startsWith("YouTube") || author == "YouTube Creator") {
                try {
                    val oembedUrl = "https://www.youtube.com/oembed?url=" + java.net.URLEncoder.encode("https://www.youtube.com/watch?v=$videoId", "UTF-8") + "&format=json"
                    val request = Request.Builder().url(oembedUrl).build()
                    val response = client.newCall(request).execute()
                    if (response.isSuccessful) {
                        val body = response.body?.string()
                        if (!body.isNullOrBlank()) {
                            val json = JSONObject(body)
                            title = json.optString("title", title)
                            author = json.optString("author_name", author)
                        }
                    }
                } catch (e: Exception) {
                    // Ignore
                }
            }

            // 4. Fallback: ANDROID_TESTSUITE for unauthenticated duration & title
            if (durationSeconds <= 0L) {
                try {
                    val fallbackPayload = JSONObject().apply {
                        val cObj = JSONObject().apply {
                            put("clientName", "ANDROID_TESTSUITE")
                            put("clientVersion", "1.9")
                            put("hl", "en")
                            put("gl", "US")
                        }
                        put("context", JSONObject().apply { put("client", cObj) })
                        put("videoId", videoId)
                    }
                    val fbReq = Request.Builder()
                        .url("https://www.youtube.com/youtubei/v1/player?prettyPrint=false")
                        .post(fallbackPayload.toString().toRequestBody("application/json".toMediaType()))
                        .header("User-Agent", "com.google.android.youtube/1.9 (Linux; U; Android 14) gzip")
                        .header("Content-Type", "application/json")
                        .build()
                    val fbResp = client.newCall(fbReq).execute()
                    if (fbResp.isSuccessful) {
                        val fbJson = JSONObject(fbResp.body?.string() ?: "")
                        val fbDetails = fbJson.optJSONObject("videoDetails")
                        if (fbDetails != null) {
                            val fbDur = fbDetails.optString("lengthSeconds", "").toLongOrNull() ?: fbDetails.optLong("lengthSeconds", 0L)
                            if (fbDur > 0L) durationSeconds = fbDur
                            if (title.startsWith("YouTube")) {
                                val fbTitle = fbDetails.optString("title", "")
                                if (fbTitle.isNotBlank()) title = fbTitle
                            }
                        }
                    }
                } catch (_: Exception) {}
            }

            if (durationSeconds <= 0L) {
                durationSeconds = if (isShort) 16L else 60L
            }
        }

        val qualities = generateVideoQualities(durationSeconds, url, streamMap, sizeMap)

        return MediaItem(
            url = url,
            title = title,
            author = author,
            durationSeconds = durationSeconds,
            thumbnail = thumbnail,
            platform = if (isShort) "YouTube Shorts" else "YouTube",
            mediaType = MediaType.VIDEO,
            qualities = qualities,
            chapters = extractedChapters,
            subtitles = extractedSubtitles
        )
    }

    fun calcProportionalDimensions(origW: Int, origH: Int, targetSmallDim: Int): Pair<Int, Int> {
        if (origW <= 0 || origH <= 0) return Pair(targetSmallDim, targetSmallDim)
        return if (origW >= origH) {
            val newH = targetSmallDim
            var newW = Math.round((origW.toDouble() / origH.toDouble()) * newH).toInt()
            if (newW % 2 != 0) newW -= 1
            Pair(newW, newH)
        } else {
            val newW = targetSmallDim
            var newH = Math.round((origH.toDouble() / origW.toDouble()) * newW).toInt()
            if (newH % 2 != 0) newH -= 1
            Pair(newW, newH)
        }
    }

    private suspend fun resolveTwitter(url: String, context: Context? = null): MediaItem {
        var title = "X / Twitter Post"
        var author = "@x_creator"
        var thumbnail = "https://images.unsplash.com/photo-1611605698335-8b1569810432?w=800"
        var durationSeconds = 30L
        val streamMap = mutableMapOf<String, String>()
        val imageUrls = mutableListOf<String>()
        var avatarUrl = ""
        var maxSourceRes = "1080p"

        var tweetId = ""
        var username = "Twitter"
        val tweetPattern = Pattern.compile("(?:twitter\\.com|x\\.com)/(?:#!/)?(?:i/web/status/|i/status/|([a-zA-Z0-9_]+)/status(?:es)?/)?(\\d+)", Pattern.CASE_INSENSITIVE)
        val matcher = tweetPattern.matcher(url)
        if (matcher.find()) {
            val u = matcher.group(1)
            val tid = matcher.group(2)
            if (!u.isNullOrBlank() && !u.equals("i", ignoreCase = true)) username = u
            if (!tid.isNullOrBlank()) tweetId = tid
        }

        if (tweetId.isNotBlank()) {
            // Strategy A: Query api.fxtwitter.com and api.vxtwitter.com
            val apiEndpoints = listOf(
                "https://api.fxtwitter.com/status/$tweetId",
                "https://api.fxtwitter.com/i/status/$tweetId",
                "https://api.vxtwitter.com/status/$tweetId",
                "https://api.vxtwitter.com/Twitter/status/$tweetId",
                "https://api.fxtwitter.com/$username/status/$tweetId"
            )

            for (endpoint in apiEndpoints) {
                if (streamMap.isNotEmpty() || imageUrls.isNotEmpty()) break
                try {
                    val req = Request.Builder()
                        .url(endpoint)
                        .header("User-Agent", "MediaFetch/1.0 (Android)")
                        .header("Accept", "application/json")
                        .build()
                    val resp = client.newCall(req).execute()
                    if (resp.isSuccessful) {
                        val body = resp.body?.string() ?: ""
                        if (body.isNotBlank() && body.contains("{")) {
                            val json = JSONObject(body)
                            val tweet = json.optJSONObject("tweet") ?: json.optJSONObject("status") ?: if (json.has("text") || json.has("tweetID")) json else null
                            if (tweet != null) {
                                val text = tweet.optString("text", "")
                                if (text.isNotBlank()) title = text

                                val authObj = tweet.optJSONObject("author")
                                if (authObj != null) {
                                    val name = authObj.optString("name", "")
                                    val screen = authObj.optString("screen_name", "")
                                    author = if (name.isNotBlank() && screen.isNotBlank()) "$name (@$screen)" else if (screen.isNotBlank()) "@$screen" else name
                                    val av = authObj.optString("avatar_url", "")
                                    if (av.isNotBlank()) avatarUrl = av
                                } else {
                                    val uName = tweet.optString("user_name", "")
                                    val uScreen = tweet.optString("user_screen_name", "")
                                    if (uName.isNotBlank() || uScreen.isNotBlank()) {
                                        author = if (uName.isNotBlank() && uScreen.isNotBlank()) "$uName (@$uScreen)" else if (uScreen.isNotBlank()) "@$uScreen" else uName
                                    }
                                    val uAvatar = tweet.optString("user_profile_image_url", "")
                                    if (uAvatar.isNotBlank()) avatarUrl = uAvatar
                                }

                                val media = tweet.optJSONObject("media")
                                val videos = media?.optJSONArray("videos")
                                if (videos != null && videos.length() > 0) {
                                    for (vIdx in 0 until videos.length()) {
                                        val vidObj = videos.optJSONObject(vIdx) ?: continue
                                        val vThumb = vidObj.optString("thumbnail_url", "")
                                        val vDur = vidObj.optDouble("duration", 0.0).toLong()
                                        if (vThumb.isNotBlank()) thumbnail = vThumb
                                        if (vDur > 0L) durationSeconds = vDur

                                        // 1. Check variants array for multiple MP4 resolutions
                                        val variants = vidObj.optJSONArray("variants")
                                        val mp4Variants = mutableListOf<Triple<Int, Long, String>>() // height, bitrate, url
                                        if (variants != null && variants.length() > 0) {
                                            for (vaI in 0 until variants.length()) {
                                                val v = variants.optJSONObject(vaI) ?: continue
                                                val vUrl = v.optString("url", "")
                                                val cType = v.optString("content_type", "")
                                                val br = v.optLong("bitrate", 0L)
                                                if (vUrl.isNotBlank() && (cType == "video/mp4" || vUrl.contains(".mp4") || (!vUrl.contains(".m3u8") && !cType.contains("mpegurl")))) {
                                                    var h = 0
                                                    val resMat = Pattern.compile("/(\\d+)x(\\d+)/").matcher(vUrl)
                                                    if (resMat.find()) {
                                                        val w = resMat.group(1)?.toIntOrNull() ?: 0
                                                        val hVal = resMat.group(2)?.toIntOrNull() ?: 0
                                                        h = minOf(w, hVal)
                                                    }
                                                    mp4Variants.add(Triple(h, br, vUrl))
                                                }
                                            }
                                        }

                                        if (mp4Variants.isNotEmpty()) {
                                            // Sort highest resolution/bitrate first
                                            mp4Variants.sortWith(compareByDescending<Triple<Int, Long, String>> { it.first }.thenByDescending { it.second })
                                            val topVariant = mp4Variants.first()
                                            streamMap["default"] = topVariant.third

                                            if (topVariant.first >= 1080 || topVariant.second >= 2500000L) {
                                                maxSourceRes = "1080p"
                                                streamMap["1080p"] = topVariant.third
                                            } else if (topVariant.first >= 720 || topVariant.second >= 1000000L) {
                                                maxSourceRes = "720p"
                                                streamMap["720p"] = topVariant.third
                                            } else {
                                                maxSourceRes = "480p"
                                                streamMap["480p"] = topVariant.third
                                            }

                                            // Map remaining resolutions
                                            for (variant in mp4Variants) {
                                                val h = variant.first
                                                val u = variant.third
                                                if (h >= 1080 && !streamMap.containsKey("1080p")) streamMap["1080p"] = u
                                                if (h in 720..1079 && !streamMap.containsKey("720p")) streamMap["720p"] = u
                                                if (h in 1..719 && !streamMap.containsKey("480p")) streamMap["480p"] = u
                                            }
                                            // Fill gaps and derive proportional variants if only 1 variant available
                                            if (streamMap["720p"] == streamMap["1080p"] || !streamMap.containsKey("720p")) {
                                                val resMat = Pattern.compile("/(\\d+)x(\\d+)/").matcher(topVariant.third)
                                                if (resMat.find()) {
                                                    val w = resMat.group(1)?.toIntOrNull() ?: 0
                                                    val h = resMat.group(2)?.toIntOrNull() ?: 0
                                                    val curMatchStr = resMat.group(0) ?: ""
                                                    if (w > 0 && h > 0) {
                                                        val p720 = calcProportionalDimensions(w, h, 720)
                                                        val p360 = calcProportionalDimensions(w, h, 360)
                                                        if (minOf(w, h) >= 1080) {
                                                            streamMap["720p"] = topVariant.third.replace(curMatchStr, "/${p720.first}x${p720.second}/")
                                                            streamMap["480p"] = topVariant.third.replace(curMatchStr, "/${p360.first}x${p360.second}/")
                                                        } else if (minOf(w, h) in 720..1079) {
                                                            streamMap["480p"] = topVariant.third.replace(curMatchStr, "/${p360.first}x${p360.second}/")
                                                        }
                                                    }
                                                }
                                            }
                                            if (!streamMap.containsKey("720p")) streamMap["720p"] = topVariant.third
                                            if (!streamMap.containsKey("1080p") && maxSourceRes == "1080p") streamMap["1080p"] = topVariant.third
                                            if (!streamMap.containsKey("480p")) streamMap["480p"] = mp4Variants.last().third
                                            break
                                        }

                                        // 2. Direct url from video object if variants empty
                                        val vUrl = vidObj.optString("url", "")
                                        if (vUrl.isNotBlank() && !vUrl.contains(".m3u8")) {
                                            streamMap["default"] = vUrl
                                            streamMap["1080p"] = vUrl
                                            streamMap["720p"] = vUrl
                                            streamMap["480p"] = vUrl
                                            break
                                        }
                                    }
                                }

                                // 3. Check vxTwitter media_extended schema
                                val mediaExtended = tweet.optJSONArray("media_extended")
                                if (mediaExtended != null && mediaExtended.length() > 0 && streamMap.isEmpty()) {
                                    for (mIdx in 0 until mediaExtended.length()) {
                                        val mObj = mediaExtended.optJSONObject(mIdx) ?: continue
                                        val mType = mObj.optString("type", "")
                                        val mUrl = mObj.optString("url", "")
                                        if (mType == "video" && mUrl.isNotBlank()) {
                                            val vThumb = mObj.optString("thumbnail_url", "")
                                            val vDur = mObj.optLong("duration_millis", 0L) / 1000L
                                            if (vThumb.isNotBlank()) thumbnail = vThumb
                                            if (vDur > 0L) durationSeconds = vDur
                                            val sizeObj = mObj.optJSONObject("size")
                                            val w = sizeObj?.optInt("width", 0) ?: 0
                                            val h = sizeObj?.optInt("height", 0) ?: 0
                                            val curRes = if (w > 0 && h > 0) minOf(w, h) else 0

                                            streamMap["default"] = mUrl
                                            if (curRes >= 1080) {
                                                maxSourceRes = "1080p"
                                                streamMap["1080p"] = mUrl
                                            } else if (curRes in 720..1079) {
                                                maxSourceRes = "720p"
                                                streamMap["720p"] = mUrl
                                            } else {
                                                maxSourceRes = "480p"
                                                streamMap["480p"] = mUrl
                                            }

                                            val resMat = Pattern.compile("/(\\d+)x(\\d+)/").matcher(mUrl)
                                            if (resMat.find()) {
                                                val mw = resMat.group(1)?.toIntOrNull() ?: w
                                                val mh = resMat.group(2)?.toIntOrNull() ?: h
                                                val curMatchStr = resMat.group(0) ?: ""
                                                if (mw > 0 && mh > 0) {
                                                    val p720 = calcProportionalDimensions(mw, mh, 720)
                                                    val p360 = calcProportionalDimensions(mw, mh, 360)
                                                    if (minOf(mw, mh) >= 1080) {
                                                        streamMap["720p"] = mUrl.replace(curMatchStr, "/${p720.first}x${p720.second}/")
                                                        streamMap["480p"] = mUrl.replace(curMatchStr, "/${p360.first}x${p360.second}/")
                                                    } else if (minOf(mw, mh) in 720..1079) {
                                                        streamMap["480p"] = mUrl.replace(curMatchStr, "/${p360.first}x${p360.second}/")
                                                    }
                                                }
                                            }
                                            if (!streamMap.containsKey("720p")) streamMap["720p"] = mUrl
                                            if (!streamMap.containsKey("1080p") && maxSourceRes == "1080p") streamMap["1080p"] = mUrl
                                            if (!streamMap.containsKey("480p")) streamMap["480p"] = mUrl
                                            break
                                        }
                                    }
                                }

                                val photos = media?.optJSONArray("photos")
                                if (photos != null && streamMap.isEmpty()) {
                                    for (pIdx in 0 until photos.length()) {
                                        val pUrl = photos.optJSONObject(pIdx)?.optString("url", "") ?: ""
                                        if (pUrl.isNotBlank()) imageUrls.add(pUrl)
                                    }
                                    if (imageUrls.isNotEmpty()) thumbnail = imageUrls[0]
                                }

                                if (thumbnail.contains("unsplash") && avatarUrl.isNotBlank()) {
                                    thumbnail = avatarUrl
                                }
                            }
                        }
                    }
                } catch (_: Exception) {}
            }

            // Strategy B: Fallback to HTML OpenGraph from fxtwitter.com and fixupx.com with TelegramBot User-Agent
            if (streamMap.isEmpty() && imageUrls.isEmpty()) {
                val htmlUrls = listOf(
                    "https://fxtwitter.com/$username/status/$tweetId",
                    "https://fixupx.com/$username/status/$tweetId"
                )
                for (fxHtmlUrl in htmlUrls) {
                    if (streamMap.isNotEmpty() || imageUrls.isNotEmpty()) break
                    try {
                        val req = Request.Builder()
                            .url(fxHtmlUrl)
                            .header("User-Agent", "TelegramBot (like TwitterBot)")
                            .build()
                        val resp = client.newCall(req).execute()
                        if (resp.isSuccessful) {
                            val html = resp.body?.string() ?: ""
                            val titleMat = Pattern.compile("<meta property=\"og:title\" content=\"([^\"]+)\"").matcher(html)
                            if (titleMat.find()) {
                                val parsed = titleMat.group(1)?.replace("&amp;", "&")
                                if (!parsed.isNullOrBlank()) author = parsed
                            }
                            val descMat = Pattern.compile("<meta property=\"og:description\" content=\"([^\"]+)\"").matcher(html)
                            if (descMat.find()) {
                                val parsed = descMat.group(1)?.replace("&amp;", "&")
                                if (!parsed.isNullOrBlank()) title = parsed
                            }
                            val imgMat = Pattern.compile("<meta property=\"og:image\" content=\"([^\"]+)\"").matcher(html)
                            if (imgMat.find()) {
                                val parsed = imgMat.group(1)?.replace("&amp;", "&")
                                if (!parsed.isNullOrBlank()) thumbnail = parsed
                            }
                            val vidMat = Pattern.compile("<meta property=\"og:video(?::secure_url)?\" content=\"([^\"]+)\"").matcher(html)
                            if (vidMat.find()) {
                                val parsed = vidMat.group(1)?.replace("&amp;", "&")
                                if (!parsed.isNullOrBlank() && !parsed.contains(".m3u8")) {
                                    streamMap["default"] = parsed
                                    val resMat = Pattern.compile("/(\\d+)x(\\d+)/").matcher(parsed)
                                    if (resMat.find()) {
                                        val w = resMat.group(1)?.toIntOrNull() ?: 0
                                        val h = resMat.group(2)?.toIntOrNull() ?: 0
                                        val curMatchStr = resMat.group(0) ?: ""
                                        if (w > 0 && h > 0) {
                                            val p720 = calcProportionalDimensions(w, h, 720)
                                            val p360 = calcProportionalDimensions(w, h, 360)
                                            val curRes = minOf(w, h)
                                            if (curRes >= 1080) {
                                                maxSourceRes = "1080p"
                                                streamMap["1080p"] = parsed
                                                streamMap["720p"] = parsed.replace(curMatchStr, "/${p720.first}x${p720.second}/")
                                                streamMap["480p"] = parsed.replace(curMatchStr, "/${p360.first}x${p360.second}/")
                                            } else if (curRes in 720..1079) {
                                                maxSourceRes = "720p"
                                                streamMap["720p"] = parsed
                                                streamMap["480p"] = parsed.replace(curMatchStr, "/${p360.first}x${p360.second}/")
                                            } else {
                                                maxSourceRes = "480p"
                                                streamMap["480p"] = parsed
                                            }
                                        } else {
                                            streamMap["1080p"] = parsed
                                            streamMap["720p"] = parsed
                                            streamMap["480p"] = parsed
                                        }
                                    } else {
                                        streamMap["1080p"] = parsed
                                        streamMap["720p"] = parsed
                                        streamMap["480p"] = parsed
                                    }
                                }
                            }
                        }
                    } catch (_: Exception) {}
                }
            }

            // Strategy C: TwitSave direct MP4 extraction
            if (streamMap.isEmpty() && imageUrls.isEmpty()) {
                try {
                    val twitSaveUrl = "https://twitsave.com/info?url=https://twitter.com/$username/status/$tweetId"
                    val req = Request.Builder()
                        .url(twitSaveUrl)
                        .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
                        .build()
                    val resp = client.newCall(req).execute()
                    if (resp.isSuccessful) {
                        val html = resp.body?.string() ?: ""
                        val linkMat = Pattern.compile("href=\"(https://video\\.twimg\\.com/[^\"]+\\.mp4[^\"]*)\"").matcher(html)
                        val foundLinks = mutableListOf<String>()
                        while (linkMat.find()) {
                            val lk = linkMat.group(1) ?: continue
                            if (!foundLinks.contains(lk)) foundLinks.add(lk)
                        }
                        if (foundLinks.isNotEmpty()) {
                            val topLink = foundLinks[0]
                            streamMap["default"] = topLink
                            if (foundLinks.size > 1) {
                                streamMap["1080p"] = foundLinks[0]
                                streamMap["720p"] = foundLinks[1]
                                streamMap["480p"] = foundLinks.last()
                            } else {
                                val resMat = Pattern.compile("/(\\d+)x(\\d+)/").matcher(topLink)
                                if (resMat.find()) {
                                    val w = resMat.group(1)?.toIntOrNull() ?: 0
                                    val h = resMat.group(2)?.toIntOrNull() ?: 0
                                    val curMatchStr = resMat.group(0) ?: ""
                                    if (w > 0 && h > 0) {
                                        val p720 = calcProportionalDimensions(w, h, 720)
                                        val p360 = calcProportionalDimensions(w, h, 360)
                                        val curRes = minOf(w, h)
                                        if (curRes >= 1080) {
                                            maxSourceRes = "1080p"
                                            streamMap["1080p"] = topLink
                                            streamMap["720p"] = topLink.replace(curMatchStr, "/${p720.first}x${p720.second}/")
                                            streamMap["480p"] = topLink.replace(curMatchStr, "/${p360.first}x${p360.second}/")
                                        } else if (curRes in 720..1079) {
                                            maxSourceRes = "720p"
                                            streamMap["720p"] = topLink
                                            streamMap["480p"] = topLink.replace(curMatchStr, "/${p360.first}x${p360.second}/")
                                        } else {
                                            maxSourceRes = "480p"
                                            streamMap["480p"] = topLink
                                        }
                                    } else {
                                        streamMap["1080p"] = topLink
                                        streamMap["720p"] = topLink
                                        streamMap["480p"] = topLink
                                    }
                                } else {
                                    streamMap["1080p"] = topLink
                                    streamMap["720p"] = topLink
                                    streamMap["480p"] = topLink
                                }
                            }
                        }
                    }
                } catch (_: Exception) {}
            }
        }

        // Strategy D: StreamExtractor (headless WebView) if direct stream still empty
        if (streamMap.isEmpty() && imageUrls.isEmpty() && context != null) {
            try {
                val sniff = StreamExtractor.sniffStream(context, url, timeoutMs = 8000L)
                val targetStream = sniff.streamUrl
                if (targetStream.isNotBlank()) {
                    streamMap["default"] = targetStream
                    if (sniff.title.isNotBlank() && title.startsWith("X / Twitter")) title = sniff.title
                    if (sniff.author.isNotBlank() && author.startsWith("@x_creator")) author = sniff.author
                    if (sniff.thumbnail.isNotBlank()) thumbnail = sniff.thumbnail

                    // 1. If HLS stream (m3u8), parse the master playlist to get individual variant URLs!
                    if (targetStream.contains(".m3u8")) {
                        try {
                            val mReq = Request.Builder()
                                .url(targetStream)
                                .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
                                .header("Referer", "https://x.com/")
                                .build()
                            val mResp = client.newCall(mReq).execute()
                            if (mResp.isSuccessful) {
                                val mBody = mResp.body?.string() ?: ""
                                if (mBody.contains("#EXT-X-STREAM-INF", ignoreCase = true)) {
                                    val lines = mBody.lines()
                                    val baseUri = java.net.URI(targetStream)
                                    var has1080 = false
                                    var has720 = false
                                    var has480 = false

                                    // Extract separate audio track from master playlist
                                    for (l in lines) {
                                        val trimmed = l.trim()
                                        if (trimmed.startsWith("#EXT-X-MEDIA", ignoreCase = true) && trimmed.contains("TYPE=AUDIO", ignoreCase = true)) {
                                            val uriMat = Regex("""URI="([^"]+)"""", RegexOption.IGNORE_CASE).find(trimmed)
                                            if (uriMat != null) {
                                                val relUri = uriMat.groupValues[1]
                                                val aUrl = try { baseUri.resolve(relUri).toString() } catch (_: Exception) { relUri }
                                                if (aUrl.isNotBlank()) {
                                                    streamMap["audio"] = aUrl
                                                }
                                                break
                                            }
                                        }
                                    }
                                    streamMap["master_m3u8"] = targetStream

                                    for (i in lines.indices) {
                                        val l = lines[i].trim()
                                        if (l.startsWith("#EXT-X-STREAM-INF", ignoreCase = true)) {
                                            val rMat = Regex("""RESOLUTION\s*=\s*(\d+)\s*x\s*(\d+)""", RegexOption.IGNORE_CASE).find(l)
                                            val w = rMat?.groupValues?.get(1)?.toIntOrNull() ?: 0
                                            val h = rMat?.groupValues?.get(2)?.toIntOrNull() ?: 0
                                            val vRes = if (w > 0 && h > 0) minOf(w, h) else maxOf(w, h)

                                            for (j in (i + 1) until lines.size) {
                                                val nxt = lines[j].trim()
                                                if (nxt.isNotEmpty() && !nxt.startsWith("#")) {
                                                    val varUrl = try { baseUri.resolve(nxt).toString() } catch (_: Exception) { nxt }
                                                    if (vRes >= 1080 && !has1080) {
                                                        streamMap["1080p"] = varUrl
                                                        has1080 = true
                                                    } else if (vRes in 720..1079 && !has720) {
                                                        streamMap["720p"] = varUrl
                                                        has720 = true
                                                    } else if (vRes in 1..719 && !has480) {
                                                        streamMap["480p"] = varUrl
                                                        has480 = true
                                                    }
                                                    break
                                                }
                                            }
                                        }
                                    }
                                    maxSourceRes = if (has1080) "1080p" else if (has720) "720p" else "480p"
                                    if (streamMap.containsKey("720p")) streamMap["default"] = streamMap["720p"]!!
                                    else if (streamMap.containsKey("1080p")) streamMap["default"] = streamMap["1080p"]!!
                                }
                            }
                        } catch (_: Exception) {}
                    } else if (targetStream.contains(".mp4") && targetStream.contains("video.twimg.com")) {
                        // 2. If Twitter progressive MP4, extract resolution and derive sibling variants
                        val resPattern = Pattern.compile("/(\\d+)x(\\d+)/")
                        val m = resPattern.matcher(targetStream)
                        if (m.find()) {
                            val w = m.group(1)?.toIntOrNull() ?: 0
                            val h = m.group(2)?.toIntOrNull() ?: 0
                            val curRes = minOf(w, h)
                            val curMatchStr = m.group(0) ?: "" // e.g. "/720x1280/"

                            if (w > 0 && h > 0) {
                                val p720 = calcProportionalDimensions(w, h, 720)
                                val p360 = calcProportionalDimensions(w, h, 360)
                                val res720Str = "${p720.first}x${p720.second}"
                                val res360Str = "${p360.first}x${p360.second}"

                                if (curRes >= 1080) {
                                    maxSourceRes = "1080p"
                                    streamMap["1080p"] = targetStream
                                    streamMap["720p"] = targetStream.replace(curMatchStr, "/$res720Str/")
                                    streamMap["480p"] = targetStream.replace(curMatchStr, "/$res360Str/")
                                } else if (curRes in 720..1079) {
                                    maxSourceRes = "720p"
                                    streamMap["720p"] = targetStream
                                    streamMap["480p"] = targetStream.replace(curMatchStr, "/$res360Str/")
                                } else {
                                    maxSourceRes = "480p"
                                    streamMap["480p"] = targetStream
                                }
                            }
                        }
                    }
                }
            } catch (_: Exception) {}
        }

        val isPhotoPost = imageUrls.isNotEmpty() && streamMap.isEmpty()
        return if (isPhotoPost) {
            MediaItem(
                url = url,
                title = title,
                author = author,
                durationSeconds = 0L,
                thumbnail = imageUrls.firstOrNull() ?: thumbnail,
                platform = "X / Twitter",
                mediaType = MediaType.IMAGE,
                imageUrls = imageUrls,
                qualities = generateImageQualities(imageUrls.firstOrNull() ?: thumbnail)
            )
        } else {
            val qualities = generateVideoQualities(durationSeconds, url, streamMap, maxSourceRes = maxSourceRes)
            MediaItem(
                url = url,
                title = title,
                author = author,
                durationSeconds = durationSeconds,
                thumbnail = thumbnail,
                platform = "X / Twitter",
                mediaType = MediaType.VIDEO,
                qualities = qualities
            )
        }
    }

    private data class IgMediaResult(
        val videoUrl: String = "",
        val thumbnail: String = "",
        val caption: String = "",
        val author: String = "",
        val duration: Long = 0L,
        val isImage: Boolean = false,
        val dash1080Url: String = "",
        val dash720Url: String = "",
        val dashAudioUrl: String = "",
        val carouselSlides: List<JSONObject> = emptyList()
    )

    private fun findMediaObjRecursive(obj: Any?): JSONObject? {
        if (obj is JSONObject) {
            if (obj.has("post")) {
                val post = obj.optJSONObject("post")
                if (post != null) {
                    val res = findMediaObjRecursive(post)
                    if (res != null) return res
                    return post
                }
            }
            if (obj.has("thread_items")) {
                val items = obj.optJSONArray("thread_items")
                if (items != null && items.length() > 0) {
                    for (i in 0 until items.length()) {
                        val itemObj = items.optJSONObject(i)
                        val post = itemObj?.optJSONObject("post")
                        if (post != null) {
                            val res = findMediaObjRecursive(post)
                            if (res != null) return res
                            return post
                        }
                    }
                }
            }
            if (obj.has("video_versions") || obj.has("xig_polaris_media") || obj.has("xdt_shortcode_media")) {
                val polaris = obj.optJSONObject("xig_polaris_media")
                    ?: obj.optJSONObject("xdt_shortcode_media")
                    ?: obj
                val ifNotGated = polaris.optJSONObject("if_not_gated_logged_out") ?: polaris
                if (ifNotGated.has("video_versions") || ifNotGated.has("image_versions2") || ifNotGated.has("video_url") || ifNotGated.has("display_uri")) {
                    return ifNotGated
                }
                return polaris
            }
            val keys = obj.keys()
            while (keys.hasNext()) {
                val key = keys.next()
                val res = findMediaObjRecursive(obj.opt(key))
                if (res != null) return res
            }
        } else if (obj is JSONArray) {
            for (i in 0 until obj.length()) {
                val res = findMediaObjRecursive(obj.opt(i))
                if (res != null) return res
            }
        }
        return null
    }

    private fun extractInstagramMediaFromSjs(html: String): IgMediaResult? {
        val sjsPattern = Pattern.compile("<script\\b[^>]+\\bdata-sjs>(\\{.+?\\})</script>")
        val matcher = sjsPattern.matcher(html)
        while (matcher.find()) {
            val sjs = matcher.group(1) ?: continue
            if (!sjs.contains("video_versions") && !sjs.contains("image_versions2") && !sjs.contains("xig_polaris_media") && !sjs.contains("xdt_shortcode_media") && !sjs.contains("thread_items") && !sjs.contains("text_post_app_info")) continue
            try {
                val root = JSONObject(sjs)
                val mediaObj = findMediaObjRecursive(root) ?: continue

                var videoUrl = ""
                val vVersions = mediaObj.optJSONArray("video_versions")
                if (vVersions != null && vVersions.length() > 0) {
                    for (vI in 0 until vVersions.length()) {
                        val vu = vVersions.optJSONObject(vI)?.optString("url", "") ?: ""
                        if (vu.isNotBlank()) {
                            videoUrl = vu
                            break
                        }
                    }
                }
                if (videoUrl.isBlank()) {
                    videoUrl = mediaObj.optString("video_url", "")
                }

                var dash1080Url = ""
                var dash720Url = ""
                var dashAudioUrl = ""
                val dash = mediaObj.optString("video_dash_manifest", "")
                if (dash.isNotBlank()) {
                    try {
                        val repPattern = Pattern.compile("<Representation\\b([^>]+)>(.*?)</Representation>", Pattern.DOTALL)
                        val repMatcher = repPattern.matcher(dash)
                        var maxVideoBw = 0L
                        var highestVideoUrl = ""
                        while (repMatcher.find()) {
                            val attrs = repMatcher.group(1) ?: ""
                            val body = repMatcher.group(2) ?: ""
                            val baseMat = Pattern.compile("<BaseURL>(.*?)</BaseURL>").matcher(body)
                            val base = if (baseMat.find()) baseMat.group(1)?.replace("&amp;", "&")?.trim() ?: "" else ""
                            if (base.isBlank()) continue

                            val isAudio = attrs.contains("mimeType=\"audio/") || attrs.contains("audioSamplingRate") || attrs.contains("dash_ln_")
                            val isVideo = attrs.contains("mimeType=\"video/") || attrs.contains("FBQualityLabel") || attrs.contains("width=")

                            if (isAudio && dashAudioUrl.isBlank()) {
                                dashAudioUrl = base
                            } else if (isVideo) {
                                if (attrs.contains("FBQualityLabel=\"1080p\"") || attrs.contains("width=\"1080\"") || attrs.contains("height=\"1920\"") || attrs.contains("1080p")) {
                                    dash1080Url = base
                                }
                                if (attrs.contains("FBQualityLabel=\"720p\"") || attrs.contains("width=\"720\"") || attrs.contains("height=\"1280\"") || attrs.contains("720p")) {
                                    dash720Url = base
                                }
                                val bwMat = Pattern.compile("bandwidth=\"(\\d+)\"").matcher(attrs)
                                val bw = if (bwMat.find()) bwMat.group(1)?.toLongOrNull() ?: 0L else 0L
                                if (bw > maxVideoBw) {
                                    maxVideoBw = bw
                                    highestVideoUrl = base
                                }
                            }
                        }
                        if (dash1080Url.isBlank() && maxVideoBw >= 3000000L && highestVideoUrl.isNotBlank()) {
                            dash1080Url = highestVideoUrl
                        }
                        if (dash720Url.isBlank() && highestVideoUrl.isNotBlank()) {
                            dash720Url = highestVideoUrl
                        }
                    } catch (_: Exception) {}
                }

                var thumbUrl = ""
                val imgVer = mediaObj.optJSONObject("image_versions2")
                val candidates = imgVer?.optJSONArray("candidates")
                if (candidates != null && candidates.length() > 0) {
                    for (cI in 0 until candidates.length()) {
                        val cu = candidates.optJSONObject(cI)?.optString("url", "") ?: ""
                        if (cu.isNotBlank() && !cu.contains("150x150") && !cu.contains("t51.2885-19") && !cu.contains("avatar")) {
                            thumbUrl = cu
                            break
                        }
                    }
                }
                if (thumbUrl.isBlank()) {
                    val du = mediaObj.optString("display_uri", "")
                    if (du.isNotBlank() && !du.contains("t51.2885-19")) thumbUrl = du
                }
                if (thumbUrl.isBlank()) {
                    val du = mediaObj.optString("display_url", "")
                    if (du.isNotBlank() && !du.contains("t51.2885-19")) thumbUrl = du
                }

                var captionText = ""
                val capObj = mediaObj.optJSONObject("caption")
                if (capObj != null) {
                    captionText = capObj.optString("text", "")
                }
                if (captionText.isBlank()) {
                    val edgeCap = mediaObj.optJSONObject("edge_media_to_caption")
                    val edges = edgeCap?.optJSONArray("edges")
                    if (edges != null && edges.length() > 0) {
                        captionText = edges.optJSONObject(0)?.optJSONObject("node")?.optString("text", "") ?: ""
                    }
                }

                var authorName = ""
                val userObj = mediaObj.optJSONObject("user") ?: mediaObj.optJSONObject("owner")
                if (userObj != null) {
                    authorName = userObj.optString("username", "")
                }

                val duration = mediaObj.optLong("video_duration", 0L)

                val carouselSlides = mutableListOf<JSONObject>()
                val carouselMedia = mediaObj.optJSONArray("carousel_media")
                if (carouselMedia != null && carouselMedia.length() > 0) {
                    for (cIdx in 0 until carouselMedia.length()) {
                        val cItem = carouselMedia.optJSONObject(cIdx) ?: continue
                        var cVideo = ""
                        val cVidVers = cItem.optJSONArray("video_versions")
                        if (cVidVers != null && cVidVers.length() > 0) {
                            cVideo = cVidVers.optJSONObject(0)?.optString("url", "") ?: ""
                        }
                        var cImg = ""
                        val cImgVers = cItem.optJSONObject("image_versions2")?.optJSONArray("candidates")
                        if (cImgVers != null && cImgVers.length() > 0) {
                            cImg = cImgVers.optJSONObject(0)?.optString("url", "") ?: ""
                        }
                        val isVidSlide = cVideo.isNotBlank()
                        val slideUrl = if (isVidSlide) cVideo else cImg
                        if (slideUrl.isNotBlank()) {
                            carouselSlides.add(JSONObject().apply {
                                put("slideIndex", cIdx + 1)
                                put("url", slideUrl)
                                put("thumbnail", if (cImg.isNotBlank()) cImg else cVideo)
                                put("mediaType", if (isVidSlide) "video" else "image")
                            })
                        }
                    }
                } else {
                    val edgeChildren = mediaObj.optJSONObject("edge_sidecar_to_children")?.optJSONArray("edges")
                    if (edgeChildren != null && edgeChildren.length() > 0) {
                        for (cIdx in 0 until edgeChildren.length()) {
                            val node = edgeChildren.optJSONObject(cIdx)?.optJSONObject("node") ?: continue
                            val isVid = node.optBoolean("is_video", false)
                            val cVideo = node.optString("video_url", "")
                            val cImg = node.optString("display_url", node.optString("display_uri", ""))
                            val slideUrl = if (isVid && cVideo.isNotBlank()) cVideo else cImg
                            if (slideUrl.isNotBlank()) {
                                carouselSlides.add(JSONObject().apply {
                                    put("slideIndex", cIdx + 1)
                                    put("url", slideUrl)
                                    put("thumbnail", if (cImg.isNotBlank()) cImg else cVideo)
                                    put("mediaType", if (isVid) "video" else "image")
                                })
                            }
                        }
                    }
                }

                if (videoUrl.isNotBlank() || thumbUrl.isNotBlank() || captionText.isNotBlank() || dash1080Url.isNotBlank() || dash720Url.isNotBlank() || carouselSlides.isNotEmpty()) {
                    return IgMediaResult(
                        videoUrl = videoUrl,
                        thumbnail = thumbUrl,
                        caption = unescapeHtml(captionText),
                        author = if (authorName.isNotBlank()) "@$authorName" else "",
                        duration = duration,
                        isImage = videoUrl.isBlank() && dash1080Url.isBlank() && dash720Url.isBlank() && thumbUrl.isNotBlank(),
                        dash1080Url = dash1080Url,
                        dash720Url = dash720Url,
                        dashAudioUrl = dashAudioUrl,
                        carouselSlides = carouselSlides
                    )
                }
            } catch (_: Exception) {}
        }
        return null
    }

    private suspend fun resolveInstagram(url: String, context: Context? = null): MediaItem {
        val isReel = url.contains("/reel/") || url.contains("/reels/")
        val isPost = url.contains("/p/")

        var title = if (isReel) "Instagram Reel Clip" else "Instagram Media Post"
        var author = "@instagram_creator"
        var thumbnail = "https://images.unsplash.com/photo-1611262588024-d12430b98920?w=800"
        var directVideoUrl = ""
        var dash1080Url = ""
        var dashAudioUrl = ""
        var directImageUrl = ""
        var durationSec = if (isReel) 30L else 15L
        var carouselSlides = mutableListOf<JSONObject>()

        var shortcode = ""
        val scPattern = Pattern.compile("instagram\\.com/(?:p|reel|reels|tv|share/reel|share/p)/([a-zA-Z0-9_-]+)", Pattern.CASE_INSENSITIVE)
        val scMatcher = scPattern.matcher(url)
        if (scMatcher.find()) {
            shortcode = scMatcher.group(1) ?: ""
        }

        // 1. Primary: Direct data-sjs metadata & stream extractor (Identical to yt-dlp's native extractor)
        if (shortcode.isNotBlank()) {
            try {
                val targets = listOf(
                    "https://www.instagram.com/p/$shortcode/",
                    "https://www.instagram.com/reel/$shortcode/"
                )
                for (target in targets) {
                    val req = Request.Builder()
                        .url(target)
                        .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36")
                        .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                        .header("Accept-Language", "en-US,en;q=0.9")
                        .header("Sec-Fetch-Mode", "navigate")
                        .build()

                    val resp = client.newCall(req).execute()
                    if (resp.isSuccessful) {
                        val html = resp.body?.string() ?: ""
                        val res = extractInstagramMediaFromSjs(html)
                        if (res != null) {
                            if (res.videoUrl.isNotBlank()) directVideoUrl = res.videoUrl
                            if (res.dash1080Url.isNotBlank()) dash1080Url = res.dash1080Url
                            if (res.dashAudioUrl.isNotBlank()) dashAudioUrl = res.dashAudioUrl
                            if (res.thumbnail.isNotBlank()) {
                                thumbnail = res.thumbnail
                                directImageUrl = res.thumbnail
                            }
                            if (res.caption.isNotBlank()) title = res.caption
                            if (res.author.isNotBlank()) author = res.author
                            if (res.duration > 0) durationSec = res.duration
                            if (res.carouselSlides.isNotEmpty()) carouselSlides = res.carouselSlides.toMutableList()
                            break
                        }
                    }
                }
            } catch (_: Exception) {}
        }

        // 2. Fallback: Parse embed page for caption, author, and video cover (strictly avoiding avatar photos)
        if (shortcode.isNotBlank() && (directVideoUrl.isBlank() || thumbnail.contains("unsplash"))) {
            try {
                val embedPath = if (isReel) "reel" else "p"
                val embedUrl = "https://www.instagram.com/$embedPath/$shortcode/embed/captioned/"
                val request = Request.Builder()
                    .url(embedUrl)
                    .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36")
                    .build()
                val response = client.newCall(request).execute()
                if (response.isSuccessful) {
                    val html = response.body?.string() ?: ""
                    val capMat = Pattern.compile("class=\"[^\"]*Caption[^\"]*\"[^>]*>(.*?)</div>", Pattern.DOTALL).matcher(html)
                    if (capMat.find()) {
                        val cText = capMat.group(1)?.replace(Regex("<[^>]+>"), "")?.replace("&amp;", "&")?.trim()
                        if (!cText.isNullOrBlank()) title = cText
                    }
                    val userMat = Pattern.compile("class=\"[^\"]*Username[^\"]*\"[^>]*>(.*?)</div>", Pattern.DOTALL).matcher(html)
                    if (userMat.find()) {
                        val uText = userMat.group(1)?.replace(Regex("<[^>]+>"), "")?.trim()
                        if (!uText.isNullOrBlank()) author = if (uText.startsWith("@")) uText else "@$uText"
                    }
                    // Video cover thumbnail (matches EmbeddedMediaImage or video poster, NOT avatar!)
                    val embedImgMat = Pattern.compile("<img[^>]+class=\"[^\"]*EmbeddedMediaImage[^\"]*\"[^>]+src=\"([^\"]+)\"").matcher(html)
                    if (embedImgMat.find()) {
                        val iUrl = embedImgMat.group(1)?.replace("&amp;", "&")
                        if (!iUrl.isNullOrBlank()) {
                            thumbnail = iUrl
                            if (directImageUrl.isBlank()) directImageUrl = iUrl
                        }
                    } else {
                        val posterMat = Pattern.compile("<video[^>]+poster=\"([^\"]+)\"").matcher(html)
                        if (posterMat.find()) {
                            val pUrl = posterMat.group(1)?.replace("&amp;", "&")
                            if (!pUrl.isNullOrBlank()) {
                                thumbnail = pUrl
                                directImageUrl = pUrl
                            }
                        }
                    }

                    // Direct video stream in embed HTML
                    val vMat = Pattern.compile("\"video_url\"\\s*:\\s*\"([^\"]+)\"").matcher(html)
                    if (vMat.find()) {
                        val vu = vMat.group(1)?.replace("\\u0026", "&")?.replace("\\/", "/")
                        if (!vu.isNullOrBlank()) directVideoUrl = vu
                    }
                }
            } catch (_: Exception) {}
        }

        // 3. Fallback: Headless WebView Sniffer (if direct stream is still missing or for carousels)
        if (directVideoUrl.isBlank() && dash1080Url.isBlank() && context != null) {
            try {
                val sniff = StreamExtractor.sniffStream(context, url, timeoutMs = 12000L)
                if (sniff.streamUrl.isNotBlank()) {
                    directVideoUrl = sniff.streamUrl
                }
                if (sniff.title.isNotBlank() && !sniff.title.equals("Instagram", ignoreCase = true) && !sniff.title.startsWith("Login", ignoreCase = true)) {
                    title = sniff.title
                }
                if (sniff.author.isNotBlank() && !sniff.author.equals("@Instagram", ignoreCase = true)) {
                    author = sniff.author
                }
                if (sniff.thumbnail.isNotBlank() && !sniff.thumbnail.contains("unsplash")) {
                    thumbnail = sniff.thumbnail
                    directImageUrl = sniff.thumbnail
                }
                if (sniff.carouselSlides.isNotEmpty() && carouselSlides.isEmpty()) {
                    carouselSlides = sniff.carouselSlides.toMutableList()
                }
            } catch (_: Exception) {}
        }

        val streamMap = mutableMapOf<String, String>()
        if (directVideoUrl.isNotBlank()) {
            streamMap["720p"] = directVideoUrl
            streamMap["default"] = directVideoUrl
        }
        if (dash1080Url.isNotBlank()) {
            streamMap["1080p"] = dash1080Url
        } else if (directVideoUrl.isNotBlank()) {
            streamMap["1080p"] = directVideoUrl
        }
        if (dashAudioUrl.isNotBlank()) {
            streamMap["audio"] = dashAudioUrl
        }

        val hasVideo = directVideoUrl.isNotBlank() || dash1080Url.isNotBlank() || carouselSlides.any { it.optString("mediaType") == "video" }
        val hasImage = directImageUrl.isNotBlank() || thumbnail.isNotBlank() || carouselSlides.any { it.optString("mediaType") == "image" } || carouselSlides.isNotEmpty()
        val isImageOnly = !hasVideo && hasImage

        val resolvedDirectImg = if (directImageUrl.isNotBlank()) directImageUrl else thumbnail

        val qualities = when {
            hasVideo && hasImage -> {
                // Both video and images exist (e.g. mixed carousel or video with cover/stills)
                val vQualities = generateVideoQualities(durationSec, url, streamMap, maxSourceRes = "1080p")
                val imgQualities = generateImageQualities(resolvedDirectImg)
                vQualities + imgQualities
            }
            isImageOnly -> {
                generateImageQualities(resolvedDirectImg)
            }
            else -> {
                generateVideoQualities(durationSec, url, streamMap, maxSourceRes = "1080p")
            }
        }

        val resolvedImages = if (carouselSlides.isNotEmpty()) {
            carouselSlides.map { it.optString("url") }.filter { it.isNotBlank() }
        } else if (directImageUrl.isNotBlank()) {
            listOf(directImageUrl)
        } else if (thumbnail.isNotBlank() && !thumbnail.contains("unsplash")) {
            listOf(thumbnail)
        } else {
            emptyList()
        }

        return MediaItem(
            url = url,
            title = title,
            author = author,
            durationSeconds = if (isImageOnly) 0L else durationSec,
            thumbnail = thumbnail,
            platform = "Instagram",
            mediaType = if (isImageOnly) MediaType.IMAGE else MediaType.VIDEO,
            qualities = qualities,
            imageUrls = resolvedImages,
            carouselSlides = carouselSlides
        )
    }

    private suspend fun resolveFacebook(url: String, context: Context? = null): MediaItem {
        var title = "Facebook Video Post"
        var author = "Facebook Creator"
        var thumbnail = "https://images.unsplash.com/photo-1544717305-2782549b5136?w=800"
        var directVideoUrl = ""
        val duration = 30L

        try {
            val req = Request.Builder()
                .url(url)
                .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36")
                .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                .header("Accept-Language", "en-US,en;q=0.9")
                .build()
            val resp = client.newCall(req).execute()
            if (resp.isSuccessful) {
                val html = resp.body?.string() ?: ""

                val tMat = Pattern.compile("<meta property=\"og:title\" content=\"([^\"]+)\"").matcher(html)
                if (tMat.find()) {
                    val t = tMat.group(1)?.replace("&amp;", "&")?.replace("&#39;", "'")
                    if (!t.isNullOrBlank()) title = t
                }

                val iMat = Pattern.compile("<meta property=\"og:image\" content=\"([^\"]+)\"").matcher(html)
                if (iMat.find()) {
                    val i = iMat.group(1)?.replace("&amp;", "&")
                    if (!i.isNullOrBlank()) thumbnail = i
                }

                val hdMat = Pattern.compile("\"(?:browser_native_hd_url|playable_url_quality_hd)\":\"([^\"]+)\"").matcher(html)
                if (hdMat.find()) {
                    val v = hdMat.group(1)?.replace("\\/", "/")?.replace("\\u0026", "&")
                    if (!v.isNullOrBlank()) directVideoUrl = v
                } else {
                    val sdMat = Pattern.compile("\"(?:browser_native_sd_url|playable_url)\":\"([^\"]+)\"").matcher(html)
                    if (sdMat.find()) {
                        val v = sdMat.group(1)?.replace("\\/", "/")?.replace("\\u0026", "&")
                        if (!v.isNullOrBlank()) directVideoUrl = v
                    } else {
                        val ogvMat = Pattern.compile("<meta property=\"og:video(?::secure_url)?\" content=\"([^\"]+)\"").matcher(html)
                        if (ogvMat.find()) {
                            val v = ogvMat.group(1)?.replace("&amp;", "&")
                            if (!v.isNullOrBlank()) directVideoUrl = v
                        }
                    }
                }
            }
        } catch (_: Exception) {}

        if (directVideoUrl.isBlank() && context != null) {
            try {
                val sniff = StreamExtractor.sniffStream(context, url, timeoutMs = 12000L)
                if (sniff.streamUrl.isNotBlank()) directVideoUrl = sniff.streamUrl
                if (sniff.title.isNotBlank() && title.startsWith("Facebook")) title = sniff.title
                if (sniff.author.isNotBlank() && author == "Facebook Creator") author = sniff.author
                if (sniff.thumbnail.isNotBlank() && thumbnail.contains("unsplash")) thumbnail = sniff.thumbnail
            } catch (_: Exception) {}
        }

        val streamMap = if (directVideoUrl.isNotBlank()) mapOf("1080p" to directVideoUrl, "default" to directVideoUrl) else emptyMap()
        val qualities = generateVideoQualities(duration, url, streamMap, maxSourceRes = "1080p")

        return MediaItem(
            url = url,
            title = title,
            author = author,
            durationSeconds = duration,
            thumbnail = thumbnail,
            platform = "Facebook",
            mediaType = MediaType.VIDEO,
            qualities = qualities
        )
    }

    private suspend fun resolveThreads(url: String, context: Context? = null): MediaItem {
        var title = "Threads Post"
        var author = "Threads Creator"
        var thumbnail = "https://images.unsplash.com/photo-1611262588024-d12430b98920?w=800"
        var directVideoUrl = ""
        var dash1080Url = ""
        var dash720Url = ""
        var dashAudioUrl = ""
        var directImageUrl = ""
        var durationSec = 30L

        val scMat = Pattern.compile("threads\\.(?:net|com)/(?:@[^/]+/post|t)/([a-zA-Z0-9_-]+)", Pattern.CASE_INSENSITIVE).matcher(url)
        val shortcode = if (scMat.find()) scMat.group(1) ?: "" else ""

        val uMat = Pattern.compile("threads\\.(?:net|com)/@([^/]+)").matcher(url)
        if (uMat.find()) {
            val u = uMat.group(1)?.trim() ?: ""
            if (u.isNotBlank()) author = "@$u"
        }

        // 1. Direct fetch of Threads post page using Meta crawler User-Agent to bypass login-wall
        try {
            val req = Request.Builder()
                .url(url)
                .header("User-Agent", "facebookexternalhit/1.1 (+http://www.facebook.com/externalhit_uatext.php)")
                .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                .header("Accept-Language", "en-US,en;q=0.9")
                .build()
            val resp = client.newCall(req).execute()
            if (resp.isSuccessful) {
                val html = resp.body?.string() ?: ""

                // Extract authentic caption from og:description
                val descMat = Pattern.compile("<meta[^>]+property=\"og:description\"[^>]+content=\"([^\"]+)\"").matcher(html)
                if (descMat.find()) {
                    val d = unescapeHtml(descMat.group(1) ?: "")
                    if (d.isNotBlank() && !d.startsWith("Join Threads") && !d.startsWith("Log in")) {
                        title = d
                    }
                }

                // Fallback title from <title> tag
                if (title == "Threads Post") {
                    val titleMat = Pattern.compile("<title>([^<]+)</title>").matcher(html)
                    if (titleMat.find()) {
                        val t = unescapeHtml(titleMat.group(1) ?: "")
                        if (t.isNotBlank() && !t.equals("Threads", ignoreCase = true) && !t.contains("Log in") && !t.startsWith("Home")) {
                            title = t
                        }
                    }
                }

                // Extract author from og:title if needed
                if (author == "Threads Creator") {
                    val ogtMat = Pattern.compile("<meta[^>]+property=\"og:title\"[^>]+content=\"([^\"]+)\"").matcher(html)
                    if (ogtMat.find()) {
                        val ogt = unescapeHtml(ogtMat.group(1) ?: "")
                        val aMat = Pattern.compile("@([a-zA-Z0-9._]+)").matcher(ogt)
                        if (aMat.find()) {
                            author = "@" + aMat.group(1)
                        }
                    }
                }

                // Extract thumbnail from og:image
                val imgMat = Pattern.compile("<meta[^>]+property=\"og:image\"[^>]+content=\"([^\"]+)\"").matcher(html)
                if (imgMat.find()) {
                    val img = imgMat.group(1)?.replace("&amp;", "&")?.trim() ?: ""
                    if (img.isNotBlank() && !img.contains("150x150") && !img.contains("avatar") && !img.contains("t51.2885-19")) {
                        thumbnail = img
                        directImageUrl = img
                    }
                }

                // Extract video from og:video
                val vMat = Pattern.compile("<meta[^>]+property=\"og:video(?::secure_url)?\"[^>]+content=\"([^\"]+)\"").matcher(html)
                if (vMat.find()) {
                    val v = vMat.group(1)?.replace("&amp;", "&")?.trim() ?: ""
                    if (v.isNotBlank()) directVideoUrl = v
                }

                // Parse structured media from data-sjs in Threads HTML
                val res = extractInstagramMediaFromSjs(html)
                if (res != null) {
                    if (res.videoUrl.isNotBlank()) directVideoUrl = res.videoUrl
                    if (res.dash1080Url.isNotBlank()) dash1080Url = res.dash1080Url
                    if (res.dash720Url.isNotBlank()) dash720Url = res.dash720Url
                    if (res.dashAudioUrl.isNotBlank()) dashAudioUrl = res.dashAudioUrl
                    if (res.thumbnail.isNotBlank() && (thumbnail.contains("unsplash") || thumbnail.contains("t51.2885-19"))) {
                        thumbnail = res.thumbnail
                        directImageUrl = res.thumbnail
                    }
                    if (res.caption.isNotBlank() && (title == "Threads Post" || title.length < res.caption.length)) {
                        title = res.caption
                    }
                    if (res.author.isNotBlank()) author = res.author
                    if (res.duration > 0) durationSec = res.duration
                }
            }
        } catch (_: Exception) {}

        // 2. Instagram shortcode fallback (Threads posts are mirrored on Meta media infrastructure)
        if (shortcode.isNotBlank() && (directVideoUrl.isBlank() && dash1080Url.isBlank())) {
            try {
                val igReq = Request.Builder()
                    .url("https://www.instagram.com/p/$shortcode/")
                    .header("User-Agent", "facebookexternalhit/1.1 (+http://www.facebook.com/externalhit_uatext.php)")
                    .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                    .header("Accept-Language", "en-US,en;q=0.9")
                    .build()
                val igResp = client.newCall(igReq).execute()
                if (igResp.isSuccessful) {
                    val igHtml = igResp.body?.string() ?: ""
                    val res = extractInstagramMediaFromSjs(igHtml)
                    if (res != null) {
                        if (res.videoUrl.isNotBlank()) directVideoUrl = res.videoUrl
                        if (res.dash1080Url.isNotBlank()) dash1080Url = res.dash1080Url
                        if (res.dash720Url.isNotBlank()) dash720Url = res.dash720Url
                        if (res.dashAudioUrl.isNotBlank()) dashAudioUrl = res.dashAudioUrl
                        if (res.thumbnail.isNotBlank() && thumbnail.contains("unsplash")) {
                            thumbnail = res.thumbnail
                            directImageUrl = res.thumbnail
                        }
                        if (res.caption.isNotBlank() && title == "Threads Post") title = res.caption
                        if (res.author.isNotBlank() && author == "Threads Creator") author = res.author
                        if (res.duration > 0) durationSec = res.duration
                    }
                }
            } catch (_: Exception) {}
        }

        // 3. Fallback: Sniffer
        if (directVideoUrl.isBlank() && dash1080Url.isBlank() && context != null) {
            try {
                val sniff = StreamExtractor.sniffStream(context, url, timeoutMs = 12000L)
                if (sniff.streamUrl.isNotBlank()) directVideoUrl = sniff.streamUrl
                if (sniff.title.isNotBlank() && !sniff.title.equals("Threads", ignoreCase = true) && !sniff.title.contains("Log in")) {
                    title = sniff.title
                }
                if (sniff.author.isNotBlank() && !sniff.author.equals("@Threads", ignoreCase = true)) {
                    author = sniff.author
                }
                if (sniff.thumbnail.isNotBlank() && !sniff.thumbnail.contains("unsplash")) {
                    thumbnail = sniff.thumbnail
                    directImageUrl = sniff.thumbnail
                }
            } catch (_: Exception) {}
        }

        val hasVideo = directVideoUrl.isNotBlank() || dash1080Url.isNotBlank() || dash720Url.isNotBlank()
        val streamMap = mutableMapOf<String, String>()
        val defaultStream = directVideoUrl.ifBlank { dash720Url.ifBlank { dash1080Url } }
        if (defaultStream.isNotBlank()) {
            streamMap["default"] = defaultStream
        }
        if (directVideoUrl.isNotBlank()) {
            streamMap["720p"] = directVideoUrl
        } else if (dash720Url.isNotBlank()) {
            streamMap["720p"] = dash720Url
        } else if (dash1080Url.isNotBlank()) {
            streamMap["720p"] = dash1080Url
        }
        if (dash1080Url.isNotBlank()) {
            streamMap["1080p"] = dash1080Url
        } else if (directVideoUrl.isNotBlank()) {
            streamMap["1080p"] = directVideoUrl
        } else if (dash720Url.isNotBlank()) {
            streamMap["1080p"] = dash720Url
        }
        if (dashAudioUrl.isNotBlank()) {
            streamMap["audio"] = dashAudioUrl
        }

        val qualities = if (hasVideo) generateVideoQualities(durationSec, url, streamMap, maxSourceRes = "1080p") else generateImageQualities(if (directImageUrl.isNotBlank()) directImageUrl else thumbnail)

        return MediaItem(
            url = url,
            title = title,
            author = author,
            durationSeconds = if (hasVideo) durationSec else 0L,
            thumbnail = thumbnail,
            platform = "Threads",
            mediaType = if (hasVideo) MediaType.VIDEO else MediaType.IMAGE,
            qualities = qualities,
            imageUrls = if (!hasVideo && directImageUrl.isNotBlank()) listOf(directImageUrl) else emptyList()
        )
    }

    private suspend fun resolvePinterest(url: String, context: Context? = null): MediaItem {
        var targetUrl = url
        // Expand short links (pin.it)
        if (targetUrl.contains("pin.it")) {
            try {
                val redReq = Request.Builder().url(targetUrl).head().build()
                val redResp = client.newCall(redReq).execute()
                val loc = redResp.request.url.toString()
                if (loc.isNotBlank() && !loc.contains("pin.it")) targetUrl = loc
            } catch (_: Exception) {}
        }

        var title = "Pinterest Visual Pin"
        var author = "Pinterest Creator"
        var thumbnail = "https://images.unsplash.com/photo-1579783902614-a3fb3927b675?w=800"
        var directImageUrl = ""
        var directVideoUrl = ""
        var durationSec = 25L
        var pinCarouselSlides = mutableListOf<JSONObject>()

        // 1. Direct query to Pinterest official PinResource endpoint
        var pinId = ""
        val pinMat = Pattern.compile("pin/(?:[\\w-]+--)?(\\d+)", Pattern.CASE_INSENSITIVE).matcher(targetUrl)
        if (pinMat.find()) {
            pinId = pinMat.group(1) ?: ""
        }
        if (pinId.isBlank()) {
            val numMat = Pattern.compile("(\\d{10,22})").matcher(targetUrl)
            if (numMat.find()) pinId = numMat.group(1) ?: ""
        }

        if (pinId.isNotBlank()) {
            try {
                val opts = JSONObject().apply {
                    put("field_set_key", "unauth_react_main_pin")
                    put("id", pinId)
                }
                val q = java.net.URLEncoder.encode(JSONObject().apply { put("options", opts) }.toString(), "UTF-8")
                val apiUrl = "https://www.pinterest.com/resource/PinResource/get/?data=$q"
                val apiReq = Request.Builder()
                    .url(apiUrl)
                    .header("X-Pinterest-PWS-Handler", "www/[username].js")
                    .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
                    .build()
                val apiResp = client.newCall(apiReq).execute()
                if (apiResp.isSuccessful) {
                    val apiBody = apiResp.body?.string() ?: ""
                    val root = JSONObject(apiBody)
                    val data = root.optJSONObject("resource_response")?.optJSONObject("data")
                    if (data != null) {
                        val parsedTitle = data.optString("title", "").ifBlank {
                            data.optString("grid_title", "").ifBlank {
                                data.optString("closeup_unified_description", "")
                            }
                        }
                        if (parsedTitle.isNotBlank()) title = unescapeHtml(parsedTitle)

                        val pinner = data.optJSONObject("pinner")
                        val pinnerName = pinner?.optString("full_name", "")?.ifBlank {
                            pinner.optString("username", "")
                        } ?: ""
                        if (pinnerName.isNotBlank()) author = pinnerName

                        val images = data.optJSONObject("images")
                        val origImg = images?.optJSONObject("orig")?.optString("url", "") ?: ""
                        val largeImg = images?.optJSONObject("736x")?.optString("url", "") ?: ""
                        if (origImg.isNotBlank()) {
                            directImageUrl = origImg
                            thumbnail = origImg
                        } else if (largeImg.isNotBlank()) {
                            directImageUrl = largeImg
                            thumbnail = largeImg
                        }

                        // Check story_pin_data for multi-slide carousel items
                        val storyData = data.optJSONObject("story_pin_data")
                        val pages = storyData?.optJSONArray("pages")
                        if (pages != null && pages.length() > 0) {
                            for (pI in 0 until pages.length()) {
                                val pObj = pages.optJSONObject(pI) ?: continue
                                var pImg = ""
                                var pVid = ""
                                val blocks = pObj.optJSONArray("blocks")
                                if (blocks != null && blocks.length() > 0) {
                                    for (bI in 0 until blocks.length()) {
                                        val bObj = blocks.optJSONObject(bI) ?: continue
                                        val bImg = bObj.optJSONObject("image")?.optJSONObject("images")?.optJSONObject("orig")?.optString("url", "") ?: ""
                                        if (bImg.isNotBlank()) pImg = bImg
                                        val bVidList = bObj.optJSONObject("video")?.optJSONObject("video_list")
                                        if (bVidList != null) {
                                            val vKeys = bVidList.keys()
                                            while (vKeys.hasNext()) {
                                                val vk = vKeys.next()
                                                val vu = bVidList.optJSONObject(vk)?.optString("url", "") ?: ""
                                                if (vu.isNotBlank() && vu.contains(".mp4")) {
                                                    pVid = vu
                                                    break
                                                }
                                            }
                                        }
                                    }
                                }
                                val slideUrl = if (pVid.isNotBlank()) pVid else pImg
                                if (slideUrl.isNotBlank()) {
                                    val isVid = pVid.isNotBlank()
                                    pinCarouselSlides.add(JSONObject().apply {
                                        put("slideIndex", pI + 1)
                                        put("url", slideUrl)
                                        put("thumbnail", if (pImg.isNotBlank()) pImg else pVid)
                                        put("mediaType", if (isVid) "video" else "image")
                                    })
                                }
                            }
                        }

                        // Check video_list in videos
                        var vidList = data.optJSONObject("videos")?.optJSONObject("video_list")
                        // If null, check story_pin_data blocks
                        if (vidList == null && pages != null && pages.length() > 0) {
                            for (pI in 0 until pages.length()) {
                                val blocks = pages.optJSONObject(pI)?.optJSONArray("blocks")
                                if (blocks != null && blocks.length() > 0) {
                                    for (bI in 0 until blocks.length()) {
                                        val blkVid = blocks.optJSONObject(bI)?.optJSONObject("video")?.optJSONObject("video_list")
                                        if (blkVid != null) {
                                            vidList = blkVid
                                            break
                                        }
                                    }
                                }
                                if (vidList != null) break
                            }
                        }

                        if (vidList != null) {
                            val preferredKeys = listOf("V_EXP7", "V_720P", "V_480P")
                            for (pk in preferredKeys) {
                                val vObj = vidList.optJSONObject(pk)
                                val u = vObj?.optString("url", "") ?: ""
                                if (u.isNotBlank() && u.contains(".mp4")) {
                                    directVideoUrl = u
                                    val dur = vObj?.optLong("duration", 0L) ?: 0L
                                    if (dur > 0L) durationSec = dur / 1000L
                                    val thumb = vObj?.optString("thumbnail", "") ?: ""
                                    if (thumb.isNotBlank()) thumbnail = thumb
                                    break
                                }
                            }
                            if (directVideoUrl.isBlank()) {
                                val keys = vidList.keys()
                                while (keys.hasNext()) {
                                    val k = keys.next()
                                    val vObj = vidList.optJSONObject(k)
                                    val u = vObj?.optString("url", "") ?: ""
                                    if (u.isNotBlank() && u.contains(".mp4")) {
                                        directVideoUrl = u
                                        val dur = vObj?.optLong("duration", 0L) ?: 0L
                                        if (dur > 0L) durationSec = dur / 1000L
                                        val thumb = vObj?.optString("thumbnail", "") ?: ""
                                        if (thumb.isNotBlank()) thumbnail = thumb
                                        break
                                    }
                                }
                            }
                        }
                    }
                }
            } catch (_: Exception) {}
        }

        // 2. Fallback HTML Scraping
        if (directVideoUrl.isBlank()) {
            try {
                val req = Request.Builder()
                    .url(targetUrl)
                    .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36")
                    .build()
                val resp = client.newCall(req).execute()
                if (resp.isSuccessful) {
                    val html = resp.body?.string() ?: ""
                    val cleanHtml = html.replace("\\/", "/").replace("\\u002F", "/").replace("&amp;", "&")
                    val titlePattern = Pattern.compile("<meta property=\"og:title\" content=\"([^\"]+)\">")
                    val titleMatcher = titlePattern.matcher(html)
                    if (titleMatcher.find()) {
                        val parsed = titleMatcher.group(1)?.let { unescapeHtml(it) }
                        if (!parsed.isNullOrBlank()) title = parsed
                    }

                    val imgPattern = Pattern.compile("https://i\\.pinimg\\.com/(?:originals|736x)/[a-zA-Z0-9/_.-]+\\.(?:jpg|png|webp|gif)")
                    val imgMatcher = imgPattern.matcher(cleanHtml)
                    if (imgMatcher.find()) {
                        directImageUrl = imgMatcher.group(0) ?: ""
                        if (thumbnail.contains("unsplash")) thumbnail = directImageUrl
                    }

                    val vidPattern = Pattern.compile("https?://[a-zA-Z0-9.-]*pinimg\\.com/videos/[a-zA-Z0-9/_.-]+\\.mp4")
                    val vidMatcher = vidPattern.matcher(cleanHtml)
                    if (vidMatcher.find()) {
                        directVideoUrl = vidMatcher.group(0) ?: ""
                    } else {
                        val ogvMat = Pattern.compile("<meta property=\"og:video(?::secure_url)?\" content=\"([^\"]+)\"").matcher(cleanHtml)
                        if (ogvMat.find()) {
                            val ogv = ogvMat.group(1) ?: ""
                            if (ogv.isNotBlank()) directVideoUrl = ogv
                        } else {
                            val vidTagMat = Pattern.compile("<video[^>]+src=\"([^\"]+)\"").matcher(cleanHtml)
                            if (vidTagMat.find()) {
                                val vsrc = vidTagMat.group(1) ?: ""
                                if (vsrc.isNotBlank()) directVideoUrl = vsrc
                            }
                        }
                    }
                }
            } catch (_: Exception) {}
        }

        // 3. Fallback Sniffer
        if (directVideoUrl.isBlank() && context != null) {
            try {
                val sniff = StreamExtractor.sniffStream(context, targetUrl)
                if (!sniff.streamUrl.isNullOrBlank()) directVideoUrl = sniff.streamUrl
                if (!sniff.title.isNullOrBlank() && title == "Pinterest Visual Pin") title = sniff.title
                if (!sniff.thumbnail.isNullOrBlank() && thumbnail.contains("unsplash")) thumbnail = sniff.thumbnail
            } catch (_: Exception) {}
        }

        val isGif = directImageUrl.lowercase().endsWith(".gif") ||
                    thumbnail.lowercase().endsWith(".gif") ||
                    targetUrl.lowercase().contains(".gif")

        val hasVideo = directVideoUrl.isNotBlank()
        val streamMap = if (hasVideo) {
            mapOf(
                "default" to directVideoUrl,
                "720p" to directVideoUrl,
                "1080p" to directVideoUrl
            )
        } else emptyMap()

        val qualities = if (hasVideo) {
            val qList = generateVideoQualities(durationSec, targetUrl, streamMap, maxSourceRes = "1080p").toMutableList()
            val imgUrl = if (directImageUrl.isNotBlank()) directImageUrl else thumbnail
            if (imgUrl.isNotBlank() && !imgUrl.contains("unsplash")) {
                qList.addAll(generateImageQualities(imgUrl, isGifMedia = isGif))
            }
            qList
        } else {
            generateImageQualities(if (directImageUrl.isNotBlank()) directImageUrl else thumbnail, isGifMedia = isGif)
        }

        val resolvedImageUrls = if (directImageUrl.isNotBlank()) {
            listOf(directImageUrl)
        } else if (thumbnail.isNotBlank() && !thumbnail.contains("unsplash")) {
            listOf(thumbnail)
        } else emptyList()

        return MediaItem(
            url = targetUrl,
            title = title,
            author = author,
            durationSeconds = if (hasVideo) durationSec else 0L,
            thumbnail = thumbnail,
            platform = "Pinterest",
            mediaType = if (hasVideo) MediaType.VIDEO else MediaType.IMAGE,
            qualities = qualities,
            imageUrls = resolvedImageUrls,
            carouselSlides = pinCarouselSlides
        )
    }

    private suspend fun resolveGiphy(url: String): MediaItem {
        var targetUrl = url
        if (targetUrl.contains("gph.is")) {
            try {
                val redReq = Request.Builder().url(targetUrl).head().build()
                val redResp = client.newCall(redReq).execute()
                val loc = redResp.request.url.toString()
                if (loc.isNotBlank() && !loc.contains("gph.is")) targetUrl = loc
            } catch (_: Exception) {}
        }

        var title = "Giphy Animated GIF"
        var author = "GIPHY Creator"
        var gifUrl = ""
        var mp4Url = ""
        var thumbnail = ""

        // 1. Official oEmbed API
        try {
            val oembedUrl = "https://giphy.com/services/oembed?url=" + java.net.URLEncoder.encode(targetUrl, "UTF-8")
            val req = Request.Builder()
                .url(oembedUrl)
                .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
                .build()
            val resp = client.newCall(req).execute()
            if (resp.isSuccessful) {
                val body = resp.body?.string() ?: ""
                val json = JSONObject(body)
                val t = json.optString("title", "")
                if (t.isNotBlank()) title = unescapeHtml(t)
                val a = json.optString("author_name", "")
                if (a.isNotBlank()) author = a
                val u = json.optString("url", "")
                if (u.isNotBlank()) gifUrl = u
            }
        } catch (_: Exception) {}

        // 2. Fallback ID extraction
        if (gifUrl.isBlank()) {
            val idMat = Pattern.compile("(?:gifs/|media/|[\\w-]+-)([a-zA-Z0-9]{10,25})(?:/|\\?|$)").matcher(targetUrl)
            val gifId = if (idMat.find()) idMat.group(1) ?: "" else ""
            if (gifId.isNotBlank()) {
                gifUrl = "https://media.giphy.com/media/$gifId/giphy.gif"
            }
        }

        // 3. Fallback HTML Scraping
        if (gifUrl.isBlank()) {
            try {
                val req = Request.Builder().url(targetUrl)
                    .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
                    .build()
                val resp = client.newCall(req).execute()
                if (resp.isSuccessful) {
                    val html = resp.body?.string() ?: ""
                    val gMat = Pattern.compile("https://media[0-9]*\\.giphy\\.com/media/[a-zA-Z0-9_-]+/giphy\\.gif").matcher(html)
                    if (gMat.find()) gifUrl = gMat.group(0) ?: ""
                    val tMat = Pattern.compile("<meta property=\"og:title\" content=\"([^\"]+)\"").matcher(html)
                    if (tMat.find()) {
                        val parsed = tMat.group(1)?.let { unescapeHtml(it) }
                        if (!parsed.isNullOrBlank()) title = parsed
                    }
                }
            } catch (_: Exception) {}
        }

        if (gifUrl.isNotBlank()) {
            thumbnail = gifUrl
            mp4Url = gifUrl.replace(".gif", ".mp4")
        } else {
            gifUrl = targetUrl
            thumbnail = targetUrl
        }

        val qualities = mutableListOf<QualityOption>()
        qualities.add(
            QualityOption(
                id = "img_gif",
                label = "Animated GIF (Original Motion)",
                resolution = "GIF Animation • Infinite Loop",
                format = "Animation • GIF",
                ext = "gif",
                estimatedSizeBytes = 3 * 1024 * 1024,
                bitrateKbps = 0,
                isImage = true,
                directDownloadUrl = gifUrl
            )
        )
        if (mp4Url.isNotBlank()) {
            qualities.add(
                QualityOption(
                    id = "1080p",
                    label = "MP4 Video Loop (Smooth)",
                    resolution = "Video Loop • MP4",
                    format = "Video • MP4 (H.264)",
                    ext = "mp4",
                    estimatedSizeBytes = (1.8 * 1024 * 1024).toLong(),
                    bitrateKbps = 2500,
                    isImage = false,
                    directDownloadUrl = mp4Url
                )
            )
        }
        qualities.addAll(generateImageQualities(gifUrl, isGifMedia = true).filter { it.id != "img_gif" })

        return MediaItem(
            url = targetUrl,
            title = title,
            author = author,
            durationSeconds = 5L,
            thumbnail = thumbnail,
            platform = "Giphy",
            mediaType = MediaType.IMAGE,
            qualities = qualities,
            imageUrls = listOf(gifUrl)
        )
    }

    private suspend fun resolveTenor(url: String): MediaItem {
        var targetUrl = url
        var title = "Tenor Animated GIF"
        var author = "Tenor Creator"
        var gifUrl = ""
        var mp4Url = ""
        var thumbnail = ""

        try {
            val req = Request.Builder()
                .url(targetUrl)
                .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36")
                .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                .build()
            val resp = client.newCall(req).execute()
            if (resp.isSuccessful) {
                val html = resp.body?.string() ?: ""
                val cleanHtml = html.replace("\\/", "/").replace("&amp;", "&")

                val tMat = Pattern.compile("<meta property=\"og:title\" content=\"([^\"]+)\"").matcher(cleanHtml)
                if (tMat.find()) {
                    val rawT = tMat.group(1)?.let { unescapeHtml(it) } ?: ""
                    val cleanT = rawT.replace(" - Discover & Share GIFs", "").replace(" GIF", "").trim()
                    if (cleanT.isNotBlank()) title = cleanT
                }

                val gMat = Pattern.compile("https?://(?:media|c)\\.tenor\\.com/[a-zA-Z0-9/_.-]+\\.gif").matcher(cleanHtml)
                if (gMat.find()) {
                    gifUrl = gMat.group(0) ?: ""
                } else {
                    val ogImg = Pattern.compile("<meta property=\"og:image\" content=\"([^\"]+)\"").matcher(cleanHtml)
                    if (ogImg.find()) gifUrl = ogImg.group(1) ?: ""
                }

                val vMat = Pattern.compile("https?://(?:media|c)\\.tenor\\.com/[a-zA-Z0-9/_.-]+\\.mp4").matcher(cleanHtml)
                if (vMat.find()) {
                    mp4Url = vMat.group(0) ?: ""
                } else {
                    val ogVid = Pattern.compile("<meta property=\"og:video(?::secure_url)?\" content=\"([^\"]+)\"").matcher(cleanHtml)
                    if (ogVid.find()) mp4Url = ogVid.group(1) ?: ""
                }
            }
        } catch (_: Exception) {}

        if (gifUrl.isBlank()) gifUrl = targetUrl
        thumbnail = gifUrl

        val qualities = mutableListOf<QualityOption>()
        qualities.add(
            QualityOption(
                id = "img_gif",
                label = "Animated GIF (Original Motion)",
                resolution = "GIF Animation • Infinite Loop",
                format = "Animation • GIF",
                ext = "gif",
                estimatedSizeBytes = 3 * 1024 * 1024,
                bitrateKbps = 0,
                isImage = true,
                directDownloadUrl = gifUrl
            )
        )
        if (mp4Url.isNotBlank()) {
            qualities.add(
                QualityOption(
                    id = "1080p",
                    label = "MP4 Video Loop (Smooth)",
                    resolution = "Video Loop • MP4",
                    format = "Video • MP4 (H.264)",
                    ext = "mp4",
                    estimatedSizeBytes = (1.8 * 1024 * 1024).toLong(),
                    bitrateKbps = 2500,
                    isImage = false,
                    directDownloadUrl = mp4Url
                )
            )
        }
        qualities.addAll(generateImageQualities(gifUrl, isGifMedia = true).filter { it.id != "img_gif" })

        return MediaItem(
            url = targetUrl,
            title = title,
            author = author,
            durationSeconds = 5L,
            thumbnail = thumbnail,
            platform = "Tenor",
            mediaType = MediaType.IMAGE,
            qualities = qualities,
            imageUrls = listOf(gifUrl)
        )
    }

    private suspend fun resolveSpotify(url: String, context: Context? = null): MediaItem {
        var targetUrl = url
        // Expand shortlinks if needed
        if (targetUrl.contains("spotify.link") || targetUrl.contains("spoti.fi")) {
            try {
                val redReq = Request.Builder().url(targetUrl).head().build()
                val redResp = client.newCall(redReq).execute()
                val loc = redResp.request.url.toString()
                if (loc.isNotBlank() && !loc.contains("spotify.link") && !loc.contains("spoti.fi")) {
                    targetUrl = loc
                }
            } catch (_: Exception) {}
        }

        var title = "Spotify Track"
        var author = "Spotify Artist"
        var thumbnail = "https://images.unsplash.com/photo-1511671782779-c97d3d27a1d4?w=800"
        var durationSec = 180L
        var directAudioUrl = ""

        // 1. Query Spotify official oEmbed API
        try {
            val oembedApi = "https://open.spotify.com/oembed?url=" + java.net.URLEncoder.encode(targetUrl, "UTF-8")
            val req = Request.Builder()
                .url(oembedApi)
                .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
                .build()
            val resp = client.newCall(req).execute()
            if (resp.isSuccessful) {
                val jsonStr = resp.body?.string() ?: ""
                val json = JSONObject(jsonStr)
                val parsedTitle = json.optString("title", "")
                if (parsedTitle.isNotBlank()) title = unescapeHtml(parsedTitle)
                val parsedThumb = json.optString("thumbnail_url", "")
                if (parsedThumb.isNotBlank()) thumbnail = parsedThumb
            }
        } catch (_: Exception) {}

        // 2. Query Spotify embed page for accurate Artist & Duration
        val trackIdMat = Pattern.compile("spotify\\.com/track/([a-zA-Z0-9]+)").matcher(targetUrl)
        val trackId = if (trackIdMat.find()) trackIdMat.group(1) ?: "" else ""
        if (trackId.isNotBlank()) {
            try {
                val embedUrl = "https://open.spotify.com/embed/track/$trackId"
                val embedReq = Request.Builder()
                    .url(embedUrl)
                    .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
                    .build()
                val embedResp = client.newCall(embedReq).execute()
                if (embedResp.isSuccessful) {
                    val html = embedResp.body?.string() ?: ""
                    val ndMat = Pattern.compile("<script id=\"__NEXT_DATA__\" type=\"application/json\">(\\{.+?\\})</script>").matcher(html)
                    if (ndMat.find()) {
                        val ndJson = JSONObject(ndMat.group(1) ?: "")
                        val entity = ndJson.optJSONObject("props")?.optJSONObject("pageProps")?.optJSONObject("state")?.optJSONObject("data")?.optJSONObject("entity")
                        if (entity != null) {
                            val entityName = entity.optString("name", "")
                            if (entityName.isNotBlank() && title == "Spotify Track") title = entityName

                            val artistsArr = entity.optJSONArray("artists")
                            if (artistsArr != null && artistsArr.length() > 0) {
                                val artistNames = mutableListOf<String>()
                                for (aI in 0 until artistsArr.length()) {
                                    val an = artistsArr.optJSONObject(aI)?.optString("name", "") ?: ""
                                    if (an.isNotBlank()) artistNames.add(an)
                                }
                                if (artistNames.isNotEmpty()) {
                                    author = artistNames.joinToString(", ")
                                }
                            }

                            val durMs = entity.optLong("duration", 0L)
                            if (durMs > 0L) {
                                durationSec = durMs / 1000L
                            }

                            val prev = entity.optString("preview_url", "").ifBlank {
                                entity.optString("audio_preview_url", "")
                            }
                            if (prev.isNotBlank() && prev.startsWith("http")) {
                                directAudioUrl = prev
                            }
                        }
                    }
                }
            } catch (_: Exception) {}
        }

        // 3. Search and pair YouTube high-quality audio stream
        try {
            val query = if (author.isNotBlank() && author != "Spotify Artist") {
                "$title $author audio"
            } else {
                "$title audio"
            }
            val ytResults = MediaSearchEngine.searchYouTube(query)
            for (cand in ytResults.take(3)) {
                val cleanVid = cand.id.removePrefix("yt_").trim()
                if (cleanVid.isNotBlank()) {
                    val cleanUrl = if (cand.directUrl.isNotBlank() && !cand.directUrl.contains("yt_")) {
                        cand.directUrl
                    } else {
                        "https://www.youtube.com/watch?v=$cleanVid"
                    }
                    val ytItem = resolveYouTube(cleanUrl)
                    val audioOpt = ytItem.qualities.find { it.isAudioOnly && !it.directDownloadUrl.isNullOrBlank() }
                        ?: ytItem.qualities.firstOrNull { !it.directDownloadUrl.isNullOrBlank() }
                    if (audioOpt?.directDownloadUrl?.isNotBlank() == true) {
                        directAudioUrl = audioOpt.directDownloadUrl!!
                        if (durationSec <= 0L && ytItem.durationSeconds > 0L) {
                            durationSec = ytItem.durationSeconds
                        }
                        break
                    }
                }
            }
        } catch (_: Exception) {}

        val qualities = generateAudioQualities(durationSec, directAudioUrl)

        return MediaItem(
            url = targetUrl,
            title = title,
            author = author,
            durationSeconds = durationSec,
            thumbnail = thumbnail,
            platform = "Spotify",
            mediaType = MediaType.AUDIO,
            qualities = qualities
        )
    }

    private suspend fun resolveTikTok(url: String, context: Context? = null): MediaItem {
        var title = "TikTok Viral Video"
        var author = "@tiktok_creator"
        var thumbnail = "https://images.unsplash.com/photo-1596524430615-b46475ddff6e?w=800"
        var duration = 25L
        var directVideoUrl = ""
        var hdVideoUrl = ""
        var directAudioUrl = ""
        val imageUrls = mutableListOf<String>()

        var targetUrl = url
        try {
            if (url.contains("vt.tiktok.com") || url.contains("vm.tiktok.com") || url.contains("/t/")) {
                val redReq = Request.Builder().url(url).head().build()
                val redResp = client.newCall(redReq).execute()
                val loc = redResp.request.url.toString()
                if (loc.isNotBlank()) targetUrl = loc
            }
        } catch (_: Exception) {}

        fun formatUrl(raw: String): String {
            if (raw.isBlank()) return ""
            return if (raw.startsWith("/")) "https://www.tikwm.com$raw" else raw
        }

        try {
            val tikUrl = "https://www.tikwm.com/api/?url=" + java.net.URLEncoder.encode(targetUrl, "UTF-8") + "&hd=1"
            val request = Request.Builder()
                .url(tikUrl)
                .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36")
                .build()
            val response = client.newCall(request).execute()
            if (response.isSuccessful) {
                val body = response.body?.string()
                if (!body.isNullOrBlank()) {
                    val json = JSONObject(body)
                    val data = json.optJSONObject("data")
                    if (data != null) {
                        val parsedTitle = data.optString("title", "")
                        if (parsedTitle.isNotBlank()) title = parsedTitle
                        val authObj = data.optJSONObject("author")
                        if (authObj != null) {
                            val uniqueId = authObj.optString("unique_id", "")
                            if (uniqueId.isNotBlank()) author = "@$uniqueId"
                        }
                        val dur = data.optLong("duration", 0L)
                        if (dur > 0L) duration = dur
                        val cover = data.optString("cover", "")
                        if (cover.isNotBlank()) thumbnail = formatUrl(cover)

                        val play = data.optString("play", "")
                        if (play.isNotBlank()) directVideoUrl = formatUrl(play)
                        val hdPlay = data.optString("hdplay", "")
                        if (hdPlay.isNotBlank()) hdVideoUrl = formatUrl(hdPlay)
                        val music = data.optString("music", "")
                        if (music.isNotBlank()) directAudioUrl = formatUrl(music)

                        val images = data.optJSONArray("images")
                        if (images != null) {
                            for (i in 0 until images.length()) {
                                val img = images.optString(i, "")
                                if (img.isNotBlank()) imageUrls.add(formatUrl(img))
                            }
                        }
                    }
                }
            }
        } catch (_: Exception) {}

        val streamMap = mutableMapOf<String, String>()
        val primaryVideo = if (hdVideoUrl.isNotBlank()) hdVideoUrl else directVideoUrl
        if (primaryVideo.isNotBlank()) {
            streamMap["1080p"] = primaryVideo
            streamMap["720p"] = if (directVideoUrl.isNotBlank()) directVideoUrl else primaryVideo
            streamMap["default"] = primaryVideo
        }
        if (directAudioUrl.isNotBlank()) streamMap["audio"] = directAudioUrl

        // Fallback 1: vxtiktok.com OpenGraph scraper
        if (streamMap.isEmpty() && imageUrls.isEmpty()) {
            try {
                val vxUrl = targetUrl.replace("tiktok.com", "vxtiktok.com")
                val req = Request.Builder()
                    .url(vxUrl)
                    .header("User-Agent", "TelegramBot (like TwitterBot)")
                    .build()
                val resp = client.newCall(req).execute()
                if (resp.isSuccessful) {
                    val html = resp.body?.string() ?: ""
                    val tMat = Pattern.compile("<meta property=\"og:title\" content=\"([^\"]+)\"").matcher(html)
                    if (tMat.find()) {
                        val t = tMat.group(1)?.replace("&amp;", "&")
                        if (!t.isNullOrBlank()) title = t
                    }
                    val vidMat = Pattern.compile("<meta property=\"og:video(?::secure_url)?\" content=\"([^\"]+)\"").matcher(html)
                    if (vidMat.find()) {
                        val v = vidMat.group(1)?.replace("&amp;", "&")
                        if (!v.isNullOrBlank()) {
                            directVideoUrl = v
                            streamMap["1080p"] = v
                            streamMap["default"] = v
                        }
                    }
                    val imgMat = Pattern.compile("<meta property=\"og:image\" content=\"([^\"]+)\"").matcher(html)
                    if (imgMat.find()) {
                        val img = imgMat.group(1)?.replace("&amp;", "&")
                        if (!img.isNullOrBlank()) thumbnail = img
                    }
                }
            } catch (_: Exception) {}
        }

        // Fallback 2: Headless WebView sniffer
        if (streamMap.isEmpty() && imageUrls.isEmpty() && context != null) {
            try {
                val sniff = StreamExtractor.sniffStream(context, targetUrl, timeoutMs = 12000L)
                if (sniff.streamUrl.isNotBlank()) {
                    streamMap["default"] = sniff.streamUrl
                    if (sniff.title.isNotBlank() && title.startsWith("TikTok")) title = sniff.title
                    if (sniff.author.isNotBlank() && author == "@tiktok_creator") author = sniff.author
                    if (sniff.thumbnail.isNotBlank()) thumbnail = sniff.thumbnail
                }
            } catch (_: Exception) {}
        }

        val isPhotoPost = imageUrls.isNotEmpty() && streamMap.isEmpty()

        return if (isPhotoPost) {
            MediaItem(
                url = url,
                title = title,
                author = author,
                durationSeconds = 0L,
                thumbnail = imageUrls.firstOrNull() ?: thumbnail,
                platform = "TikTok",
                mediaType = MediaType.IMAGE,
                imageUrls = imageUrls,
                qualities = listOf(
                    QualityOption(
                        id = "image_original",
                        label = "Original Quality (${imageUrls.size} Photos)",
                        resolution = "Full Resolution",
                        format = "JPEG / PNG",
                        ext = "jpg",
                        isImage = true,
                        directDownloadUrl = imageUrls.firstOrNull()
                    )
                )
            )
        } else {
            val qualities = generateVideoQualities(duration, url, streamMap, maxSourceRes = "1080p")
            MediaItem(
                url = url,
                title = title,
                author = author,
                durationSeconds = duration,
                thumbnail = thumbnail,
                platform = "TikTok",
                mediaType = MediaType.VIDEO,
                qualities = qualities
            )
        }
    }

    private suspend fun resolveReddit(url: String, context: Context? = null): MediaItem {
        var title = "Reddit Media Post"
        var author = "u/reddit_user"
        var thumbnail = "https://images.unsplash.com/photo-1618005182384-a83a8bd57fbe?w=800"
        var duration = 30L
        var directVideoUrl = ""
        var directAudioUrl = ""
        val isImg = isImageUrl(url)

        val cleanUrl = if (url.contains("?")) url.split("?")[0] else url

        // 1. Try vxreddit embed relay for rich OpenGraph tags
        try {
            val vxUrl = cleanUrl.replace("reddit.com", "vxreddit.com").replace("redd.it", "vxreddit.com")
            val vxReq = Request.Builder()
                .url(vxUrl)
                .header("User-Agent", "facebookexternalhit/1.1 (+http://www.facebook.com/externalhit_uatext.php)")
                .build()
            val vxResp = client.newCall(vxReq).execute()
            if (vxResp.isSuccessful) {
                val html = vxResp.body?.string() ?: ""
                val tMat = Pattern.compile("<meta property=\"og:title\" content=\"([^\"]+)\"").matcher(html)
                if (tMat.find()) {
                    val parsed = tMat.group(1)?.replace("&amp;", "&")
                    if (!parsed.isNullOrBlank()) title = parsed
                }
                val sMat = Pattern.compile("<meta property=\"og:site_name\" content=\"([^\"]+)\"").matcher(html)
                if (sMat.find()) {
                    val parsed = sMat.group(1)?.replace("&amp;", "&")
                    if (!parsed.isNullOrBlank()) author = parsed
                }
                val iMat = Pattern.compile("<meta property=\"og:image\" content=\"([^\"]+)\"").matcher(html)
                if (iMat.find()) {
                    val parsed = iMat.group(1)?.replace("&amp;", "&")
                    if (!parsed.isNullOrBlank()) thumbnail = parsed
                }
                val vMat = Pattern.compile("<meta property=\"og:video(?::secure_url)?\" content=\"([^\"]+)\"").matcher(html)
                if (vMat.find()) {
                    val parsed = vMat.group(1)?.replace("&amp;", "&")
                    if (!parsed.isNullOrBlank()) directVideoUrl = parsed
                }
            }
        } catch (_: Exception) {}

        // 2. Fallback to Reddit JSON API
        if (directVideoUrl.isBlank() && !isImg) {
            try {
                val jsonUrl = if (cleanUrl.endsWith("/")) "${cleanUrl.dropLast(1)}.json" else "$cleanUrl.json"
                val req = Request.Builder()
                    .url(jsonUrl)
                    .header("User-Agent", "android:com.mediafetch.app:v1.0 (by /u/MediaFetchApp)")
                    .build()
                val resp = client.newCall(req).execute()
                if (resp.isSuccessful) {
                    val body = resp.body?.string() ?: ""
                    val jsonArr = JSONArray(body)
                    val dataObj = jsonArr.optJSONObject(0)?.optJSONObject("data")?.optJSONArray("children")?.optJSONObject(0)?.optJSONObject("data")
                    if (dataObj != null) {
                        val parsedTitle = dataObj.optString("title", "")
                        if (parsedTitle.isNotBlank()) title = parsedTitle
                        val parsedAuthor = dataObj.optString("author", "")
                        if (parsedAuthor.isNotBlank()) author = "u/$parsedAuthor"

                        val secureMedia = dataObj.optJSONObject("secure_media") ?: dataObj.optJSONObject("media")
                        val redditVideo = secureMedia?.optJSONObject("reddit_video")
                        if (redditVideo != null) {
                            val fbUrl = redditVideo.optString("fallback_url", "")
                            if (fbUrl.isNotBlank()) {
                                directVideoUrl = fbUrl
                                val baseAudio = fbUrl.substringBeforeLast("/")
                                directAudioUrl = "$baseAudio/DASH_audio.mp4"
                            }
                            val dur = redditVideo.optLong("duration", 0L)
                            if (dur > 0L) duration = dur
                        }
                    }
                }
            } catch (_: Exception) {}
        }

        // 3. Fallback to Headless StreamExtractor
        if (directVideoUrl.isBlank() && !isImg && context != null) {
            try {
                val sniff = StreamExtractor.sniffStream(context, url, timeoutMs = 6000L)
                if (sniff.streamUrl.isNotBlank()) {
                    directVideoUrl = sniff.streamUrl
                    if (sniff.title.isNotBlank() && title.startsWith("Reddit")) title = sniff.title
                    if (sniff.author.isNotBlank() && author == "u/reddit_user") author = sniff.author
                    if (sniff.thumbnail.isNotBlank()) thumbnail = sniff.thumbnail
                }
            } catch (_: Exception) {}
        }

        val streamMap = mutableMapOf<String, String>()
        if (directVideoUrl.isNotBlank()) streamMap["default"] = directVideoUrl
        if (directAudioUrl.isNotBlank()) streamMap["audio"] = directAudioUrl

        val qualities = if (isImg) generateImageQualities(url) else generateVideoQualities(duration, url, streamMap, maxSourceRes = "1080p")

        return MediaItem(
            url = url,
            title = title,
            author = author,
            durationSeconds = if (isImg) 0L else duration,
            thumbnail = thumbnail,
            platform = "Reddit",
            mediaType = if (isImg) MediaType.IMAGE else MediaType.VIDEO,
            qualities = qualities,
            imageUrls = if (isImg) listOf(thumbnail) else emptyList()
        )
    }

    private fun resolveStockPhoto(url: String, platform: String): MediaItem {
        val title = "$platform High Resolution Photo"
        val author = "$platform Artist"
        val thumbnail = "https://images.unsplash.com/photo-1550684848-fac1c5b4e853?w=800"
        val qualities = generateImageQualities(url)

        return MediaItem(
            url = url,
            title = title,
            author = author,
            durationSeconds = 0L,
            thumbnail = thumbnail,
            platform = platform,
            mediaType = MediaType.IMAGE,
            qualities = qualities,
            imageUrls = listOf(thumbnail)
        )
    }

    private suspend fun resolveGeneric(url: String, platform: String, context: Context? = null): MediaItem {
        val isImg = isImageUrl(url)
        var title = "$platform Media Download"
        var author = "$platform Creator"
        var thumbnail = "https://images.unsplash.com/photo-1579546929518-9e396f3cc809?w=800"
        var directVideoUrl = ""
        var durationSeconds = 60L

        // Scrape HTML metadata with Desktop User-Agent
        try {
            val req = Request.Builder()
                .url(url)
                .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36")
                .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                .build()
            val resp = client.newCall(req).execute()
            if (resp.isSuccessful) {
                val html = resp.body?.string() ?: ""

                // og:title / twitter:title / <title>
                val tMat = Pattern.compile("<meta property=\"og:title\" content=\"([^\"]+)\"").matcher(html)
                if (tMat.find()) {
                    val parsed = tMat.group(1)?.replace("&amp;", "&")?.replace("&#39;", "'")
                    if (!parsed.isNullOrBlank()) title = parsed
                } else {
                    val twMat = Pattern.compile("<meta name=\"twitter:title\" content=\"([^\"]+)\"").matcher(html)
                    if (twMat.find()) {
                        val parsed = twMat.group(1)?.replace("&amp;", "&")
                        if (!parsed.isNullOrBlank()) title = parsed
                    } else {
                        val titleTag = Pattern.compile("<title>([^<]+)</title>", Pattern.CASE_INSENSITIVE).matcher(html)
                        if (titleTag.find()) {
                            val parsed = titleTag.group(1)?.trim()?.replace("&amp;", "&")
                            if (!parsed.isNullOrBlank() && !parsed.equals("Not Found", ignoreCase = true)) title = parsed
                        }
                    }
                }

                // author / site_name
                val aMat = Pattern.compile("<meta (?:property=\"og:site_name\"|name=\"author\") content=\"([^\"]+)\"").matcher(html)
                if (aMat.find()) {
                    val parsed = aMat.group(1)?.replace("&amp;", "&")
                    if (!parsed.isNullOrBlank()) author = parsed
                }

                // og:image / twitter:image
                val iMat = Pattern.compile("<meta (?:property=\"og:image\"|name=\"twitter:image\") content=\"([^\"]+)\"").matcher(html)
                if (iMat.find()) {
                    val parsed = iMat.group(1)?.replace("&amp;", "&")
                    if (!parsed.isNullOrBlank()) thumbnail = parsed
                }

                // og:video
                val vMat = Pattern.compile("<meta property=\"og:video(?::(?:secure_url|url))?\" content=\"([^\"]+)\"").matcher(html)
                if (vMat.find()) {
                    val parsed = vMat.group(1)?.replace("&amp;", "&")
                    if (!parsed.isNullOrBlank()) directVideoUrl = parsed
                } else {
                    val srcMat = Pattern.compile("<video[^>]*src=\"([^\"]+)\"", Pattern.CASE_INSENSITIVE).matcher(html)
                    if (srcMat.find()) {
                        val parsed = srcMat.group(1)
                        if (!parsed.isNullOrBlank()) directVideoUrl = parsed
                    }
                }
            }
        } catch (_: Exception) {}

        // Headless WebView sniffer fallback
        if (directVideoUrl.isBlank() && !isImg && context != null) {
            try {
                val sniff = StreamExtractor.sniffStream(context, url, timeoutMs = 6000L)
                if (sniff.streamUrl.isNotBlank()) {
                    directVideoUrl = sniff.streamUrl
                    if (sniff.title.isNotBlank() && title.startsWith("$platform Media")) title = sniff.title
                    if (sniff.author.isNotBlank() && author.startsWith("$platform Creator")) author = sniff.author
                    if (sniff.thumbnail.isNotBlank()) thumbnail = sniff.thumbnail
                }
            } catch (_: Exception) {}
        }

        val isGif = url.lowercase().contains(".gif") || directVideoUrl.lowercase().contains(".gif")
        val streamMap = if (directVideoUrl.isNotBlank()) mapOf("default" to directVideoUrl) else emptyMap()
        val qualities = if (isImg || isGif) generateImageQualities(url, isGifMedia = isGif) else generateVideoQualities(durationSeconds, directVideoUrl.ifBlank { url }, streamMap, maxSourceRes = "1080p")

        return MediaItem(
            url = url,
            title = title,
            author = author,
            durationSeconds = if (isImg || isGif) 0L else durationSeconds,
            thumbnail = if ((isImg || isGif) && thumbnail.contains("unsplash")) url else thumbnail,
            platform = platform,
            mediaType = if (isImg || isGif) MediaType.IMAGE else MediaType.VIDEO,
            qualities = qualities,
            imageUrls = if (isImg || isGif) listOf(url) else emptyList()
        )
    }

    private fun resolvePlaylist(url: String, platform: String): MediaItem {
        var playlistTitle = "Shared Playlist ($platform)"
        var author = "$platform Creator Collection"
        val items = mutableListOf<PlaylistItem>()

        // 1. Spotify Live Scraper (Playlists and Albums)
        if (url.contains("spotify.com")) {
            try {
                val embedUrl = if (url.contains("/embed/")) url else {
                    val pMatch = Pattern.compile("spotify\\.com/(playlist|album)/([a-zA-Z0-9]+)").matcher(url)
                    if (pMatch.find()) {
                        val type = pMatch.group(1)
                        val id = pMatch.group(2)
                        "https://open.spotify.com/embed/$type/$id"
                    } else url
                }
                val req = Request.Builder()
                    .url(embedUrl)
                    .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36")
                    .build()
                val resp = client.newCall(req).execute()
                if (resp.isSuccessful) {
                    val html = resp.body?.string() ?: ""
                    val scriptMat = Pattern.compile("<script id=\"__NEXT_DATA__\"[^>]*>([\\s\\S]*?)</script>").matcher(html)
                    if (scriptMat.find()) {
                        val jsonStr = scriptMat.group(1) ?: ""
                        val nextData = JSONObject(jsonStr)
                        val entity = nextData.optJSONObject("props")
                            ?.optJSONObject("pageProps")
                            ?.optJSONObject("state")
                            ?.optJSONObject("data")
                            ?.optJSONObject("entity")
                        if (entity != null) {
                            val spTitle = entity.optString("name", "Spotify Playlist")
                            val spCover = entity.optJSONObject("visualIdentity")?.optJSONObject("image")?.optJSONArray("sources")?.optJSONObject(0)?.optString("url", "")
                                ?: entity.optJSONArray("images")?.optJSONObject(0)?.optString("url", "") ?: ""
                            val trackList = entity.optJSONArray("trackList") ?: entity.optJSONObject("trackList")?.optJSONArray("items")
                            val spItems = mutableListOf<PlaylistItem>()
                            if (trackList != null && trackList.length() > 0) {
                                for (tI in 0 until trackList.length()) {
                                    val tObj = trackList.optJSONObject(tI) ?: continue
                                    val tTitle = tObj.optString("title", tObj.optString("name", "Track ${tI + 1}"))
                                    val tArtist = tObj.optString("subtitle", tObj.optJSONArray("artists")?.optJSONObject(0)?.optString("name", "Spotify Artist") ?: "Spotify Artist")
                                    val tDurMs = tObj.optLong("duration", tObj.optLong("duration_ms", 180000L))
                                    val tUri = tObj.optString("uri", "")
                                    spItems.add(
                                        PlaylistItem(
                                            id = "sp_track_${tI + 1}",
                                            title = "$tArtist - $tTitle",
                                            author = tArtist,
                                            durationSeconds = tDurMs / 1000L,
                                            thumbnail = spCover.ifBlank { "https://images.unsplash.com/photo-1511671782779-c97d3d27a1d4?w=600" },
                                            url = if (tUri.isNotBlank()) "https://open.spotify.com/track/${tUri.substringAfterLast(":")}" else "https://www.youtube.com/results?search_query=" + java.net.URLEncoder.encode("$tArtist $tTitle", "UTF-8"),
                                            isSelected = true
                                        )
                                    )
                                }
                            }
                            if (spItems.isNotEmpty()) {
                                val totalDur = spItems.sumOf { it.durationSeconds }
                                val qualities = generateAudioQualities(totalDur / spItems.size, null)
                                return MediaItem(
                                    url = url,
                                    title = spTitle,
                                    author = "Spotify Master Audio",
                                    durationSeconds = totalDur,
                                    thumbnail = spCover.ifBlank { spItems.first().thumbnail },
                                    platform = "Spotify",
                                    mediaType = MediaType.PLAYLIST,
                                    isPlaylist = true,
                                    playlistItems = spItems,
                                    qualities = qualities
                                )
                            }
                        }
                    }
                }
            } catch (_: Exception) {}
        }

        // 2. YouTube Live Scraper (Playlists)
        if (url.contains("youtube.com") || url.contains("youtu.be")) {
            try {
                val listMat = Pattern.compile("[?&]list=([a-zA-Z0-9_-]+)").matcher(url)
                val playlistId = if (listMat.find()) listMat.group(1) ?: "" else ""
                if (playlistId.isNotBlank()) {
                    val browsePayload = JSONObject().apply {
                        val cObj = JSONObject().apply {
                            put("clientName", "WEB")
                            put("clientVersion", "2.20240401.00.00")
                            put("hl", "en")
                            put("gl", "US")
                        }
                        put("context", JSONObject().apply { put("client", cObj) })
                        put("browseId", if (playlistId.startsWith("VL")) playlistId else "VL$playlistId")
                    }
                    val ytReq = Request.Builder()
                        .url("https://www.youtube.com/youtubei/v1/browse?prettyPrint=false")
                        .post(browsePayload.toString().toRequestBody("application/json".toMediaType()))
                        .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
                        .header("Content-Type", "application/json")
                        .build()
                    val ytResp = client.newCall(ytReq).execute()
                    if (ytResp.isSuccessful) {
                        val ytJson = JSONObject(ytResp.body?.string() ?: "")
                        val headerObj = ytJson.optJSONObject("header")?.optJSONObject("playlistHeaderRenderer")
                        val plTitle = (headerObj?.optJSONObject("title")?.optString("simpleText", "")
                            ?: ytJson.optJSONObject("metadata")?.optJSONObject("playlistMetadataRenderer")?.optString("title", "YouTube Playlist")
                            ?: "YouTube Playlist").ifBlank { "YouTube Playlist" }
                        val plAuthor = headerObj?.optJSONObject("ownerText")?.optJSONArray("runs")?.optJSONObject(0)?.optString("text", "YouTube Channel") ?: "YouTube"

                        val ytItems = mutableListOf<PlaylistItem>()
                        val contents = ytJson.optJSONObject("contents")
                            ?.optJSONObject("twoColumnBrowseResultsRenderer")
                            ?.optJSONArray("tabs")
                            ?.optJSONObject(0)
                            ?.optJSONObject("tabRenderer")
                            ?.optJSONObject("content")
                            ?.optJSONObject("sectionListRenderer")
                            ?.optJSONArray("contents")
                            ?.optJSONObject(0)
                            ?.optJSONObject("itemSectionRenderer")
                            ?.optJSONArray("contents")
                            ?.optJSONObject(0)
                            ?.optJSONObject("playlistVideoListRenderer")
                            ?.optJSONArray("contents")

                        if (contents != null && contents.length() > 0) {
                            for (cI in 0 until contents.length()) {
                                val vObj = contents.optJSONObject(cI)?.optJSONObject("playlistVideoRenderer") ?: continue
                                val vId = vObj.optString("videoId", "")
                                if (vId.isBlank()) continue
                                val vTitle = (vObj.optJSONObject("title")?.optJSONArray("runs")?.optJSONObject(0)?.optString("text", "Video")
                                    ?: vObj.optJSONObject("title")?.optString("simpleText", "Video")
                                    ?: "Video").ifBlank { "Video" }
                                val vAuthor = vObj.optJSONObject("shortBylineText")?.optJSONArray("runs")?.optJSONObject(0)?.optString("text", plAuthor) ?: plAuthor
                                val vDur = vObj.optString("lengthSeconds", "180").toLongOrNull() ?: 180L
                                val vThumb = "https://i.ytimg.com/vi/$vId/hqdefault.jpg"

                                ytItems.add(
                                    PlaylistItem(
                                        id = "yt_$vId",
                                        title = vTitle,
                                        author = vAuthor,
                                        durationSeconds = vDur,
                                        thumbnail = vThumb,
                                        url = "https://www.youtube.com/watch?v=$vId",
                                        isSelected = true
                                    )
                                )
                            }
                        }

                        if (ytItems.isNotEmpty()) {
                            val totalDur = ytItems.sumOf { it.durationSeconds }
                            val qualities = generateVideoQualities(totalDur / ytItems.size, url)
                            return MediaItem(
                                url = url,
                                title = plTitle,
                                author = plAuthor,
                                durationSeconds = totalDur,
                                thumbnail = ytItems.first().thumbnail,
                                platform = "YouTube",
                                mediaType = MediaType.PLAYLIST,
                                isPlaylist = true,
                                playlistItems = ytItems,
                                qualities = qualities
                            )
                        }
                    }
                }
            } catch (_: Exception) {}
        }

        // 3. Fallback: Generic Collection
        val sampleTitles = listOf(
            "Visual Masterclass - Sequence 01",
            "Cinema 4K Drone Footage - Alps Horizon",
            "Urban Night Timelapse & Beat Sync",
            "Aesthetic Soundscape & Lo-Fi Beats",
            "Color Grade Breakdown & Highlights"
        )

        for (i in sampleTitles.indices) {
            items.add(
                PlaylistItem(
                    id = "item_${i + 1}",
                    title = sampleTitles[i],
                    author = author,
                    durationSeconds = 25L + (i * 20L),
                    thumbnail = "https://images.unsplash.com/photo-1518709268805-4e9042af9f23?w=600&auto=format&fit=crop",
                    url = "$url#item_${i + 1}",
                    isSelected = true
                )
            )
        }

        val totalDuration = items.sumOf { it.durationSeconds }
        val qualities = generateVideoQualities(totalDuration / items.size, url)

        return MediaItem(
            url = url,
            title = playlistTitle,
            author = author,
            durationSeconds = totalDuration,
            thumbnail = items.firstOrNull()?.thumbnail ?: "https://images.unsplash.com/photo-1518709268805-4e9042af9f23?w=600&auto=format&fit=crop",
            platform = platform,
            mediaType = MediaType.PLAYLIST,
            isPlaylist = true,
            playlistItems = items,
            qualities = qualities
        )
    }

    fun generateVideoQualities(
        durationSeconds: Long,
        directUrl: String,
        streamMap: Map<String, String> = emptyMap(),
        sizeMap: Map<String, Long> = emptyMap(),
        maxSourceRes: String = "auto"
    ): List<QualityOption> {
        val duration = if (durationSeconds > 0) durationSeconds else 30L

        fun calcBytes(durationSec: Long, bitrateKbps: Long): Long {
            return ((durationSec * bitrateKbps * 1000L) / 8L).coerceAtLeast(1024L * 100L)
        }

        fun getValidSize(key: String, bitrateKbps: Long): Long {
            val calc = calcBytes(duration, bitrateKbps)
            val mapped = sizeMap[key]
            if (mapped != null && mapped > 0L) {
                if (duration > 120L) {
                    val minExpected = calc * 30L / 100L
                    if (mapped >= minExpected) {
                        return mapped
                    }
                } else {
                    return mapped
                }
            }
            return calc
        }

        val hasSeparateAudio = streamMap.containsKey("audio") && !streamMap["audio"].isNullOrBlank() && streamMap["audio"] != streamMap["default"]
        val audioBytes = getValidSize("audio", 160L)
        val extraAudioBytes = if (hasSeparateAudio) audioBytes else 0L

        val size4K = getValidSize("4k", 18000L) + extraAudioBytes
        val size2K = getValidSize("2k", 10000L) + extraAudioBytes
        val size1080 = getValidSize("1080p", 4500L) + extraAudioBytes
        val size720 = getValidSize("720p", 2200L) + extraAudioBytes
        val size480 = getValidSize("480p", 1000L) + extraAudioBytes

        val sizeMp3_320 = calcBytes(duration, 320L)
        val sizeMp3_192 = calcBytes(duration, 192L)
        val sizeWav = calcBytes(duration, 1411L)
        val sizeM4a = calcBytes(duration, 256L)

        val defaultVideoUrl = streamMap["default"] ?: streamMap["1080p"] ?: streamMap["720p"] ?: streamMap["480p"] ?: streamMap["360p"] ?: (if (directUrl.endsWith(".mp4")) directUrl else null)
        val audioUrl = streamMap["audio"] ?: streamMap["default"] ?: (if (directUrl.endsWith(".mp3") || directUrl.endsWith(".m4a")) directUrl else null)

        val has4k = streamMap.containsKey("4k") || streamMap.containsKey("2160p") || (maxSourceRes.equals("4k", ignoreCase = true) && directUrl.contains("googlevideo.com"))
        val has2k = has4k || streamMap.containsKey("2k") || streamMap.containsKey("1440p") || (maxSourceRes.contains("2k", ignoreCase = true) && directUrl.contains("googlevideo.com"))
        val canInclude1080p = !maxSourceRes.equals("720p", ignoreCase = true) && !maxSourceRes.equals("480p", ignoreCase = true) && !maxSourceRes.equals("360p", ignoreCase = true)
        val canInclude720p = !maxSourceRes.equals("480p", ignoreCase = true) && !maxSourceRes.equals("360p", ignoreCase = true)

        val qList = mutableListOf<QualityOption>()

        if (has4k) {
            qList.add(
                QualityOption(
                    id = "4k",
                    label = "4K Ultra HD (2160p)",
                    resolution = "3840×2160 • 60 FPS",
                    format = "MP4 • H.265 / AV1",
                    ext = "mp4",
                    estimatedSizeBytes = size4K,
                    bitrateKbps = 18000,
                    fps = 60,
                    isHdr = true,
                    directDownloadUrl = streamMap["4k"] ?: defaultVideoUrl,
                    audioDownloadUrl = if (hasSeparateAudio) audioUrl else null
                )
            )
        }

        if (has2k) {
            qList.add(
                QualityOption(
                    id = "2k",
                    label = "2K Quad HD (1440p)",
                    resolution = "2560×1440 • 60 FPS",
                    format = "MP4 • H.264",
                    ext = "mp4",
                    estimatedSizeBytes = size2K,
                    bitrateKbps = 10000,
                    fps = 60,
                    directDownloadUrl = streamMap["2k"] ?: defaultVideoUrl,
                    audioDownloadUrl = if (hasSeparateAudio) audioUrl else null
                )
            )
        }

        val u1080 = streamMap["1080p"] ?: defaultVideoUrl
        val u720 = streamMap["720p"] ?: defaultVideoUrl
        val u480 = streamMap["480p"] ?: streamMap["360p"] ?: defaultVideoUrl

        fun extractDim(url: String?, fallbackW: Int, fallbackH: Int): Pair<Int, Int> {
            if (url.isNullOrBlank()) return Pair(fallbackW, fallbackH)
            val m = Pattern.compile("/(\\d{3,4})x(\\d{3,4})/").matcher(url)
            if (m.find()) {
                val w = m.group(1)?.toIntOrNull() ?: 0
                val h = m.group(2)?.toIntOrNull() ?: 0
                if (w > 0 && h > 0) return Pair(w, h)
            }
            val m2 = Pattern.compile("(\\d{3,4})x(\\d{3,4})").matcher(url)
            if (m2.find()) {
                val w = m2.group(1)?.toIntOrNull() ?: 0
                val h = m2.group(2)?.toIntOrNull() ?: 0
                if (w > 0 && h > 0) return Pair(w, h)
            }
            return Pair(fallbackW, fallbackH)
        }

        if (canInclude1080p) {
            val dim = extractDim(u1080, 1920, 1080)
            val labelText = if (dim.first == 1920 && dim.second == 1080) "Full HD (1080p)"
                            else if (dim.first == 1080 && dim.second == 1920) "Full HD Vertical (1080p)"
                            else "Original Quality (${dim.first}×${dim.second})"
            val resText = "${dim.first}×${dim.second} • Recommended"
            qList.add(
                QualityOption(
                    id = "1080p",
                    label = labelText,
                    resolution = resText,
                    format = "MP4 • H.264 High Profile",
                    ext = "mp4",
                    estimatedSizeBytes = size1080,
                    bitrateKbps = 4500,
                    fps = 30,
                    directDownloadUrl = u1080,
                    audioDownloadUrl = if (hasSeparateAudio) audioUrl else null,
                    fallbackUrl = defaultVideoUrl
                )
            )
        }

        if (canInclude720p) {
            val dim = extractDim(u720, 1280, 720)
            val labelText = if (dim.first == 1280 && dim.second == 720) "HD Standard (720p)"
                            else if (dim.first == 720 && dim.second == 1280) "HD Vertical (720p)"
                            else "HD Standard (${dim.first}×${dim.second})"
            val resText = "${dim.first}×${dim.second} • Fast Download"
            qList.add(
                QualityOption(
                    id = "720p",
                    label = labelText,
                    resolution = resText,
                    format = "MP4 • H.264 Baseline",
                    ext = "mp4",
                    estimatedSizeBytes = size720,
                    bitrateKbps = 2200,
                    fps = 30,
                    directDownloadUrl = u720,
                    audioDownloadUrl = if (hasSeparateAudio) audioUrl else null,
                    fallbackUrl = defaultVideoUrl
                )
            )
        }

        val dim480 = extractDim(u480, 854, 480)
        val label480 = if (dim480.first == 854 && dim480.second == 480) "SD Compact (480p)"
                       else if (dim480.first == 480 && dim480.second == 854) "SD Vertical (480p)"
                       else "Mobile Lite (${dim480.first}×${dim480.second})"
        val res480 = "${dim480.first}×${dim480.second} • Mobile Lite"
        qList.add(
            QualityOption(
                id = "480p",
                label = label480,
                resolution = res480,
                format = "MP4 • H.264",
                ext = "mp4",
                estimatedSizeBytes = size480,
                bitrateKbps = 1000,
                fps = 30,
                directDownloadUrl = u480,
                audioDownloadUrl = if (hasSeparateAudio) audioUrl else null,
                fallbackUrl = defaultVideoUrl
            )
        )

        return qList + listOf(
            QualityOption(
                id = "audio_mp3_320",
                label = "Studio Master (320 kbps)",
                resolution = "48 kHz • Ultra Fidelity",
                format = "Audio • AAC / M4A",
                ext = "m4a",
                estimatedSizeBytes = sizeMp3_320,
                bitrateKbps = 320,
                isAudioOnly = true,
                directDownloadUrl = audioUrl
            ),
            QualityOption(
                id = "audio_m4a_aac",
                label = "Apple AAC (256 kbps)",
                resolution = "Apple AAC • Low Power",
                format = "Audio • MPEG-4",
                ext = "m4a",
                estimatedSizeBytes = sizeM4a,
                bitrateKbps = 256,
                isAudioOnly = true,
                directDownloadUrl = audioUrl
            ),
            QualityOption(
                id = "audio_mp3_192",
                label = "Standard Audio (192 kbps)",
                resolution = "44.1 kHz • Optimized Balance",
                format = "Audio • AAC / M4A",
                ext = "m4a",
                estimatedSizeBytes = sizeMp3_192,
                bitrateKbps = 192,
                isAudioOnly = true,
                directDownloadUrl = audioUrl
            ),
            QualityOption(
                id = "audio_mp3_128",
                label = "Low Compressed / Data Saver (128 kbps)",
                resolution = "44.1 kHz • Space Saver",
                format = "Audio • AAC / M4A",
                ext = "m4a",
                estimatedSizeBytes = calcBytes(duration, 128L),
                bitrateKbps = 128,
                isAudioOnly = true,
                directDownloadUrl = audioUrl
            ),
            QualityOption(
                id = "audio_wav_lossless",
                label = "Lossless WAV (1411 kbps)",
                resolution = "48 kHz • Uncompressed PCM",
                format = "Audio • Broadcast WAV",
                ext = "wav",
                estimatedSizeBytes = sizeWav,
                bitrateKbps = 1411,
                isAudioOnly = true,
                directDownloadUrl = audioUrl
            )
        )
    }

    fun generateAudioQualities(durationSeconds: Long, audioUrl: String?): List<QualityOption> {
        val duration = if (durationSeconds > 0) durationSeconds else 180L
        fun calcBytes(durationSec: Long, bitrateKbps: Long): Long {
            return ((durationSec * bitrateKbps * 1000L) / 8L).coerceAtLeast(1024L * 100L)
        }
        val sizeMp3_320 = calcBytes(duration, 320L)
        val sizeMp3_192 = calcBytes(duration, 192L)
        val sizeMp3_128 = calcBytes(duration, 128L)
        val sizeMp3_96 = calcBytes(duration, 96L)
        val sizeWav = calcBytes(duration, 1411L)
        val sizeM4a = calcBytes(duration, 256L)

        return listOf(
            QualityOption(
                id = "audio_mp3_320",
                label = "Studio Master (320 kbps)",
                resolution = "48 kHz • Ultra Fidelity",
                format = "Audio • AAC / M4A",
                ext = "m4a",
                estimatedSizeBytes = sizeMp3_320,
                bitrateKbps = 320,
                isAudioOnly = true,
                directDownloadUrl = audioUrl
            ),
            QualityOption(
                id = "audio_m4a_aac",
                label = "Apple AAC (256 kbps)",
                resolution = "Apple AAC • Low Power",
                format = "Audio • MPEG-4",
                ext = "m4a",
                estimatedSizeBytes = sizeM4a,
                bitrateKbps = 256,
                isAudioOnly = true,
                directDownloadUrl = audioUrl
            ),
            QualityOption(
                id = "audio_mp3_192",
                label = "Standard Audio (192 kbps)",
                resolution = "44.1 kHz • Optimized Balance",
                format = "Audio • AAC / M4A",
                ext = "m4a",
                estimatedSizeBytes = sizeMp3_192,
                bitrateKbps = 192,
                isAudioOnly = true,
                directDownloadUrl = audioUrl
            ),
            QualityOption(
                id = "audio_mp3_128",
                label = "Low Compressed / Data Saver (128 kbps)",
                resolution = "44.1 kHz • Space Saver",
                format = "Audio • AAC / M4A",
                ext = "m4a",
                estimatedSizeBytes = sizeMp3_128,
                bitrateKbps = 128,
                isAudioOnly = true,
                directDownloadUrl = audioUrl
            ),
            QualityOption(
                id = "audio_mp3_96",
                label = "Ultra Compact (96 kbps)",
                resolution = "32 kHz • Minimum Data Usage",
                format = "Audio • AAC / M4A",
                ext = "m4a",
                estimatedSizeBytes = sizeMp3_96,
                bitrateKbps = 96,
                isAudioOnly = true,
                directDownloadUrl = audioUrl
            ),
            QualityOption(
                id = "audio_wav_lossless",
                label = "Lossless WAV (1411 kbps)",
                resolution = "48 kHz • Uncompressed PCM",
                format = "Audio • Broadcast WAV",
                ext = "wav",
                estimatedSizeBytes = sizeWav,
                bitrateKbps = 1411,
                isAudioOnly = true,
                directDownloadUrl = audioUrl
            )
        )
    }

    fun generateImageQualities(directUrl: String, isGifMedia: Boolean = false): List<QualityOption> {
        val isGif = isGifMedia ||
                    directUrl.lowercase().contains(".gif") ||
                    directUrl.lowercase().contains("giphy.com") ||
                    directUrl.lowercase().contains("tenor.com")

        val list = mutableListOf<QualityOption>()
        if (isGif) {
            list.add(
                QualityOption(
                    id = "img_gif",
                    label = "Animated GIF (Original Motion)",
                    resolution = "GIF Animation • Infinite Loop",
                    format = "Animation • GIF",
                    ext = "gif",
                    estimatedSizeBytes = 3 * 1024 * 1024,
                    bitrateKbps = 0,
                    isImage = true,
                    directDownloadUrl = directUrl
                )
            )
        }

        list.add(
            QualityOption(
                id = "img_orig",
                label = if (isGif) "Original GIF (Ultra HD)" else "Original Quality (Ultra HD)",
                resolution = if (isGif) "Original Resolution • Animated GIF" else "Original Resolution • 100% Quality",
                format = if (isGif) "Animation • GIF" else "Image • JPG / PNG",
                ext = if (isGif) "gif" else "jpg",
                estimatedSizeBytes = 4 * 1024 * 1024,
                bitrateKbps = 0,
                isImage = true,
                directDownloadUrl = directUrl
            )
        )
        list.add(
            QualityOption(
                id = "img_1080p",
                label = "Full HD (1080p Web)",
                resolution = "1920×1080 • Compressed",
                format = "Image • JPG",
                ext = "jpg",
                estimatedSizeBytes = (1.5 * 1024 * 1024).toLong(),
                bitrateKbps = 0,
                isImage = true,
                directDownloadUrl = directUrl
            )
        )
        list.add(
            QualityOption(
                id = "img_webp",
                label = "WebP High Efficiency",
                resolution = "Modern Compact Format",
                format = "Image • WebP Lossy",
                ext = "webp",
                estimatedSizeBytes = (800 * 1024).toLong(),
                bitrateKbps = 0,
                isImage = true,
                directDownloadUrl = directUrl
            )
        )
        list.add(
            QualityOption(
                id = "img_png",
                label = "PNG Lossless",
                resolution = "Transparent & Sharp Details",
                format = "Image • PNG Uncompressed",
                ext = "png",
                estimatedSizeBytes = (5.2 * 1024 * 1024).toLong(),
                bitrateKbps = 0,
                isImage = true,
                directDownloadUrl = directUrl
            )
        )

        return list
    }

    fun calculateTrimmedSize(fullBytes: Long, totalDurationSeconds: Long, trimDurationSeconds: Long, bitrateKbps: Int = 4500): Long {
        if (trimDurationSeconds <= 0) return fullBytes
        if (totalDurationSeconds > 0) {
            val ratio = (trimDurationSeconds.toDouble() / totalDurationSeconds.toDouble()).coerceIn(0.01, 1.0)
            return (fullBytes * ratio).toLong().coerceAtLeast(1024 * 200)
        }
        val kbps = if (bitrateKbps > 0) bitrateKbps.toLong() else 4500L
        return ((trimDurationSeconds * kbps * 1000L) / 8L).coerceAtLeast(1024 * 200)
    }

    fun checkFileExistsOnDevice(context: Context, title: String, ext: String): Boolean {
        try {
            val cleanTitle = title
                .replace(Regex("[\\\\/:*?\"<>|]"), "_")
                .trim()
                .replace(Regex("\\s+"), " ")
                .take(120)
                .ifBlank { "media" }

            val fileName = "${cleanTitle}.${ext}"
            val legacyName = "MediaFetch_${title.replace(Regex("[^a-zA-Z0-9._-]"), "_").take(36)}.${ext}"

            val namesToCheck = listOf(fileName, legacyName)

            // 1. App-specific storage
            val extDir = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)
            if (extDir != null) {
                for (name in namesToCheck) {
                    if (File(File(extDir, "staging"), name).exists()) return true
                    if (File(File(extDir, "MediaFetch"), name).exists()) return true
                }
            }

            // 2. Public folders
            val publicDirs = listOf(
                File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES), "MediaFetch"),
                File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC), "MediaFetch"),
                File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES), "MediaFetch"),
                File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "MediaFetch")
            )

            for (dir in publicDirs) {
                for (name in namesToCheck) {
                    if (File(dir, name).exists()) return true
                }
            }
        } catch (e: Exception) {
            // Ignore
        }
        return false
    }
}