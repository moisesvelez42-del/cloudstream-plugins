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
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) " +
            "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0.0.0 Safari/537.36"
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
        
        doc.select(".container .row, .episodios-grid, .lista-episodios, main").forEach { section ->
            section.select(".item, .episode-card, .anime-card, article").forEach { item ->
                val result = item.toSearchResult()
                if (result != null) {
                    homeItems.add(result)
                }
            }
        }
        
        if (homeItems.isEmpty()) {
            doc.select(".col-6, .col-md-4, .col-lg-3").forEach { item ->
                val result = item.toSearchResult()
                if (result != null) {
                    homeItems.add(result)
                }
            }
        }

        return newHomePageResponse(request.name, homeItems.distinctBy { it.url }, hasNext = false)
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val anchor = selectFirst("a") ?: return null
        val url = anchor.absUrl("href").ifBlank { anchor.attr("href") }
        if (url.isBlank() || !url.contains(mainUrl)) return null

        val name = selectFirst("h3, h2, .title, .name, .episode-title")?.text()?.trim()
            ?: anchor.selectFirst("img")?.attr("alt")?.trim()
            ?: return null

        val poster = selectFirst("img")?.let {
            it.attr("data-src").ifBlank { it.attr("src") }
        }?.takeIf { it.isNotBlank() } ?: selectFirst("picture source")?.attr("srcset")

        val type = when {
            url.contains("/pelicula/") -> TvType.Movie
            url.contains("/donghua/") -> TvType.Anime
            else -> TvType.Anime
        }

        return if (type == TvType.Movie) {
            newMovieSearchResponse(name, url, type) {
                this.posterUrl = poster
            }
        } else {
            newAnimeSearchResponse(name, url, type) {
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
        
        return doc.select(".grid-items .item, .search-results .item, .anime-list .item, .row .col-6").mapNotNull {
            it.toSearchResult()
        }.distinctBy { it.url }
    }

    override suspend fun load(url: String): LoadResponse? {
        val doc = app.get(
            url,
            headers = mapOf("User-Agent" to USER_AGENT)
        ).document
        
        val title = doc.selectFirst("h1, .title")?.text()?.trim()?.split("–")?.firstOrNull()?.trim()
            ?: return null
        
        val poster = doc.selectFirst("meta[property=\"og:image\"]")?.attr("content")
            ?: doc.selectFirst(".anime-cover img, .poster img")?.attr("src")
        
        val plot = doc.selectFirst(".sinopsis, .description, .plot, meta[name=\"description\"]")?.let {
            if (it.tagName() == "meta") it.attr("content") else it.text().trim()
        }?.takeIf { it.isNotBlank() }
        
        val genres = doc.select(".generos a, .genres a, .tags a, .genre").map { 
            it.text().trim() 
        }.filter { it.isNotBlank() }
        
        val year = doc.selectFirst(".year, .info-item:contains(Año), [class*=\"year\"]")?.text()
            ?.filter { it.isDigit() }?.take(4)?.toIntOrNull()

        val statusText = doc.selectFirst(".status, .info-item:contains(Estado), [class*=\"status\"]")?.text()?.lowercase() ?: ""
        val showStatus = when {
            statusText.contains("emisión") || statusText.contains("ongoing") || statusText.contains("airing") -> ShowStatus.Ongoing
            statusText.contains("finalizado") || statusText.contains("completed") || statusText.contains("finished") -> ShowStatus.Completed
            else -> ShowStatus.Completed
        }

        val type = when {
            url.contains("/pelicula/") -> TvType.Movie
            url.contains("/donghua/") -> TvType.Anime
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
        
        doc.select(".lista-episodios a, .episodes-list a, .episode a, ul li a").forEach { epLink ->
            val epUrl = epLink.absUrl("href").ifBlank { epLink.attr("href") }
            if (epUrl.isBlank() || !epUrl.contains(mainUrl)) return@forEach
            
            val epText = epLink.text().trim()
            
            val epNum = Regex("""(\d+)\s*$""").find(epText)?.groupValues?.get(1)?.toIntOrNull()
                ?: Regex("""episodio[:\s]*(\d+)""").find(epText)?.groupValues?.get(1)?.toIntOrNull()
            
            val season = Regex("""-(\d+)/ver/|temporada[:\s]*(\d+)""").find(epUrl)?.let {
                it.groupValues.get(1).toIntOrNull() ?: it.groupValues.get(2).toIntOrNull()
            } ?: 1

            val name = if (epNum != null) "Episodio $epNum" else epText.ifBlank { "Episodio" }

            episodes.add(newEpisode(epUrl) {
                this.name = name
                this.season = season
                this.episode = epNum ?: 1
            })
        }

        if (episodes.isEmpty()) {
            doc.select(".col-6, .episode-card").forEachIndexed { index, epCard ->
                val epLink = epCard.selectFirst("a")
                val epUrl = epLink?.absUrl("href")?.ifBlank { epLink?.attr("href") } ?: return@forEachIndexed
                if (epUrl.isBlank() || !epUrl.contains(mainUrl)) return@forEachIndexed
                
                val epText = epCard.text().trim()
                val epNum = Regex("""(\d+)""").find(epText)?.groupValues?.get(1)?.toIntOrNull() ?: (index + 1)
                
                episodes.add(newEpisode(epUrl) {
                    this.name = "Episodio $epNum"
                    this.episode = epNum
                })
            }
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

        doc.select(".servidores li, .options li, .players li, .server-list li, .episode-servers li").forEach { server ->
            val dataUrl = server.attr("data-url").ifBlank { 
                server.selectFirst("a")?.attr("href") 
            }
            if (!dataUrl.isNullOrBlank() && (dataUrl.startsWith("http") || dataUrl.startsWith("//"))) {
                val fullUrl = if (dataUrl.startsWith("//")) "https:$dataUrl" else dataUrl
                loadExtractor(fullUrl, data, subtitleCallback, callback)
                found = true
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