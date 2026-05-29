package com.example

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addEpisodes
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

    override val mainPage = mainPageOf(
        "$mainUrl/" to "Últimos Agregados",
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val doc = app.get(request.data, interceptor = appUtils.cloudflareKiller.getInterceptor()).document
        val items = doc.select(".lista-episodios .item, .recent-episodes .item, .grid-items .item").mapNotNull {
            it.toSearchResult()
        }
        return newHomePageResponse(request.name, items, hasNext = false)
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val anchor = selectFirst("a") ?: return null
        val url = anchor.absUrl("href")
        val name = selectFirst(".title, h3, h2")?.text()?.trim() ?: return null
        val poster = selectFirst("img")?.let {
            it.attr("data-src").ifBlank { it.attr("src") }
        } ?: ""

        val type = if (url.contains("/pelicula/")) TvType.Movie else TvType.Anime

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
        val doc = app.get(url, interceptor = appUtils.cloudflareKiller.getInterceptor()).document
        return doc.select(".grid-items .item, .search-results .item").mapNotNull {
            it.toSearchResult()
        }
    }

    override suspend fun load(url: String): LoadResponse? {
        val doc = app.get(url, interceptor = appUtils.cloudflareKiller.getInterceptor()).document
        
        val title = doc.selectFirst("h1")?.text()?.trim() ?: return null
        val poster = doc.selectFirst("meta[property=\"og:image\"]")?.attr("content")
            ?: doc.selectFirst(".anime-details img")?.attr("src")
        
        val plot = doc.selectFirst(".sinopsis, meta[name=\"description\"]")?.let {
            if (it.tagName() == "meta") it.attr("content") else it.text().trim()
        }
        
        val genres = doc.select(".generos a, .genres a").map { it.text().trim() }
        val year = doc.selectFirst(".info-item:contains(Año), .year")?.text()?.filter { it.isDigit() }?.toIntOrNull()
        
        val statusText = doc.selectFirst(".status, .info-item:contains(Estado)")?.text()?.lowercase() ?: ""
        val status = if (statusText.contains("en emisión") || statusText.contains("ongoing")) {
            TvStatus.Ongoing
        } else {
            TvStatus.Completed
        }

        val type = if (url.contains("/pelicula/")) TvType.Movie else TvType.Anime

        if (type == TvType.Movie) {
            return newMovieLoadResponse(title, url, type, url) {
                this.posterUrl = poster
                this.plot = plot
                this.tags = genres
                this.year = year
            }
        }

        val episodes = doc.select(".lista-episodios a, .episodes-list a").mapNotNull {
            val epUrl = it.absUrl("href")
            val epText = it.text().trim()
            val epNum = Regex("""\s+(\d+)$""").find(epText)?.groupValues?.get(1)?.toIntOrNull()
            val season = Regex("""-(\d+)/ver/""").find(epUrl)?.groupValues?.get(1)?.toIntOrNull() ?: 1

            newEpisode(epUrl) {
                this.name = epText
                this.season = season
                this.episode = epNum
            }
        }.reversed()

        return newAnimeLoadResponse(title, url, type) {
            this.posterUrl = poster
            this.plot = plot
            this.tags = genres
            this.year = year
            this.showStatus = status
            addEpisodes(DubStatus.Subbed, episodes)
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val doc = app.get(data, interceptor = appUtils.cloudflareKiller.getInterceptor()).document
        var found = false

        for (li in doc.select(".servidores li, .options li, .players li")) {
            val dataUrl = li.attr("data-url")
            if (dataUrl.isNotBlank() && dataUrl.startsWith("http")) {
                loadExtractor(dataUrl, data, subtitleCallback, callback)
                found = true
            }
        }

        for (iframe in doc.select("iframe")) {
            val src = iframe.attr("src")
            if (src != null && src.startsWith("http")) {
                loadExtractor(src, data, subtitleCallback, callback)
                found = true
            }
        }

        return found
    }

    private fun String.encodeUri(): String =
        java.net.URLEncoder.encode(this, "UTF-8")
}
