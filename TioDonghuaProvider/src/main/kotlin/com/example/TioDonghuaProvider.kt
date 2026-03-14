package com.example

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Element

class TioDonghuaProvider : MainAPI() {

    override var mainUrl  = "https://tiodonghua.com"
    override var name     = "TioDonghua"
    override var lang     = "es"
    override val hasMainPage = true
    override val supportedTypes = setOf(TvType.Anime)

    // ─── Main page sections ───────────────────────────────────────────────────

    override val mainPage = mainPageOf(
        "$mainUrl/"                                           to "Inicio",
        "$mainUrl/genero/donghua/page/"                      to "Donghua",
        "$mainUrl/genero/mundo-donghua/page/"                to "En emisión",
        "$mainUrl/peliculas/page/"                           to "Películas",
    )

    /**
     * Parse a single card element ( <article class="item …"> ) found on any
     * listing page and return the correct SearchResponse subtype.
     */
    private fun Element.toSearchResult(): SearchResponse? {
        val anchor  = selectFirst("a") ?: return null
        val url     = anchor.attr("href").trim()
        val title   = selectFirst(".data h3")?.text()?.trim()
            ?: selectFirst("h3")?.text()?.trim()
            ?: return null
        val poster  = selectFirst("img")?.let {
            it.attr("data-src").ifBlank { it.attr("src") }
        }

        // Películas sub-path → Movie, everything else → Anime
        return if (url.contains("/peliculas/")) {
            newMovieSearchResponse(title, url, TvType.Movie) {
                this.posterUrl = poster
            }
        } else {
            newAnimeSearchResponse(title, url, TvType.Anime) {
                this.posterUrl = poster
            }
        }
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        // For paginated sections append the page number; for "/" just use as-is
        val url = if (request.data.endsWith("/page/"))
            "${request.data}$page/"
        else
            request.data

        val doc   = app.get(url).document
        // The WordPress/DooPlay theme wraps cards inside <article class="item …">
        val items = doc.select("article.item").mapNotNull { it.toSearchResult() }
        return newHomePageResponse(request.name, items, hasNext = items.isNotEmpty())
    }

    // ─── Search ───────────────────────────────────────────────────────────────

    override suspend fun search(query: String): List<SearchResponse> {
        val doc = app.get("$mainUrl/?s=${query.encodeUri()}").document
        // Search results use .result-item on DooPlay themes
        return doc.select("article.item, .result-item article").mapNotNull { it.toSearchResult() }
    }

    // ─── Anime detail page ────────────────────────────────────────────────────

    override suspend fun load(url: String): LoadResponse? {
        val doc = app.get(url).document

        val title   = doc.selectFirst(".sheader .data h1")?.text()?.trim()
            ?: doc.selectFirst("h1.entry-title")?.text()?.trim()
            ?: return null

        val poster  = doc.selectFirst(".poster img")?.let {
            it.attr("data-src").ifBlank { it.attr("src") }
        }

        // Synopsis lives inside the #info tab / .wp-content
        val synopsis = doc.select("#info .wp-content p, .wp-content p")
            .joinToString("\n") { it.text() }
            .trim()
            .takeIf { it.isNotBlank() }

        val genres = doc.select(".sgeneros a").map { it.text().trim() }

        // ── Movie ──────────────────────────────────────────────────────────────
        if (url.contains("/peliculas/")) {
            return newMovieLoadResponse(title, url, TvType.Movie, url) {
                this.posterUrl   = poster
                this.plot        = synopsis
                this.tags        = genres
            }
        }

        // ── Series / Anime ─────────────────────────────────────────────────────
        // Episodes are listed inside  <ul class="episodios">  grouped by season
        // Each season block: <div id="season-X"> <ul class="episodios"> <li> …
        val episodesBySeasonRaw = mutableMapOf<Int, MutableList<Episode>>()

        // Try season-based grouping first
        val seasonDivs = doc.select("[id^=season-]")
        if (seasonDivs.isNotEmpty()) {
            for (seasonDiv in seasonDivs) {
                val seasonNum = seasonDiv.id().removePrefix("season-").toIntOrNull() ?: 1
                seasonDiv.select("li").forEach { li ->
                    val epAnchor = li.selectFirst(".episodiotitle a") ?: return@forEach
                    val epUrl    = epAnchor.attr("href").trim()
                    val epName   = epAnchor.text().trim()
                    val epNum    = li.selectFirst(".numerando")?.text()
                        ?.replace("-", "x")
                        ?.split("x")?.lastOrNull()?.trim()?.toIntOrNull()
                    episodesBySeasonRaw.getOrPut(seasonNum) { mutableListOf() }
                        .add(newEpisode(epUrl) {
                            this.name    = epName
                            this.season  = seasonNum
                            this.episode = epNum
                        })
                }
            }
        } else {
            // Fallback: flat episode list
            doc.select(".episodios li").forEach { li ->
                val epAnchor = li.selectFirst(".episodiotitle a") ?: return@forEach
                val epUrl    = epAnchor.attr("href").trim()
                val epName   = epAnchor.text().trim()
                val epNum    = li.selectFirst(".numerando")?.text()
                    ?.replace("-", "x")
                    ?.split("x")?.lastOrNull()?.trim()?.toIntOrNull()
                episodesBySeasonRaw.getOrPut(1) { mutableListOf() }
                    .add(newEpisode(epUrl) {
                        this.name    = epName
                        this.season  = 1
                        this.episode = epNum
                    })
            }
        }

        val episodes = episodesBySeasonRaw.values.flatten()

        return newAnimeLoadResponse(title, url, TvType.Anime) {
            this.posterUrl       = poster
            this.plot            = synopsis
            this.tags            = genres
            addEpisodes(DubStatus.Subbed, episodes)
        }
    }

