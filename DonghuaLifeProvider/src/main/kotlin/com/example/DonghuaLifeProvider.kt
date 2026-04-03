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

    // ─── Main page categories ──────────────────────────────────────────────────

    override val mainPage = mainPageOf(
        "$mainUrl/"          to "Latest",
        "$mainUrl/en-emision" to "Ongoing",
        "$mainUrl/donghuas"  to "Donghua",
        "$mainUrl/movies"    to "Movies",
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val doc = app.get(request.data).document
        val items = doc.select(".views-row").mapNotNull { it.toSearchResult() }
        return newHomePageResponse(request.name, items)
    }

    // ─── Card parser ──────────────────────────────────────────────────────────
    // Structure used by search results and main page listings:
    //
    //  <div class="views-row …">
    //    <div class="serie">
    //      <div class="imagen">
    //        <a href="/series/…"><img src="…" /></a>
    //      </div>
    //      <div class="block container">
    //        <div class="titulo">Title Text</div>
    //      </div>
    //    </div>
    //  </div>

    private fun Element.toSearchResult(): SearchResponse? {
        // The href may be on a /series/ or /movie/ anchor
        val anchor = selectFirst(".imagen a, a[href^='/series/'], a[href^='/movie/']")
            ?: selectFirst("a") ?: return null

        val href = anchor.attr("href").trim()
        if (href.isBlank()) return null
        val url = fixUrl(href)

        // Title: prefer the .titulo div; fall back to the img alt attribute
        val title = selectFirst(".titulo")?.text()?.trim()
            ?: selectFirst("img")?.attr("alt")?.trim()
            ?: return null

        val poster = selectFirst("img")?.attr("src")?.let { fixUrl(it) }

        return if (url.contains("/movie/")) {
            newMovieSearchResponse(title, url, TvType.Movie) { posterUrl = poster }
        } else {
            newAnimeSearchResponse(title, url, TvType.Anime) { posterUrl = poster }
        }
    }

    // ─── Search ───────────────────────────────────────────────────────────────
    // Endpoint: /search?search_api_fulltext=<query>
    // Results wrapped with the same .views-row structure as the main page.

    override suspend fun search(query: String): List<SearchResponse> {
        val encoded = query.replace(" ", "+")
        val doc = app.get("$mainUrl/search?search_api_fulltext=$encoded").document
        return doc.select(".views-row").mapNotNull { it.toSearchResult() }
    }

    // ─── Series / Movie detail ─────────────────────────────────────────────────
    //
    // The series page URL is /series/<slug>.
    // Title  → article.node--type-series .field--name-title  (or h2 inside article)
    // Poster → article.node--type-series .poster img  (first poster-style image)
    // Plot   → article.node--type-series .field--name-body, .field--name-field-synopsis
    // Seasons → all a[href^="/season/"] inside the article
    //
    // Episodes are NOT listed directly on the series page; they must be fetched
    // from each individual season page (/season/<slug>-<n>) where they appear in
    // .episodios a[href^="/episode/"].

    override suspend fun load(url: String): LoadResponse? {
        val doc = app.get(url).document

        // Series detail is inside article.node--type-series
        val article = doc.selectFirst("article.node--type-series")

        val title = (article?.selectFirst(".field--name-title, h2:not(:has(a.visually-hidden))")
            ?.text()?.trim())
            ?: doc.selectFirst("h1")?.text()?.trim()
            ?: return null

        val poster = article?.selectFirst(".poster img, .field--name-field-image img")
            ?.attr("src")?.let { fixUrl(it) }

        val plot = article?.selectFirst(
            ".field--name-body, .field--name-field-synopsis, .field--type-text-with-summary"
        )?.text()?.trim()

        // ── Movie ─────────────────────────────────────────────────────────────
        if (url.contains("/movie/")) {
            return newMovieLoadResponse(title, url, TvType.Movie, url) {
                posterUrl = poster
                this.plot = plot
            }
        }

        // ── Series / Anime – collect episodes via season sub-pages ─────────────
        val seasonLinks = article?.select("a[href^='/season/']")
            ?.mapNotNull { it.attr("href").takeIf { h -> h.isNotBlank() } }
            ?.distinct()
            ?: emptyList()

        // Fall back: single implicit season on current page
        val episodeSources = seasonLinks.ifEmpty { listOf(url) }

        val episodes = mutableListOf<Episode>()

        for ((seasonIdx, seasonHref) in episodeSources.withIndex()) {
            val seasonUrl = fixUrl(seasonHref)
            val seasonNum = Regex("""-(\d+)$""").find(seasonHref)
                ?.groupValues?.getOrNull(1)?.toIntOrNull()
                ?: (seasonIdx + 1)

            val seasonDoc = if (seasonUrl == url) doc else app.get(seasonUrl).document

            // Episodes live in .episodios
            seasonDoc.select(".episodios a[href^='/episode/']").forEach { epAnchor ->
                val epHref = fixUrl(epAnchor.attr("href"))
                val epText = epAnchor.text().trim()
                val epNum = Regex("""x(\d+)""").find(epHref)
                    ?.groupValues?.getOrNull(1)?.toIntOrNull()

                episodes.add(newEpisode(epHref) {
                    name = epText.ifBlank { "Episodio $epNum" }
                    season = seasonNum
                    episode = epNum
                })
            }
        }

        return newAnimeLoadResponse(title, url, TvType.Anime) {
            posterUrl = poster
            this.plot = plot
            addEpisodes(DubStatus.Subbed, episodes.sortedWith(
                compareBy({ it.season }, { it.episode })
            ))
        }
    }

    // ─── Video links ──────────────────────────────────────────────────────────
    // Episode pages embed the player in a standard <iframe src="…"> tag.
    // Supported via Cloudstream's built-in loadExtractor:
    //   Rumble, Dailymotion, Ok.ru, Streamtape, Filemoon, Doodstream, etc.

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val doc = app.get(data, referer = mainUrl).document
        var found = false

        doc.select("iframe").forEach { iframe ->
            val src = iframe.attr("src").ifBlank { iframe.attr("data-src") }.trim()
            if (src.startsWith("http")) {
                loadExtractor(src, data, subtitleCallback, callback)
                found = true
            }
        }

        return found
    }
}
