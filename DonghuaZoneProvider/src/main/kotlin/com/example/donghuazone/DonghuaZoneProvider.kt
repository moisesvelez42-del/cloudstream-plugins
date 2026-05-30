package com.example.donghuazone

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.network.CloudflareKiller
import org.jsoup.nodes.Element
import org.json.JSONObject

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

    private val cloudflareKiller = CloudflareKiller()

    private suspend fun getDocument(url: String): org.jsoup.nodes.Document {
        val requestHeaders = mapOf(
            "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36",
            "Referer" to "$mainUrl/",
            "Accept-Language" to "es-ES,es;q=0.9,en;q=0.8,id;q=0.7"
        )
        var response = app.get(url, headers = requestHeaders, interceptor = cloudflareKiller)

        if (response.code in listOf(403, 503) || 
            response.document.title().contains("Just a moment", true) ||
            response.document.title().contains("Cloudflare", true)) {
            Thread.sleep(2500)
            response = app.get(url, headers = requestHeaders, interceptor = cloudflareKiller)
        }

        return response.document
    }

    override val mainPage = mainPageOf(
        "$mainUrl/" to "Últimos episodios"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = if (page == 1) request.data else "$mainUrl/search?updated-max=..." // Blogger pagination is tricky via just page numbers, often start-index is used
        // Using start-index for paginating simple mainPage
        val pagedUrl = if (page == 1) mainUrl else "$mainUrl/search?max-results=10&start-index=${(page - 1) * 10 + 1}"
        
        val doc = getDocument(pagedUrl)

        val homeItems = mutableListOf<SearchResponse>()
        doc.select("article.post-outer-container, article.post, div.post-outer, div.item").forEach { element ->
            val result = element.toSearchResult()
            if (result != null && homeItems.none { it.url == result.url }) {
                homeItems.add(result)
            }
        }

        return newHomePageResponse(request.name, homeItems, hasNext = homeItems.isNotEmpty())
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val aTag = selectFirst("h3.post-title a, h2.post-title a, a.timestamp-link") ?: selectFirst("a") ?: return null
        val href = aTag.attr("href")
        if (href.isBlank()) return null
        
        val url = if (href.startsWith("http")) href else "$mainUrl$href"
        
        val title = aTag.text().trim().ifBlank { 
            aTag.attr("title").ifBlank { selectFirst(".post-title")?.text()?.trim() } 
        } ?: ""

        val imgTag = selectFirst("img, .post-body img, .post-thumbnail img, .post-image img")
        val poster = imgTag?.attr("data-src")?.ifBlank { imgTag.attr("src") }
        
        val qualityLabel = selectFirst("a.label, span.label")?.text()
        val quality = when {
            title.contains("4K", true) || (qualityLabel?.contains("4K", true) == true) -> SearchQuality.FourK
            title.contains("1080p", true) || (qualityLabel?.contains("1080p", true) == true) -> SearchQuality.HD
            else -> null
        }

        val type = if (url.contains("/pelicula/") || title.contains("Movie", true)) TvType.Movie else TvType.Anime
        
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
        val doc = getDocument("$mainUrl/search?q=label:Series+${query.encodeUri()}&max-results=10")
        
        val results = mutableListOf<SearchResponse>()
        doc.select("article.post-outer-container, article.post, div.post-outer, div.item").forEach { element ->
            val result = element.toSearchResult()
            if (result != null && results.none { it.url == result.url }) {
                results.add(result)
            }
        }
        return results
    }

    override suspend fun load(url: String): LoadResponse? {
        val doc = getDocument(url)

        val title = doc.selectFirst("h1.post-title, h3.post-title")?.text()?.trim() ?: return null

        var poster = doc.selectFirst("meta[property='og:image']")?.attr("content")
        if (poster.isNullOrBlank()) {
            poster = doc.selectFirst(".post-body img")?.attr("data-src")?.ifBlank {
                doc.selectFirst(".post-body img")?.attr("src")
            }
        }

        val plot = doc.selectFirst(".post-body, .entry-content")?.text()?.trim()

        val genres = doc.select(".labels a, span.tag a, a[rel='tag']").map { it.text().trim() }
        
        val statusText = doc.selectFirst(".status, .labels a:contains(Ongoing), .labels a:contains(Completed)")?.text() ?: ""
        val status = if (statusText.contains("Ongoing", true) || statusText.contains("Updating", true)) {
            ShowStatus.Ongoing
        } else if (statusText.contains("Completed", true) || statusText.contains("End", true)) {
            ShowStatus.Completed
        } else null

        val type = if (title.contains("Movie", true)) TvType.Movie else TvType.Anime

        if (type == TvType.Movie) {
            return newMovieLoadResponse(title, url, type, url) {
                this.posterUrl = poster
                this.plot = plot
                this.tags = genres
            }
        }

        val episodes = mutableListOf<Episode>()
        
        // Find episodic links
        doc.select(".post-body a[href*='episode-'], .entry-content a[href*='episode-']").forEach { epLink ->
            val epUrl = epLink.attr("href")
            val epText = epLink.text().trim()
            if (epUrl.isNotBlank() && !episodes.any { it.data == epUrl }) {
                val epNumMatches = Regex("""episode-(\d+)""").find(epUrl) ?: Regex("""[eE]pisode\s*(\d+)""").find(epText)
                val epNum = epNumMatches?.groupValues?.get(1)?.toIntOrNull()
                
                episodes.add(newEpisode(epUrl) {
                    this.name = if (epNum != null) "Episode $epNum" else epText
                    this.episode = epNum
                })
            }
        }

        // Si no hay links extraíbles, intentar cargar la pagina en sí como el episodio (común en Blogger si es post individual por cap)
        if (episodes.isEmpty()) {
            episodes.add(newEpisode(url) {
                this.name = "Ver Ahora"
                this.episode = 1
            })
        }

        return newAnimeLoadResponse(title, url, type) {
            this.posterUrl = poster
            this.plot = plot
            this.tags = genres
            this.showStatus = status
            addEpisodes(DubStatus.Subbed, episodes.reversed())
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val doc = getDocument(data)
        var found = false

        // Extract iframes commonly used for video streams
        doc.select("iframe, div.player-option iframe").forEach { iframe ->
            val src = iframe.attr("src").ifBlank { iframe.attr("data-src") }
            if (src.isNotBlank() && !src.contains("youtube.com")) {
                val safeUrl = if (src.startsWith("//")) "https:$src" else src
                if (safeUrl.startsWith("http")) {
                    loadExtractor(safeUrl, data, subtitleCallback, callback)
                    found = true
                }
            }
        }
        
        // Look for typical GoogleDrive or common extractors links embedded via a href or data
        doc.select("a.download-btn, a.play-btn, [data-video], ul#playeroptions li").forEach { link ->
            val src = link.attr("data-video").ifBlank { link.attr("href") }.ifBlank { link.attr("data-src") }
            if (src.isNotBlank() && src.startsWith("http") && !src.contains("youtube.com")) {
                loadExtractor(src, data, subtitleCallback, callback)
                found = true
            }
        }

        // Subtitles from track elements
        doc.select("video track, audio track").forEach { track ->
            val src = track.attr("src")
            val lang = track.attr("srclang").ifBlank { track.attr("label") }.ifBlank { "English" } // o es, id, pt
            if (src.isNotBlank()) {
                subtitleCallback(SubtitleFile(lang, src))
            }
        }

        // JSON config parse for player setup if embedded in scripts
        doc.select("script").forEach { script ->
            val content = script.html()
            if (content.contains("subtitle:") || content.contains("captions:")) {
                try {
                    val subRegex = Regex("""\{[^}]*(?:url|file|src)\s*:\s*['"]([^'"]+)['"][^}]*(?:lang|label)\s*:\s*['"]([^'"]+)['"]""")
                    subRegex.findAll(content).forEach { match ->
                        val url = match.groupValues[1]
                        val lang = match.groupValues[2]
                        subtitleCallback(SubtitleFile(lang, url))
                    }
                } catch (e: Exception) {
                    // Ignore
                }
            }
        }

        return found
    }

    private fun String.encodeUri() = java.net.URLEncoder.encode(this, "UTF-8")
}
