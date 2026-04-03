package com.example

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Element

class DonghuaLifeProvider : MainAPI() {
    override var mainUrl = "https://donghualife.com"
    override var name = "DonghuaLife"
    override val hasMainPage = true
    override var lang = "es"
    override val supportedTypes = setOf(TvType.Anime, TvType.Movie, TvType.AnimeMovie)

    override val mainPage = mainPageOf(
        "$mainUrl/" to "Latest",
        "$mainUrl/en-emision" to "Ongoing",
        "$mainUrl/donghuas" to "Donghua",
        "$mainUrl/movies" to "Movies",
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val doc = app.get(request.data).document
        val items = doc.select(".views-row").mapNotNull { it.toSearchResult() }
        return newHomePageResponse(request.name, items)
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val anchor = selectFirst(".views-field-title a, a") ?: return null
        val title = anchor.text().trim()
        val url = fixUrl(anchor.attr("href"))
        val img = selectFirst("img")?.attr("src")?.let { fixUrl(it) }

        return if (url.contains("/movie/")) {
            newMovieSearchResponse(title, url, TvType.Movie) {
                this.posterUrl = img
            }
        } else {
            newAnimeSearchResponse(title, url, TvType.Anime) {
                this.posterUrl = img
            }
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val doc = app.get("$mainUrl/search?search_api_fulltext=$query").document
        return doc.select(".views-row").mapNotNull { it.toSearchResult() }
    }

    override suspend fun load(url: String): LoadResponse? {
        val doc = app.get(url).document
        val title = doc.selectFirst("h1")?.text()?.trim() ?: return null
        val poster = doc.selectFirst(".field--name-field-image img, img")?.attr("src")?.let { fixUrl(it) }
        val plot = doc.selectFirst(".field--name-field-synopsis, .field--type-text-with-summary")?.text()?.trim()

        val episodes = mutableListOf<Episode>()

        // Get seasons and episodes
        doc.select("a[href^=\"/episode/\"]").forEach { epNode ->
            val epHref = fixUrl(epNode.attr("href"))
            val epName = epNode.text().trim()
            val epNum = Regex("""x(\d+)""").find(epHref)?.groupValues?.getOrNull(1)?.toIntOrNull()
            val seasonNum = Regex("""(?:-s|season-?)(\d+)""").find(epHref)?.groupValues?.getOrNull(1)?.toIntOrNull() 
                ?: Regex("""-(\d+)-episodio""").find(epHref)?.groupValues?.getOrNull(1)?.toIntOrNull() 
                ?: 1

            episodes.add(newEpisode(epHref) {
                this.name = epName
                this.season = seasonNum
                this.episode = epNum
            })
        }

        return if (url.contains("/movie/")) {
            newMovieLoadResponse(title, url, TvType.Movie, url) {
                this.posterUrl = poster
                this.plot = plot
            }
        } else {
            newAnimeLoadResponse(title, url, TvType.Anime) {
                this.posterUrl = poster
                this.plot = plot
                addEpisodes(DubStatus.Subbed, episodes)
            }
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val doc = app.get(data).document
        var found = false
        doc.select("iframe").forEach { iframe ->
            val src = iframe.attr("src")
            if (src.isNotBlank() && src.startsWith("http")) {
                loadExtractor(src, data, subtitleCallback, callback)
                found = true
            }
        }
        return found
    }
}
