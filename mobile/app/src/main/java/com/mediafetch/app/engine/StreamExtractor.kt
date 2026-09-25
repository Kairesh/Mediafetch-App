package com.mediafetch.app.engine

import android.annotation.SuppressLint
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import kotlinx.coroutines.suspendCancellableCoroutine
import org.json.JSONObject
import java.util.Collections
import java.util.concurrent.atomic.AtomicBoolean
import java.util.regex.Pattern
import kotlin.coroutines.resume

data class SnifferResult(
    val streamUrl: String = "",
    val title: String = "",
    val author: String = "",
    val thumbnail: String = "",
    val durationSeconds: Long = 0L,
    val carouselSlides: List<JSONObject> = emptyList(),
    val imageUrls: List<String> = emptyList()
)

object StreamExtractor {

    private val mainHandler = Handler(Looper.getMainLooper())
    private val ytPattern = Pattern.compile("(?:v=|shorts/|youtu\\.be/|embed/|live/|/v/)([a-zA-Z0-9_-]{11})")
    private val igPattern = Pattern.compile("instagram\\.com/(?:p|reel|reels|tv)/([a-zA-Z0-9_-]+)", Pattern.CASE_INSENSITIVE)

    fun extractYouTubeId(url: String): String {
        val clean = url.trim()
        val matcher = ytPattern.matcher(clean)
        return if (matcher.find()) matcher.group(1) ?: "" else ""
    }

    fun extractInstagramShortcode(url: String): String {
        val matcher = igPattern.matcher(url)
        return if (matcher.find()) matcher.group(1) ?: "" else ""
    }

    suspend fun sniffStream(context: Context, url: String, timeoutMs: Long = 10000L): SnifferResult =
        suspendCancellableCoroutine { cont ->
            extractRealStream(context, url, timeoutMs) { result ->
                if (cont.isActive) cont.resume(result)
            }
        }

