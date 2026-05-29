package com.example

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Element

class MundoDonghuaProvider : MainAPI() {

    override var mainUrl = "https://www.mundodonghua.com"
    override var name = "MundoDonghua"
    override var lang = "es"
    override val hasMainPage = true
    override val hasQuickSearch = true
    override val supportedTypes = setOf(
        TvType.Anime,
        TvType.TvSeries,
        TvType.Movie
    )

    companion object {
        private const val USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36"
    }

    override val mainPage = mainPageOf(
        "$mainUrl/" to "Últimos Episodios",
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val doc = app.get(
            request.data,
            headers = mapOf("User-Agent" to USER_AGENT)
        ).document
        
        val homeItems = mutableListOf<SearchResponse>()
        
        // Selectores específicos basados en la estructura real del sitio
        doc.select(".row > .col-6, .row > .col-md-3, .row > .col-lg-3, .col-6, .col-md-3, .col-lg-3").forEach { element ->
            val result = element.toSearchResult()
            if (result != null && homeItems.none { it.url == result.url }) {
                homeItems.add(result)
            }
        }
        
        // Fallback: buscar cualquier elemento que tenga un enlace a /donghua/ o /pelicula/
        if (homeItems.isEmpty()) {
            doc.select("a[href*='/donghua/'], a[href*='/pelicula/']").forEach { element ->
                val result = element.toSearchResultFromAnchor()
                if (result != null && homeItems.none { it.url == result.url }) {
                    homeItems.add(result)
                }
            }
        }

        return newHomePageResponse(request.name, homeItems, hasNext = false)
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val anchor = selectFirst("a") ?: return null
        return anchor.toSearchResultFromAnchor()
    }
    