    // ─── Episode / video links ────────────────────────────────────────────────

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
        val doc = app.get(data, referer = mainUrl).document

        var found = false

        // ── Strategy 1: AJAX sources via DooPlay .dooplay_player_option ──────────
        // Each option element looks like:
        //  <li id='player-option-X' class='dooplay_player_option' data-type='tv' data-post='12345' data-nume='1'>
        val playerOptions = doc.select(".dooplay_player_option[data-post]")
        
        if (playerOptions.isNotEmpty()) {
            // Extract nonce from dtGonza script: var dtGonza = {"nonce":"...",...}
            val dtGonzaScript = doc.select("script").find { 
                it.data().contains("dtGonza") && it.data().contains("nonce") 
            }?.data()
            
            val nonce = dtGonzaScript?.let { script ->
                Regex("""\"nonce\"\s*:\s*\"([^\"]+)\"""").find(script)?.groupValues?.getOrNull(1)
            } ?: doc.selectFirst("#nonce")?.attr("value")
                ?: doc.selectFirst("input[name=_wpnonce]")?.attr("value")
                ?: ""

            for (option in playerOptions) {
                val type = option.attr("data-type").trim().ifBlank { "tv" }
                val postId = option.attr("data-post").trim()
                val nume = option.attr("data-nume").trim()
                
                if (postId.isBlank() || nume.isBlank()) continue

                // POST to the standard DooPlay AJAX endpoint
                val ajaxResponse = app.post(
                    "$mainUrl/wp-admin/admin-ajax.php",
                    data = mapOf(
                        "action" to "doo_player_ajax",
                        "post" to postId,
                        "nume" to nume,
                        "type" to type,
                        "nonce" to nonce,
                    ),
                    referer = data,
                    headers = mapOf(
                        "X-Requested-With" to "XMLHttpRequest",
                        "Accept" to "application/json, text/javascript, */*; q=0.01"
                    )
                ).text

                // Parse embed_url from JSON response: {"embed_url":"...","type":"iframe"}
                val embedUrl = Regex(""""embed_url"\s*:\s*"([^"]+)"""""")
                    .find(ajaxResponse)?.groupValues?.getOrNull(1)?.let {
                        it.replace("\\u0026", "&")
                            .replace("\\/", "/")
                            .replace("\\\"", "\"")
                    }

                if (!embedUrl.isNullOrBlank()) {
                    loadExtractor(embedUrl, data, subtitleCallback, callback)
                    found = true
                }
            }
        }

        // ── Strategy 2: Direct iframes already present in the DOM ──────────────
        if (!found) {
            doc.select("iframe[src]").forEach { iframe ->
                val src = iframe.attr("src").trim()
                if (src.isNotBlank() && src.startsWith("http")) {
                    loadExtractor(src, data, subtitleCallback, callback)
                    found = true
                }
            }
        }

        // ── Strategy 3: /enlaces/ redirect links ───────────────────────────────
        if (!found) {
            doc.select("a[href*='/enlaces/']").forEach { a ->
                val redirectUrl = a.attr("href").trim()
                if (redirectUrl.isNotBlank()) {
                    // Follow the redirect to get the real embed URL
                    val response = app.get(redirectUrl, referer = data)
                    val finalUrl = response.headers["location"] 
                        ?: response.document.selectFirst("iframe[src]")?.attr("src")
                        ?: redirectUrl
                    if (finalUrl != redirectUrl) {
                        loadExtractor(finalUrl, data, subtitleCallback, callback)
                        found = true
                    }
                }
            }
        }

        return found
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun String.encodeUri(): String =
        java.net.URLEncoder.encode(this, "UTF-8")
}