    fun extractRealStream(context: Context, url: String, onStreamFound: (String) -> Unit) {
        extractRealStream(context, url, 10000L) { result ->
            onStreamFound(result.streamUrl)
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    fun extractRealStream(context: Context, url: String, timeoutMs: Long = 12000L, onResult: (SnifferResult) -> Unit) {
        val platform = MediaEngine.detectPlatform(url)

        mainHandler.post {
            try {
                val resolved = AtomicBoolean(false)
                val capturedTitle = StringBuilder()
                val capturedAuthor = StringBuilder()
                val capturedThumb = StringBuilder()
                val pendingHlsStream = StringBuilder()
                val capturedSlides = Collections.synchronizedList(mutableListOf<JSONObject>())
                val interceptedCdnImages = Collections.synchronizedList(mutableListOf<String>())

                val webView = WebView(context.applicationContext)

                val cookieManager = CookieManager.getInstance()
                cookieManager.setAcceptCookie(true)
                try {
                    cookieManager.setAcceptThirdPartyCookies(webView, true)
                } catch (_: Exception) {}

                val desktopUa = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"

                webView.settings.apply {
                    javaScriptEnabled = true
                    domStorageEnabled = true
                    mediaPlaybackRequiresUserGesture = false
                    databaseEnabled = true
                    allowContentAccess = true
                    allowFileAccess = true
                    userAgentString = desktopUa
                    cacheMode = WebSettings.LOAD_DEFAULT
                    mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                }

                val cleanup = {
                    mainHandler.post {
                        try {
                            webView.stopLoading()
                            webView.removeJavascriptInterface("MediaFetchSniffer")
                            webView.destroy()
                        } catch (_: Exception) {}
                    }
                }

                val finishWith = { streamUrl: String ->
                    if (resolved.compareAndSet(false, true)) {
                        mainHandler.removeCallbacksAndMessages(null)
                        cleanup()

                        val finalSlides = if (capturedSlides.isNotEmpty()) {
                            capturedSlides.toList()
                        } else if (interceptedCdnImages.size > 1) {
                            interceptedCdnImages.mapIndexed { idx, cdnUrl ->
                                JSONObject().apply {
                                    put("slideIndex", idx + 1)
                                    put("url", cdnUrl)
                                    put("thumbnail", cdnUrl)
                                    put("mediaType", "image")
                                }
                            }
                        } else {
                            emptyList()
                        }

                        val finalThumb = when {
                            capturedThumb.isNotEmpty() -> capturedThumb.toString().trim()
                            finalSlides.isNotEmpty() -> finalSlides[0].optString("thumbnail", finalSlides[0].optString("url", ""))
                            interceptedCdnImages.isNotEmpty() -> interceptedCdnImages[0]
                            else -> ""
                        }

                        val finalImgUrls = if (finalSlides.isNotEmpty()) {
                            finalSlides.map { it.optString("url", "") }.filter { it.isNotBlank() }
                        } else if (interceptedCdnImages.isNotEmpty()) {
                            interceptedCdnImages.toList()
                        } else if (finalThumb.isNotBlank()) {
                            listOf(finalThumb)
                        } else {
                            emptyList()
                        }

                        onResult(
                            SnifferResult(
                                streamUrl = streamUrl,
                                title = capturedTitle.toString().trim(),
                                author = capturedAuthor.toString().trim(),
                                thumbnail = finalThumb,
                                carouselSlides = finalSlides,
                                imageUrls = finalImgUrls
                            )
                        )
                    }
                }

                val timeoutRunnable = Runnable {
                    finishWith("")
                }
                mainHandler.postDelayed(timeoutRunnable, timeoutMs)

                class SnifferBridge {
                    @JavascriptInterface
                    fun onMetadata(metaJson: String) {
                        try {
                            val json = JSONObject(metaJson)
                            val t = json.optString("title", "")
                            val a = json.optString("author", "")
                            val th = json.optString("thumbnail", "")
                            val vSrc = json.optString("videoSrc", "")
                            val slidesArr = json.optJSONArray("carouselSlides")

                            if (t.isNotBlank() && capturedTitle.isEmpty()) capturedTitle.append(t)
                            if (a.isNotBlank() && capturedAuthor.isEmpty()) capturedAuthor.append(a)
                            if (th.isNotBlank() && capturedThumb.isEmpty()) capturedThumb.append(th)

                            if (slidesArr != null && slidesArr.length() > 0) {
                                synchronized(capturedSlides) {
                                    capturedSlides.clear()
                                    for (i in 0 until slidesArr.length()) {
                                        val sObj = slidesArr.optJSONObject(i) ?: continue
                                        capturedSlides.add(sObj)
                                    }
                                }
                            }

                            if (vSrc.isNotBlank() && (vSrc.startsWith("http://") || vSrc.startsWith("https://")) && !vSrc.startsWith("blob:")) {
                                if (vSrc.contains(".mp4") || !vSrc.contains(".m3u8")) {
                                    finishWith(vSrc)
                                } else {
                                    if (pendingHlsStream.isEmpty()) pendingHlsStream.append(vSrc)
                                    mainHandler.postDelayed({
                                        if (!resolved.get()) finishWith(pendingHlsStream.toString())
                                    }, 1200L)
                                }
                            } else if (capturedSlides.size > 1) {
                                mainHandler.postDelayed({
                                    finishWith("")
                                }, 600L)
                            } else if (th.isNotBlank() && (url.contains("/p/") || platform == "Pinterest")) {
                                mainHandler.postDelayed({
                                    if (capturedSlides.isEmpty() && capturedThumb.isNotEmpty()) {
                                        finishWith("")
                                    }
                                }, 1500L)
                            }
                        } catch (_: Exception) {}
                    }
                }
                webView.addJavascriptInterface(SnifferBridge(), "MediaFetchSniffer")

                webView.webViewClient = object : WebViewClient() {
                    override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest?): WebResourceResponse? {
                        val reqUrl = request?.url?.toString() ?: ""
                        val reqLower = reqUrl.lowercase()
                        val accept = request?.requestHeaders?.get("Accept")?.lowercase() ?: ""

                        // 1. Strictly ignore static assets, images, styles, scripts, analytics
                        val isImageOrStatic = reqLower.endsWith(".jpg") || reqLower.endsWith(".jpeg") ||
                            reqLower.endsWith(".png") || reqLower.endsWith(".webp") || reqLower.endsWith(".gif") ||
                            reqLower.endsWith(".svg") || reqLower.endsWith(".ico") || reqLower.endsWith(".css") ||
                            reqLower.endsWith(".js") || reqLower.endsWith(".woff") || reqLower.endsWith(".woff2") ||
                            reqLower.endsWith(".ttf") || reqLower.contains(".jpg?") || reqLower.contains(".jpeg?") ||
                            reqLower.contains(".png?") || reqLower.contains(".webp?") || reqLower.contains("favicon") ||
                            reqLower.contains("/rsrc.php/") || reqLower.contains("/logging/") || reqLower.contains("/ajax/") ||
                            reqLower.contains("analytics") || reqLower.contains("telemetry") || reqLower.contains("pixel")

                        if (isImageOrStatic) {
                            val isAvatar = reqLower.contains("150x150") || reqLower.contains("avatar") ||
                                reqLower.contains("profile") || reqLower.contains("t51.2885-19") ||
                                reqLower.contains("/s150x150/") || reqLower.contains("/s320x320/")

                            val isCdnImage = (reqLower.contains("cdninstagram.com") || reqLower.contains("fbcdn.net") ||
                                reqLower.contains("twimg.com") || reqLower.contains("tiktokcdn.com") ||
                                reqLower.contains("pinimg.com")) &&
                                (reqLower.contains(".jpg") || reqLower.contains(".webp") || reqLower.contains(".png")) &&
                                !reqLower.contains("rsrc.php")

                            if (!isAvatar && isCdnImage) {
                                if (capturedThumb.isEmpty()) {
                                    capturedThumb.append(reqUrl)
                                }
                                val baseNoQuery = reqUrl.substringBefore("?")
                                synchronized(interceptedCdnImages) {
                                    if (interceptedCdnImages.none { it.substringBefore("?") == baseNoQuery }) {
                                        interceptedCdnImages.add(reqUrl)
                                    }
                                }
                            }
                            return super.shouldInterceptRequest(view, request)
                        }

                        // 2. Identify authentic VIDEO streams
                        val isInstagramVideo = (reqLower.contains("cdninstagram.com") || reqLower.contains("fbcdn.net")) &&
                            (reqLower.contains(".mp4") || reqLower.contains("/v/t50.") || reqLower.contains("video_url") || reqLower.contains("/bytestart/"))

                        val isTwitterVideo = reqLower.contains("video.twimg.com") && (reqLower.contains(".mp4") || reqLower.contains(".m3u8"))

                        val isTikTokVideo = (reqLower.contains("tiktokcdn.com") || reqLower.contains("byteoversea.com")) &&
                            (reqLower.contains(".mp4") || reqLower.contains("mime_type=video_mp4") || reqLower.contains("/video/tos/"))

                        val isRedditVideo = (reqLower.contains("v.redd.it") || reqLower.contains("packaged-media.redd.it")) &&
                            (reqLower.contains(".mp4") || reqLower.contains(".m3u8") || reqLower.contains("dash_"))

                        val isPinterestVideo = (reqLower.contains("pinimg.com") || reqLower.contains("pinterest.com")) &&
                            (reqLower.contains(".mp4") || reqLower.contains(".m3u8") || reqLower.contains("/videos/"))

                        val isYouTubeVideo = reqLower.contains("googlevideo.com/videoplayback")

                        val isGenericVideo = (reqLower.contains(".mp4") || reqLower.contains(".m3u8") || reqLower.contains(".webm")) &&
                            !reqLower.contains("blank") && !reqLower.contains("dummy")

                        val isVideoAccept = accept.contains("video/") && !accept.contains("image/")

                        val isRealVideo = isInstagramVideo || isTwitterVideo || isTikTokVideo || isRedditVideo ||
                            isPinterestVideo || isYouTubeVideo || isGenericVideo || isVideoAccept

                        if (isRealVideo) {
                            val isCarouselCandidate = url.contains("/p/") || url.contains("/post/")
                            if (isCarouselCandidate) {
                                // For carousel posts, let the JS carousel crawler advance slides and harvest everything first
                                synchronized(capturedSlides) {
                                    if (capturedSlides.none { it.optString("url") == reqUrl }) {
                                        capturedSlides.add(JSONObject().apply {
                                            put("slideIndex", capturedSlides.size + 1)
                                            put("url", reqUrl)
                                            put("thumbnail", if (capturedThumb.isNotEmpty()) capturedThumb.toString() else reqUrl)
                                            put("mediaType", "video")
                                        })
                                    }
                                }
                                mainHandler.postDelayed({
                                    finishWith(reqUrl)
                                }, 1800L)
                            } else {
                                if (isTwitterVideo && reqLower.contains(".m3u8")) {
                                    // If Twitter master HLS playlist requested, allow grace period for direct MP4 requests
                                    mainHandler.postDelayed({
                                        finishWith(reqUrl)
                                    }, 600L)
                                } else {
                                    val isDirectMp4 = reqLower.contains(".mp4") || isTikTokVideo || isInstagramVideo
                                    if (isDirectMp4) {
                                        if (capturedTitle.isEmpty() || capturedThumb.isEmpty()) {
                                            mainHandler.postDelayed({
                                                finishWith(reqUrl)
                                            }, 400L)
                                        } else {
                                            finishWith(reqUrl)
                                        }
                                    } else {
                                        finishWith(reqUrl)
                                    }
                                }
                            }
                        }

                        return super.shouldInterceptRequest(view, request)
                    }

                    override fun onPageFinished(view: WebView?, targetUrl: String?) {
                        super.onPageFinished(view, targetUrl)
                        view?.evaluateJavascript(
                            """
                            (function() {
                                var checkCount = 0;
                                var poller = setInterval(function() {
                                    checkCount++;
                                    if (checkCount > 25) {
                                        clearInterval(poller);
                                        return;
                                    }
                                    try {
                                        var closeSelectors = [
                                            'svg[aria-label="Close"]',
                                            '[aria-label="Close"]',
                                            'button[aria-label="Close"]',
                                            'div[role="dialog"] button',
                                            'button[data-testid="cookie-policy-manage-dialog-accept-button"]',
                                            'button._a9--._ap36._a9_0',
                                            '.cookie-banner button',
                                            'div[aria-label="Decline optional cookies"]'
                                        ];
                                        for (var i = 0; i < closeSelectors.length; i++) {
                                            var el = document.querySelector(closeSelectors[i]);
                                            if (el) {
                                                var b = el.tagName === 'BUTTON' ? el : el.closest('button') || el;
                                                b.click();
                                            }
                                        }

                                        var title = '';
                                        var titleSelectors = [
                                            '.Caption',
                                            '.CaptionComments',
                                            '[data-testid="post-comment-root"]',
                                            'article h1',
                                            'article h2',
                                            '[data-e2e="browse-video-desc"]',
                                            'h1',
                                            'meta[property="og:title"]',
                                            'meta[name="twitter:title"]',
                                            'meta[property="og:description"]',
                                            'meta[name="description"]'
                                        ];
                                        for (var i = 0; i < titleSelectors.length; i++) {
                                            var el = document.querySelector(titleSelectors[i]);
                                            if (el) {
                                                var t = el.content || el.innerText || el.textContent || '';
                                                t = t.trim();
                                                if (t && t !== 'Instagram' && !t.startsWith('Login') && !t.startsWith('Log In')) {
                                                    title = t;
                                                    break;
                                                }
                                            }
                                        }
                                        if (!title) title = document.title || '';

                                        var author = '';
                                        var authorSelectors = [
                                            '.UsernameText',
                                            '.headerUsername',
                                            '[data-e2e="browse-username"]',
                                            'a[role="link"] > span',
                                            'header a[role="link"]',
                                            'meta[name="author"]',
                                            'meta[property="og:site_name"]'
                                        ];
                                        for (var i = 0; i < authorSelectors.length; i++) {
                                            var el = document.querySelector(authorSelectors[i]);
                                            if (el) {
                                                var a = el.content || el.innerText || el.textContent || '';
                                                a = a.trim();
                                                if (a && a !== 'Instagram' && a !== 'TikTok' && a !== 'X') {
                                                    author = a.startsWith('@') ? a : '@' + a;
                                                    break;
                                                }
                                            }
                                        }

                                        var thumb = '';
                                        var videoEl = document.querySelector('video');
                                        if (videoEl && videoEl.poster && !videoEl.poster.includes('blank')) {
                                            thumb = videoEl.poster;
                                        }
                                        if (!thumb) {
                                            var mediaImg = document.querySelector('.EmbeddedMediaImage, .Poster, img.EmbeddedMediaImage, [data-testid="post-image"]');
                                            if (mediaImg && mediaImg.src) thumb = mediaImg.src;
                                        }
                                        if (!thumb) {
                                            var ogImg = document.querySelector('meta[property="og:image"], meta[name="twitter:image"]');
                                            if (ogImg && ogImg.content) {
                                                var c = ogImg.content;
                                                if (!c.includes('150x150') && !c.includes('t51.2885-19') && !c.includes('avatar') && !c.includes('profile')) {
                                                    thumb = c;
                                                }
                                            }
                                        }
                                        if (!thumb) {
                                            var allImgs = Array.from(document.querySelectorAll('img'));
                                            for (var i = 0; i < allImgs.length; i++) {
                                                var s = allImgs[i].src || '';
                                                if (s.includes('cdninstagram') || s.includes('fbcdn') || s.includes('twimg') || s.includes('tiktok')) {
                                                    if (!s.includes('150x150') && !s.includes('t51.2885-19') && !s.includes('avatar') && !s.includes('profile')) {
                                                        thumb = s;
                                                        break;
                                                    }
                                                }
                                            }
                                        }

                                        var videoSrc = '';
                                        if (videoEl) {
                                            videoSrc = videoEl.currentSrc || videoEl.src || '';
                                            if (!videoSrc) {
                                                var srcEl = videoEl.querySelector('source');
                                                if (srcEl) videoSrc = srcEl.src || '';
                                            }
                                        }

                                        // Carousel Multi-Slide DOM Extractor & Auto-advancer
                                        var slides = [];
                                        var seenSrcs = {};
                                        var slideIdx = 1;

                                        // Harvest slide elements
                                        var slideEls = document.querySelectorAll('.CarouselItem, li.CarouselItem, [data-testid="carousel-item"], ul li img, .slide, [role="tabpanel"], .EmbeddedMediaImage, article img, article video');
                                        slideEls.forEach(function(el) {
                                            var img = el.tagName === 'IMG' ? el : el.querySelector('img');
                                            var vid = el.tagName === 'VIDEO' ? el : el.querySelector('video');
                                            var vUrl = vid ? (vid.currentSrc || vid.src || '') : '';
                                            var iUrl = img ? (img.currentSrc || img.src || '') : '';

                                            var sUrl = vUrl || iUrl;
                                            if (sUrl && !seenSrcs[sUrl] && !sUrl.includes('150x150') && !sUrl.includes('t51.2885-19') && !sUrl.includes('avatar') && !sUrl.includes('profile')) {
                                                seenSrcs[sUrl] = true;
                                                slides.push({
                                                    slideIndex: slideIdx++,
                                                    url: sUrl,
                                                    thumbnail: iUrl || vUrl,
                                                    mediaType: vUrl ? 'video' : 'image'
                                                });
                                            }
                                        });

                                        if (slides.length === 0) {
                                            var allDomImgs = Array.from(document.querySelectorAll('article img, .EmbeddedMedia img, img.EmbeddedMediaImage, .post-container img'));
                                            allDomImgs.forEach(function(img) {
                                                var isrc = img.currentSrc || img.src || '';
                                                if (isrc && (isrc.includes('cdninstagram') || isrc.includes('fbcdn') || isrc.includes('pinimg')) &&
                                                    !isrc.includes('150x150') && !isrc.includes('t51.2885-19') && !isrc.includes('avatar') && !seenSrcs[isrc]) {
                                                    seenSrcs[isrc] = true;
                                                    slides.push({
                                                        slideIndex: slideIdx++,
                                                        url: isrc,
                                                        thumbnail: isrc,
                                                        mediaType: 'image'
                                                    });
                                                }
                                            });
                                        }

                                        // Auto-click carousel navigation chevrons to reveal next slides
                                        var nextButtons = document.querySelectorAll('button[aria-label="Next"], button[aria-label="Next slide"], button._af65, .coreSpriteRightChevron, button[data-testid="carousel-next-button"], div[role="button"][aria-label="Next"]');
                                        nextButtons.forEach(function(btn) {
                                            try { btn.click(); } catch(e) {}
                                        });

                                        if (window.MediaFetchSniffer) {
                                            window.MediaFetchSniffer.onMetadata(JSON.stringify({
                                                title: title,
                                                author: author,
                                                thumbnail: thumb,
                                                videoSrc: videoSrc,
                                                carouselSlides: slides
                                            }));
                                        }

                                        if (videoEl) {
                                            videoEl.muted = true;
                                            videoEl.play().catch(function(e) {});
                                        }
                                        var playBtns = document.querySelectorAll('.play-button, .PlayButton, button[aria-label*="Play"], div[role="button"][aria-label*="Play"], .ytp-large-play-button, .EmbeddedMedia');
                                        playBtns.forEach(function(b) { b.click(); });
                                    } catch(e) {}
                                }, 300);
                            })();
                            """.trimIndent(),
                            null
                        )
                    }
                }

                val targetUrl = when {
                    platform == "YouTube" -> {
                        val vid = extractYouTubeId(url)
                        if (vid.isNotBlank()) "https://www.youtube.com/embed/$vid?autoplay=1&mute=1" else url
                    }
                    platform == "Instagram" -> {
                        val sc = extractInstagramShortcode(url)
                        if (sc.isNotBlank()) {
                            if (url.contains("/reel/") || url.contains("/reels/")) {
                                "https://www.instagram.com/reel/$sc/embed/captioned/"
                            } else {
                                "https://www.instagram.com/p/$sc/embed/captioned/"
                            }
                        } else url
                    }
                    else -> url
                }

                val headers = HashMap<String, String>()
                headers["Referer"] = when (platform) {
                    "YouTube" -> "https://www.youtube.com/"
                    "Instagram" -> "https://www.instagram.com/"
                    "TikTok" -> "https://www.tiktok.com/"
                    "X / Twitter" -> "https://x.com/"
                    else -> url
                }
                headers["User-Agent"] = desktopUa

                webView.loadUrl(targetUrl, headers)

            } catch (e: Exception) {
                onResult(SnifferResult())
            }
        }
    }
}