    private fun Element.toSearchResultFromAnchor(): SearchResponse? {
        val href = attr("href")?.trim() ?: return null
        if (href.isBlank()) return null
        
        val url = when {
            href.startsWith("http") -> href
            href.startsWith("/") -> "$mainUrl$href"
            else -> "$mainUrl/$href"
        }
        
        if (!url.contains("/donghua/") && !url.contains("/pelicula/")) return null

        val name = selectFirst("img")?.attr("alt")?.trim()
            ?: text()?.trim()?.lines()?.firstOrNull()?.trim()
            ?: return null
        
        val cleanName = name
            .replace(Regex("""Episodio\s*\d+""", RegexOption.IGNORE_CASE), "")
            .replace(Regex("""\d{2}-\d{2}-\d{4}"""), "")
            .replace(Regex("""\d{1,3}(,\d{3})*"""), "")
            .trim()

        val poster = selectFirst("img")?.let { img ->
            img.attr("data-src").ifBlank { img.attr("src") }
        }

        val type = when {
            url.contains("/pelicula/") -> TvType.Movie
            url.contains("/donghua/") -> TvType.Anime
            else -> TvType.Anime
        }

        return if (type == TvType.Movie) {
            newMovieSearchResponse(cleanName, url, type) {
                this.posterUrl = poster
            }
        } else {
            newAnimeSearchResponse(cleanName, url, type) {
                this.posterUrl = poster
            }
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val url = "$mainUrl/busquedas/${query.encodeUri()}"
        val doc = app.get(
            url,
            headers = mapOf("User-Agent" to USER_AGENT)
        ).document
        
        val results = mutableListOf<SearchResponse>()
        
        doc.select(".row .col-6, .row .col-md-4, .item, .card, .search-result").forEach { element ->
            val result = element.toSearchResult()
            if (result != null && results.none { it.url == result.url }) {
                results.add(result)
            }
        }
        
        if (results.isEmpty()) {
            doc.select("a[href*='/donghua/'], a[href*='/pelicula/']").forEach { element ->
                val result = element.toSearchResultFromAnchor()
                if (result != null && results.none { it.url == result.url }) {
                    results.add(result)
                }
            }
        }
        
        return results
    }

    override suspend fun load(url: String): LoadResponse? {
        val doc = app.get(
            url,
            headers = mapOf("User-Agent" to USER_AGENT)
        ).document
        
        val title = doc.selectFirst("h1, .title, .entry-title")?.text()?.trim()?.split("–")?.firstOrNull()?.trim()
            ?: return null
        
        val poster = doc.selectFirst("meta[property='og:image']")?.attr("content")
            ?: doc.selectFirst(".anime-cover img, .poster img, .thumb img")?.attr("src")
        
        val plot = doc.selectFirst(".sinopsis, .description, .plot, meta[name='description'], .summary")?.let {
            if (it.tagName() == "meta") it.attr("content") else it.text().trim()
        }?.takeIf { it.isNotBlank() }
        
        val genres = doc.select(".generos a, .genres a, .tags a, .genre, .sgeneros a").map { 
            it.text().trim() 
        }.filter { it.isNotBlank() }
        
        val year = doc.selectFirst(".year, .info-item:contains(Año), [class*='year'], .date")?.text()
            ?.filter { it.isDigit() }?.take(4)?.toIntOrNull()

        val statusText = doc.selectFirst(".status, .info-item:contains(Estado), [class*='status'], .airing")?.text()?.lowercase() ?: ""
        val showStatus = when {
            statusText.contains("emisión") || statusText.contains("ongoing") || statusText.contains("airing") -> ShowStatus.Ongoing
            statusText.contains("finalizado") || statusText.contains("completed") || statusText.contains("finished") -> ShowStatus.Completed
            else -> ShowStatus.Completed
        }

        val type = when {
            url.contains("/pelicula/") -> TvType.Movie
            url.contains("/donghua/") -> TvType.Anime
            url.contains("/ver/") -> TvType.Anime
            else -> TvType.Anime
        }

        if (type == TvType.Movie) {
            return newMovieLoadResponse(title, url, type, url) {
                this.posterUrl = poster
                this.plot = plot
                this.tags = genres
                this.year = year
            }
        }

        val episodes = mutableListOf<Episode>()
        
        val episodeSelectors = listOf(
            ".lista-episodios a",
            ".episodes-list a", 
            ".episode a",
            ".episodios a",
            ".ep-list a",
            ".episodio a",
            "ul.episodes li a",
            ".tab-content a[href*='/ver/']"
        )
        
        for (selector in episodeSelectors) {
            doc.select(selector).forEach { epLink ->
                val epUrl = epLink.absUrl("href").ifBlank { epLink.attr("href") }
                if (epUrl.isBlank() || !epUrl.contains(mainUrl)) return@forEach
                
                if (episodes.any { it.data == epUrl }) return@forEach
                
                val epText = epLink.text().trim()
                
                val epNum = Regex("""Episodio\s*(\d+)""", RegexOption.IGNORE_CASE).find(epText)?.groupValues?.get(1)?.toIntOrNull()
                    ?: Regex("""(\d+)\s*$""").find(epText)?.groupValues?.get(1)?.toIntOrNull()
                    ?: Regex("""episodio[:\s]*(\d+)""", RegexOption.IGNORE_CASE).find(epText)?.groupValues?.get(1)?.toIntOrNull()
                    ?: Regex("""\d+\s*x\s*(\d+)""").find(epText)?.groupValues?.get(1)?.toIntOrNull()
                
                val season = Regex("""-(\d+)/ver/|temporada[:\s]*(\d+)""", RegexOption.IGNORE_CASE).find(epUrl)?.let {
                    it.groupValues.get(1).toIntOrNull() ?: it.groupValues.get(2).toIntOrNull()
                } ?: 1

                val name = if (epNum != null) "Episodio $epNum" else epText.ifBlank { "Episodio" }

                episodes.add(newEpisode(epUrl) {
                    this.name = name
                    this.season = season
                    this.episode = epNum ?: 1
                })
            }
            
            if (episodes.isNotEmpty()) break
        }

        return newAnimeLoadResponse(title, url, type) {
            this.posterUrl = poster
            this.plot = plot
            this.tags = genres
            this.year = year
            this.showStatus = showStatus
            addEpisodes(DubStatus.Subbed, episodes.reversed())
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val doc = app.get(
            data,
            referer = mainUrl,
            headers = mapOf("User-Agent" to USER_AGENT)
        ).document
        var found = false

        val serverSelectors = listOf(
            ".servidores li",
            ".options li", 
            ".players li",
            ".server-list li",
            ".episode-servers li",
            ".server-item",
            ".video-option",
            "[data-server]"
        )
        
        for (selector in serverSelectors) {
            doc.select(selector).forEach { server ->
                val dataUrl = server.attr("data-url").ifBlank { 
                    server.attr("data-src").ifBlank {
                        server.attr("data-link").ifBlank {
                            server.selectFirst("a")?.attr("href")
                        }
                    }
                }
                
                if (!dataUrl.isNullOrBlank() && (dataUrl.startsWith("http") || dataUrl.startsWith("//"))) {
                    val fullUrl = if (dataUrl.startsWith("//")) "https:$dataUrl" else dataUrl
                    loadExtractor(fullUrl, data, subtitleCallback, callback)
                    found = true
                }
            }
        }

        doc.select("iframe").forEach { iframe ->
            val src = iframe.attr("src").ifBlank { iframe.attr("data-src") }
            if (src.isNotBlank()) {
                val fullUrl = if (src.startsWith("//")) "https:$src" else src
                loadExtractor(fullUrl, data, subtitleCallback, callback)
                found = true
            }
        }

        doc.select("video source").forEach { source ->
            val src = source.attr("src")
            if (src.isNotBlank()) {
                loadExtractor(src, data, subtitleCallback, callback)
                found = true
            }
        }

        return found
    }

    private fun String.encodeUri(): String =
        java.net.URLEncoder.encode(this, "UTF-8")
}