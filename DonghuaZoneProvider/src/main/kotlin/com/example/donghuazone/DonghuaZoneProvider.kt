package com.example.donghuazone

import android.util.Log
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.network.CloudflareKiller
import org.jsoup.nodes.Element

class DonghuaZoneProvider : MainAPI() {

    override var mainUrl = "https://www.donghuazone.com"
    override var name = "DonghuaZone"
    override val hasMainPage = true
    override var lang = "multi"
    override val hasQuickSearch = true
    override val supportedTypes = setOf(
        TvType.Anime,
        TvType.TvSeries,
        TvType.Movie
    )

    companion object {
        private const val TAG = "DonghuaZone"
        private const val USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
        private const val MAX_CLOUDFLARE_RETRIES = 3
    }

    private val cloudflareKiller = CloudflareKiller()

    private fun getHeaders(): Map<String, String> = mapOf(
        "User-Agent" to USER_AGENT,
        "Referer" to "$mainUrl/",
        "Accept-Language" to "es-ES,es;q=0.9,en;q=0.8,id;q=0.7",
        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/webp,*/*;q=0.8"
    )

    private suspend fun getDocument(url: String): org.jsoup.nodes.Document? {
        var lastException: Exception? = null
        
        for (attempt in 1..MAX_CLOUDFLARE_RETRIES) {
            try {
                Log.d(TAG, "Fetching URL attempt $attempt/$MAX_CLOUDFLARE_RETRIES: $url")
                
                val response = app.get(url, headers = getHeaders(), interceptor = cloudflareKiller, timeout = 30000)
                val document = response.document
                val title = document.title()
                
                Log.d(TAG, "Response code: ${response.code}, title: $title")

                if (response.code in listOf(403, 503, 429) ||
                    title.contains("Just a moment", ignoreCase = true) ||
                    title.contains("Cloudflare", ignoreCase = true) ||
                    title.contains("Checking your browser", ignoreCase = true)) {
                    
                    Log.d(TAG, "Cloudflare challenge detected, waiting before retry...")
                    Thread.sleep(3000)
                    continue
                }

                if (document.text().length < 100) {
                    Log.d(TAG, "Document seems empty or minimal, retrying...")
                    Thread.sleep(2000)
                    continue
                }

                Log.d(TAG, "Successfully fetched document with title: $title")
                return document
                
            } catch (e: Exception) {
                lastException = e
                Log.e(TAG, "Exception fetching URL: ${e.message}")
                if (attempt < MAX_CLOUDFLARE_RETRIES) {
                    Thread.sleep(2500)
                }
            }
        }
        
        Log.e(TAG, "Failed to fetch URL after $MAX_CLOUDFLARE_RETRIES attempts, last exception: ${lastException?.message}")
        return null
    }

    override val mainPage = mainPageOf(
        "$mainUrl/" to "Últimos episodios",
        "$mainUrl/search/label/Series?max-results=20" to "Series",
        "$mainUrl/search/label/Movies?max-results=20" to "Movies"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = if (page == 1) {
            request.data
        } else {
            if (request.data.contains("?")) {
                "${request.data}&start-index=${((page - 1) * 20) + 1}"
            } else {
                "${request.data}?start-index=${((page - 1) * 20) + 1}"
            }
        }
        
        Log.d(TAG, "getMainPage URL: $url")
        
        val doc = getDocument(url) ?: return newHomePageResponse(request.name, emptyList(), false)

        val homeItems = mutableListOf<SearchResponse>()
        
        doc.select(".post-outer, article.post, div.post, .article, .post-outer-container").forEach { element ->
            val result = element.toSearchResult()
            if (result != null && homeItems.none { it.url == result.url }) {
                homeItems.add(result)
            }
        }
        
        if (homeItems.isEmpty()) {
            doc.select("a[href*='donghuazone.com']").forEach { link ->
                val href = link.attr("href")
                if (href.contains("/20") && href.endsWith(".html")) {
                    val title = link.text().trim()
                    if (title.isNotBlank() && title.length > 3) {
                        val img = link.selectFirst("img")
                        val poster = img?.attr("src") ?: img?.attr("data-src")
                        homeItems.add(newAnimeSearchResponse(title, href, TvType.Anime) {
                            this.posterUrl = poster
                        })
                    }
                }
            }
        }

        Log.d(TAG, "Found ${homeItems.size} items on main page")
        return newHomePageResponse(request.name, homeItems, hasNext = homeItems.size >= 15)
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val aTag = selectFirst("h3.post-title a, h2.post-title a, .post-title a, a.timestamp-link") 
            ?: selectFirst("a[href*='.html']") 
            ?: return null
        
        val href = aTag.attr("href")
        if (href.isBlank() || !href.contains("donghuazone.com") && !href.contains("/20")) return null
        
        val url = when {
            href.startsWith("http") -> href
            href.startsWith("/") -> "$mainUrl$href"
            else -> "$mainUrl/$href"
        }
        
        if (!url.contains("donghuazone.com") || !url.endsWith(".html")) return null
        
        val title = aTag.text().trim().ifBlank { 
            aTag.attr("title").ifBlank { 
                selectFirst(".post-title")?.text()?.trim() 
            } 
        } ?: selectFirst("img")?.attr("alt")?.trim() ?: ""

        if (title.isBlank()) return null
        
        val imgTag = selectFirst("img.post-thumbnail, img.post-image, .post-thumbnail img, .post-image img, img")
        val poster = imgTag?.attr("data-src")?.ifBlank { 
            imgTag?.attr("src")?.ifBlank { 
                imgTag?.attr("data-original") 
            } 
        }
        
        val qualityLabel = selectFirst(".label, .post-labels a, .labels a")?.text()
        val quality = when {
            title.contains("4K", true) || qualityLabel?.contains("4K", true) == true -> SearchQuality.FourK
            title.contains("1080p", true) || qualityLabel?.contains("1080p", true) == true -> SearchQuality.HD
            title.contains("720p", true) || qualityLabel?.contains("720p", true) == true -> SearchQuality.HD
            else -> null
        }

        val type = when {
            url.contains("/pelicula/") || url.contains("/movie/") || title.contains("movie", true) -> TvType.Movie
            else -> TvType.Anime
        }
        
        return if (type == TvType.Movie) {
            newMovieSearchResponse(title, url, type) {
                this.posterUrl = poster
                this.quality = quality
            }
        } else {
            newAnimeSearchResponse(title, url, type) {
                this.posterUrl = poster
                this.quality = quality
            }
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val searchUrl = "$mainUrl/search?q=${query.encodeUri()}&max-results=20"
        Log.d(TAG, "Searching with URL: $searchUrl")
        
        val doc = getDocument(searchUrl) ?: return emptyList()

        val results = mutableListOf<SearchResponse>()
        
        doc.select(".post-outer, article.post, div.post, .article, .post-outer-container").forEach { element ->
            val result = element.toSearchResult()
            if (result != null && results.none { it.url == result.url }) {
                results.add(result)
            }
        }
        
        if (results.isEmpty()) {
            doc.select("a[href*='donghuazone.com'][href*='.html']").forEach { link ->
                val href = link.attr("href")
                if (href.contains("/20") || href.contains("episode") || href.contains("season")) {
                    val title = link.text().trim()
                    if (title.isNotBlank() && title.length > 3 && !title.contains("Download")) {
                        val img = link.selectFirst("img")
                        val poster = img?.attr("src") ?: img?.attr("data-src")
                        results.add(newAnimeSearchResponse(title, href, TvType.Anime) {
                            this.posterUrl = poster
                        })
                    }
                }
            }
        }

        Log.d(TAG, "Search found ${results.size} results for query: $query")
        return results.distinctBy { it.url }
    }

    override suspend fun load(url: String): LoadResponse? {
        Log.d(TAG, "Loading URL: $url")

        val doc = getDocument(url) ?: run {
            Log.e(TAG, "Failed to load document for URL: $url")
            return null
        }

        val title = doc.selectFirst("h1.post-title, h3.post-title, .post-title h1, .post-title h3, h1, h2.title")?.text()?.trim()
            ?: doc.selectFirst("title")?.text()?.split("–")?.firstOrNull()?.trim()
            ?: return null

        Log.d(TAG, "Found title: $title")

        var poster = doc.selectFirst("meta[property='og:image']")?.attr("content")
        if (poster.isNullOrBlank()) {
            poster = doc.selectFirst(".post-body img, .entry-content img, .post-thumbnail img, article img")?.let { img ->
                img.attr("data-src").ifBlank {
                    img.attr("src").ifBlank {
                        img.attr("data-original")
                    }
                }
            }
        }

        Log.d(TAG, "Poster: $poster")

        val plot = doc.selectFirst(".post-body, .entry-content, .post-content, article")?.text()?.trim()
            ?: doc.selectFirst("meta[name='description']")?.attr("content")

        val genres = doc.select(".labels a, .post-labels a, .tags a, a[rel='tag'], .label a").map { it.text().trim() }.filter { it.isNotBlank() }

        val statusText = doc.selectFirst(".status, .labels a:contains(Ongoing), .labels a:contains(Completed), .label:contains(Ongoing), .label:contains(Completed)")?.text() ?: ""
        val status = when {
            statusText.contains("Ongoing", true) || statusText.contains("Updating", true) || statusText.contains("Emisión", true) -> ShowStatus.Ongoing
            statusText.contains("Completed", true) || statusText.contains("End", true) || statusText.contains("Finalizado", true) -> ShowStatus.Completed
            else -> ShowStatus.Ongoing
        }

        val type = when {
            url.contains("/pelicula/") || url.contains("/movie/") || title.contains("Movie", true) -> TvType.Movie
            else -> TvType.Anime
        }

        if (type == TvType.Movie) {
            return newMovieLoadResponse(title, url, type, url) {
                this.posterUrl = poster
                this.plot = plot
                this.tags = genres
            }
        }

        val episodes = mutableListOf<Episode>()
        val processedUrls = mutableSetOf<String>()

        Log.d(TAG, "Searching for episodes in page...")

        extractEpisodesFromScripts(doc, processedUrls, episodes)

        if (episodes.isEmpty()) {
            extractEpisodesFromLinks(doc, url, processedUrls, episodes)
        }

        if (episodes.isEmpty()) {
            extractEpisodesFromTable(doc, url, processedUrls, episodes)
        }

        if (episodes.isEmpty()) {
            extractEpisodesFromNavigation(doc, url, processedUrls, episodes)
        }

        if (episodes.isEmpty()) {
            extractEpisodesFromJsonData(doc, url, processedUrls, episodes)
        }

        if (episodes.isEmpty()) {
            Log.d(TAG, "No episode list found, creating single episode for direct playback")
            episodes.add(newEpisode(url) {
                this.name = "Ver Ahora"
                this.episode = 1
            })
        }

        Log.d(TAG, "Found ${episodes.size} episodes")
        val sortedEpisodes = episodes.sortedBy { it.episode }

        return newAnimeLoadResponse(title, url, type) {
            this.posterUrl = poster
            this.plot = plot
            this.tags = genres
            this.showStatus = status
            addEpisodes(DubStatus.Subbed, sortedEpisodes)
        }
    }

    private fun extractEpisodesFromScripts(doc: org.jsoup.nodes.Document, processedUrls: MutableSet<String>, episodes: MutableList<Episode>) {
        doc.select("script").forEach { script ->
            val content = script.html()

            if (content.contains("episode") || content.contains("Episode") || content.contains("eps")) {
                val patterns = listOf(
                    Regex("""["']id["']\s*:\s*["']([^"']+)["'][^}]*?["']title["']\s*:\s*["']([^"']*)["']"""),
                    Regex("""episode[s]?\s*[:=]\s*\[([^\]]+)\]""", RegexOption.IGNORE_CASE),
                    Regex("""\{[^}]*?(?:episode|ep)[^}]*?url\s*[:=]\s*["']([^"']+)["'][^}]*?""", RegexOption.IGNORE_CASE),
                    Regex("""data\s*[:=]\s*\[([^\]]+)\]"""),
                    Regex("""eps\s*[:=]\s*\[([^\]]+)\]""", RegexOption.IGNORE_CASE)
                )

                for (pattern in patterns) {
                    pattern.findAll(content).forEach { match ->
                        val groups = match.groupValues
                        if (groups.size >= 2) {
                            val potentialUrl = groups.getOrNull(1) ?: continue
                            val potentialTitle = groups.getOrNull(2) ?: ""

                            if (potentialUrl.contains("donghuazone") && potentialUrl.contains(".html") && !processedUrls.contains(potentialUrl)) {
                                processedUrls.add(potentialUrl)
                                val epNum = extractEpisodeNumber(potentialUrl, potentialTitle)
                                episodes.add(newEpisode(potentialUrl) {
                                    this.name = if (epNum != null) "Episode $epNum" else potentialTitle.ifBlank { "Episode ${episodes.size + 1}" }
                                    this.episode = epNum ?: (episodes.size + 1)
                                })
                            }
                        }
                    }
                }
            }
        }
    }

    private fun extractEpisodesFromLinks(doc: org.jsoup.nodes.Document, pageUrl: String, processedUrls: MutableSet<String>, episodes: MutableList<Episode>) {
        doc.select("a[href*='episode-'], a[href*='episode_']").forEach { epLink ->
            val epUrl = epLink.absUrl("href").ifBlank { epLink.attr("href") }
            val cleanUrl = if (epUrl.startsWith("//")) "https:$epUrl" else epUrl

            if (cleanUrl.isNotBlank() && cleanUrl.contains("donghuazone") &&
                !processedUrls.contains(cleanUrl) && cleanUrl != pageUrl) {
                processedUrls.add(cleanUrl)

                val epText = epLink.text().trim()
                val epNum = extractEpisodeNumber(cleanUrl, epText)

                episodes.add(newEpisode(cleanUrl) {
                    this.name = if (epNum != null) "Episode $epNum" else epText.ifBlank { "Episode ${episodes.size + 1}" }
                    this.episode = epNum ?: (episodes.size + 1)
                })
            }
        }

        doc.select(".post-body a, .entry-content a, .post-content a").forEach { link ->
            val href = link.attr("href")
            if (href.contains("/20") && href.endsWith(".html") && href != pageUrl) {
                val fullUrl = if (href.startsWith("http")) href else "$mainUrl$href"
                if (!processedUrls.contains(fullUrl) && (href.contains("episode") || href.contains("Episode"))) {
                    processedUrls.add(fullUrl)
                    val text = link.text().trim()
                    val epNum = extractEpisodeNumber(fullUrl, text)

                    episodes.add(newEpisode(fullUrl) {
                        this.name = if (epNum != null) "Episode $epNum" else text.ifBlank { "Episode ${episodes.size + 1}" }
                        this.episode = epNum ?: (episodes.size + 1)
                    })
                }
            }
        }
    }

    private fun extractEpisodesFromTable(doc: org.jsoup.nodes.Document, pageUrl: String, processedUrls: MutableSet<String>, episodes: MutableList<Episode>) {
        doc.select("table, .episode-list, .episodes, .episode-table, .ep-list").forEach { container ->
            container.select("tr, li, .episode-item, .ep-item, .episode, td").forEach { item ->
                val link = item.selectFirst("a[href]")
                val href = link?.attr("href") ?: item.attr("data-href")

                if (href.isNotBlank() && href.contains("donghuazone") && href.contains(".html")) {
                    val fullUrl = if (href.startsWith("http")) href else "$mainUrl$href"
                    if (!processedUrls.contains(fullUrl) && fullUrl != pageUrl) {
                        processedUrls.add(fullUrl)
                        val text = link?.text()?.trim() ?: item.text().trim()
                        val epNum = extractEpisodeNumber(fullUrl, text)

                        episodes.add(newEpisode(fullUrl) {
                            this.name = if (epNum != null) "Episode $epNum" else text.ifBlank { "Episode ${episodes.size + 1}" }
                            this.episode = epNum ?: (episodes.size + 1)
                        })
                    }
                }
            }
        }
    }

    private fun extractEpisodesFromNavigation(doc: org.jsoup.nodes.Document, pageUrl: String, processedUrls: MutableSet<String>, episodes: MutableList<Episode>) {
        doc.select("nav a, .nav a, .menu a, .pagination a, .pager a").forEach { link ->
            val href = link.attr("href")
            if (href.contains("donghuazone") && href.contains(".html") && href != pageUrl &&
                (href.contains("episode") || href.contains("Episode"))) {
                val fullUrl = if (href.startsWith("http")) href else "$mainUrl$href"
                if (!processedUrls.contains(fullUrl)) {
                    processedUrls.add(fullUrl)
                    val text = link.text().trim()
                    val epNum = extractEpisodeNumber(fullUrl, text)

                    episodes.add(newEpisode(fullUrl) {
                        this.name = if (epNum != null) "Episode $epNum" else text.ifBlank { "Episode ${episodes.size + 1}" }
                        this.episode = epNum ?: (episodes.size + 1)
                    })
                }
            }
        }

        doc.select("select option, .dropdown-menu a, .dropdown-item").forEach { item ->
            val href = item.attr("value")?.takeIf { it.contains(".html") }
                ?: item.attr("data-href")?.takeIf { it.contains(".html") }
                ?: item.attr("href")?.takeIf { it.contains(".html") && it.contains("donghuazone") }

            if (!href.isNullOrBlank()) {
                val fullUrl = if (href.startsWith("http")) href else "$mainUrl$href"
                if (!processedUrls.contains(fullUrl) && fullUrl != pageUrl) {
                    processedUrls.add(fullUrl)
                    val text = item.text().trim()
                    val epNum = extractEpisodeNumber(fullUrl, text)

                    episodes.add(newEpisode(fullUrl) {
                        this.name = if (epNum != null) "Episode $epNum" else text.ifBlank { "Episode ${episodes.size + 1}" }
                        this.episode = epNum ?: (episodes.size + 1)
                    })
                }
            }
        }
    }

    private fun extractEpisodesFromJsonData(doc: org.jsoup.nodes.Document, pageUrl: String, processedUrls: MutableSet<String>, episodes: MutableList<Episode>) {
        val jsonPatterns = listOf(
            Regex("""\{[^{}]*(?:episode|url|slug)[^{}]*\}"""),
            Regex("""\[[\d,\s]+\]"""),
            Regex(""""episodes"\s*:\s*\[([^\]]+)\]"""),
            Regex(""""links"\s*:\s*\[([^\]]+)\]""")
        )

        doc.select("script").forEach { script ->
            val content = script.html()
            if (content.contains("episode") || content.contains("data") || content.contains("json")) {
                jsonPatterns.forEach { pattern ->
                    pattern.findAll(content).forEach { match ->
                        val jsonStr = match.value
                        val urlMatches = Regex("""https?://[^\s"']+\.html""").findAll(jsonStr)
                        urlMatches.forEach { urlMatch ->
                            val epUrl = urlMatch.value
                            if (epUrl.contains("donghuazone") && !processedUrls.contains(epUrl) && epUrl != pageUrl) {
                                processedUrls.add(epUrl)
                                val epNum = extractEpisodeNumber(epUrl, "")
                                episodes.add(newEpisode(epUrl) {
                                    this.name = if (epNum != null) "Episode $epNum" else "Episode ${episodes.size + 1}"
                                    this.episode = epNum ?: (episodes.size + 1)
                                })
                            }
                        }
                    }
                }
            }
        }
    }

    private fun extractEpisodeNumber(url: String, text: String): Int? {
        val patterns = listOf(
            Regex("""episode[- ]?(\d+)""", RegexOption.IGNORE_CASE),
            Regex("""episode\s*(\d+)[-\s](\d+)""", RegexOption.IGNORE_CASE),
            Regex("""[eE]p\.?\s*(\d+)"""),
            Regex("""/(\d{2,4})(?:/|\.html|$)"""),
            Regex("""-(\d{2,4})\.html""")
        )

        for (pattern in patterns) {
            val match = pattern.find(text) ?: pattern.find(url)
            if (match != null) {
                val groups = match.groupValues
                val num = groups.getOrNull(1)?.toIntOrNull()
                if (num != null && num > 0 && num < 10000) {
                    return num
                }
            }
        }
        return null
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        Log.d(TAG, "loadLinks called for: $data")
        
        val doc = getDocument(data) ?: run {
            Log.e(TAG, "Failed to get document in loadLinks")
            return false
        }
        
        var found = false

        doc.select("iframe[src], iframe[data-src]").forEach { iframe ->
            val src = iframe.attr("src").ifBlank { iframe.attr("data-src") }
            if (src.isNotBlank()) {
                val fullUrl = if (src.startsWith("//")) "https:$src" else src
                if (fullUrl.startsWith("http") && !fullUrl.contains("youtube.com") && !fullUrl.contains("googletagmanager")) {
                    Log.d(TAG, "Found iframe: $fullUrl")
                    loadExtractor(fullUrl, data, subtitleCallback, callback)
                    found = true
                }
            }
        }

        doc.select("video source, video").forEach { video ->
            val src = video.attr("src").ifBlank { 
                video.selectFirst("source")?.attr("src") 
            }
            if (!src.isNullOrBlank() && src.startsWith("http")) {
                Log.d(TAG, "Found video source: $src")
                loadExtractor(src, data, subtitleCallback, callback)
                found = true
            }
        }

        doc.select(".player-embed, .video-embed, .embed-container iframe, .player-container iframe").forEach { embed ->
            val src = embed.attr("src").ifBlank { embed.attr("data-src") }
            if (!src.isNullOrBlank()) {
                val fullUrl = if (src.startsWith("//")) "https:$src" else src
                if (fullUrl.startsWith("http")) {
                    Log.d(TAG, "Found embed: $fullUrl")
                    loadExtractor(fullUrl, data, subtitleCallback, callback)
                    found = true
                }
            }
        }

        doc.select("script").forEach { script ->
            val content = script.html()
            if (content.contains("iframe") || content.contains("player") || content.contains("embed")) {
                val iframeRegex = Regex("""src\s*[:=]\s*['"]?([^'"&\s]+)['"]?""")
                iframeRegex.findAll(content).forEach { match ->
                    val src = match.groupValues[1]
                    if (src.startsWith("http") && !src.contains("youtube.com")) {
                        Log.d(TAG, "Found iframe in script: $src")
                        loadExtractor(src, data, subtitleCallback, callback)
                        found = true
                    }
                }
                
                val videoUrlRegex = Regex("""(?:video|media|source|file)\s*[:=]\s*['"]([^'"]+)['"]""", RegexOption.IGNORE_CASE)
                videoUrlRegex.findAll(content).forEach { match ->
                    val url = match.groupValues[1]
                    if (url.startsWith("http")) {
                        Log.d(TAG, "Found video URL in script: $url")
                        loadExtractor(url, data, subtitleCallback, callback)
                        found = true
                    }
                }
            }
        }

        doc.select("a[href*='player'], a[href*='embed'], a[href*='video'], a[data-video], .download-btn, .play-btn").forEach { link ->
            val href = link.attr("href").ifBlank { link.attr("data-video") }
            if (!href.isNullOrBlank() && href.startsWith("http") && !href.contains("youtube.com")) {
                Log.d(TAG, "Found link: $href")
                loadExtractor(href, data, subtitleCallback, callback)
                found = true
            }
        }

        Log.d(TAG, "loadLinks finished, found: $found")
        return found
    }

    private fun String.encodeUri(): String = java.net.URLEncoder.encode(this, "UTF-8")
}
