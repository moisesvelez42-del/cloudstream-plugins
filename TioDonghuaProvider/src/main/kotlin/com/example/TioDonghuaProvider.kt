package com.example

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Element

class TioDonghuaProvider : MainAPI() {

    // El dominio antiguo (tiodonghua.com) responde 301 → tiodonghua.lat, que es
    // el host canónico que usa la propia web en todos sus enlaces internos.
    override var mainUrl  = "https://tiodonghua.lat"
    override var name     = "TioDonghua"
    override var lang     = "es"
    override val hasMainPage = true
    override val supportedTypes = setOf(TvType.Anime)

    // ─── Main page sections ───────────────────────────────────────────────────

    override val mainPage = mainPageOf(
        "$mainUrl/"                                           to "Latest",
        "$mainUrl/tendencias/page/"                           to "Popular",
        "$mainUrl/genero/anime/page/"                         to "Anime",
        "$mainUrl/genero/donghua/page/"                       to "Donghua",
        "$mainUrl/genero/mundo-donghua/page/"                 to "En Emisión",
        "$mainUrl/peliculas/page/"                            to "Movies",
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
            ?: selectFirst("img")?.attr("alt")?.trim()
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

        // Título: la ficha de serie (DooPlay) usa .sheader .data h1.
        // Las páginas de episodio (a donde apuntan las tarjetas del home) no
        // tienen .sheader, pero sí <h1 class="epih1">.
        val title   = doc.selectFirst(".sheader .data h1")?.text()?.trim()
            ?: doc.selectFirst("h1.epih1")?.text()?.trim()
            ?: return null

        // Póster: en la ficha es .poster img (con src, no data-src).
        // En el episodio no hay .poster, por lo que se usa og:image como respaldo.
        val poster  = doc.selectFirst(".poster img")?.let {
            it.attr("data-src").ifBlank { it.attr("src") }
        }?.takeIf { it.isNotBlank() }
            ?: doc.selectFirst("meta[property=og:image]")?.attr("content")?.trim()
                ?.takeIf { it.isNotBlank() }

        // Sinopsis: en DooPlay el texto vive DIRECTAMENTE dentro de
        // #info .wp-content (no en <p>). Se toma su texto propio (ownText)
        // para excluir la galería interna (#dt_galery).
        val synopsis = doc.selectFirst("#info .wp-content")?.ownText()?.trim()
            ?.takeIf { it.isNotBlank() }

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
        // Los episodios vienen agrupados por temporada en bloques
        //   <div class="se-c">
        //     <div class="se-q"><span class="se-t se-o">1</span> Season 1 …</div>
        //     <div class="se-a"><ul class="episodios"><li> …
        // El contenedor NO es [id^=season-] (eso ya no existe en el tema actual).
        val episodesBySeasonRaw = mutableMapOf<Int, MutableList<Episode>>()

        val seasonBlocks = doc.select(".se-c")
        if (seasonBlocks.isNotEmpty()) {
            for (block in seasonBlocks) {
                // Número de temporada: del badge .se-t (p.ej. "1"); respaldo: primer
                // dígito del título del bloque; último recurso: 1.
                val seasonNum = block.selectFirst(".se-q .se-t")?.text()?.trim()?.toIntOrNull()
                    ?: block.selectFirst(".se-q .title")?.text()
                        ?.let { Regex("\\d+").find(it)?.value?.toIntOrNull() }
                    ?: 1

                block.select("ul.episodios li").forEach { li ->
                    val epAnchor = li.selectFirst(".episodiotitle a") ?: return@forEach
                    val epUrl    = epAnchor.attr("href").trim()
                    if (epUrl.isBlank()) return@forEach
                    val epName   = epAnchor.text().trim()
                    // .numerando es del tipo "1 - 2" (temporada - episodio)
                    val epNum    = li.selectFirst(".numerando")?.text()?.trim()
                        ?.split(Regex("\\D+"))?.lastOrNull()?.toIntOrNull()
                    episodesBySeasonRaw.getOrPut(seasonNum) { mutableListOf() }
                        .add(newEpisode(epUrl) {
                            this.name    = epName
                            this.season  = seasonNum
                            this.episode = epNum
                        })
                }
            }
        } else {
            // Respaldo: lista plana de episodios (sin agrupación por temporada)
            doc.select(".episodios li").forEach { li ->
                val epAnchor = li.selectFirst(".episodiotitle a") ?: return@forEach
                val epUrl    = epAnchor.attr("href").trim()
                if (epUrl.isBlank()) return@forEach
                val epName   = epAnchor.text().trim()
                val epNum    = li.selectFirst(".numerando")?.text()?.trim()
                    ?.split(Regex("\\D+"))?.lastOrNull()?.toIntOrNull()
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
        val doc = app.get(
            data,
            referer = mainUrl,
            headers = mapOf(
                "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) " +
                    "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
            )
        ).document

        var found = false

        // ── Strategy 1: DooPlay REST API (v2) ─────────────────────────────────
        //
        // tiodonghua.com uses the newer DooPlay REST endpoint — NOT admin-ajax.php.
        //
        //   Request:  GET /wp-json/dooplayer/v2/{post_id}/{type}/{nume}
        //   Response: {"embed_url":"https:\/\/www.dailymotion.com\/embed\/video\/xxx","type":"iframe"}
        //
        // Player option HTML:
        //   <li class="dooplay_player_option"
        //       data-post="17993" data-type="tv" data-nume="1">Español 1</li>
        //
        // The site supports up to 12 servers per episode (Español 1-4, English 1-4,
        // Portugues 1-4). No nonce is required for the REST endpoint.
        val playerOptions = doc.select("li.dooplay_player_option[data-post][data-type][data-nume]")

        // El sitio migró a tiodonghua.lat y tiodonghua.com responde 301 hacia él.
        // Para no depender de esa redirección, la API se pide al mismo host que
        // sirve la página de episodio (las URLs de episodio ya apuntan a .lat).
        val baseUrl = Regex("^https?://[^/]+").find(data)?.value ?: mainUrl

        for (option in playerOptions) {
            val postId = option.attr("data-post").trim()
            val type   = option.attr("data-type").trim().ifBlank { "tv" }
            val nume   = option.attr("data-nume").trim()

            if (postId.isBlank() || nume.isBlank()) continue

            val apiUrl = "$baseUrl/wp-json/dooplayer/v2/$postId/$type/$nume"

            val jsonText = try {
                app.get(
                    apiUrl,
                    referer = data,
                    headers = mapOf(
                        "User-Agent"       to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) " +
                            "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36",
                        "X-Requested-With" to "XMLHttpRequest",
                        "Accept"           to "application/json, */*"
                    )
                ).text
            } catch (e: Exception) {
                continue
            }

            // The API escapes forward slashes as \/ — unescape before use.
            val embedUrl = Regex(""""embed_url"\s*:\s*"([^"]+)"""")
                .find(jsonText)?.groupValues?.getOrNull(1)
                ?.replace("\\/", "/")
                ?.replace("\\u0026", "&")
                ?.trim()

            if (!embedUrl.isNullOrBlank() && embedUrl.startsWith("http")) {
                if (embedUrl.contains("minochinos.com") || embedUrl.contains("tiodonghua.com")) {
                    try {
                        val docWrapper = app.get(embedUrl, referer = mainUrl).document
                        docWrapper.select("iframe[src]").forEach { iframe ->
                            val iframeSrc = iframe.attr("src")
                            if (iframeSrc.startsWith("http")) {
                                loadExtractor(iframeSrc, data, subtitleCallback, callback)
                            }
                        }
                    } catch (e: Exception) {
                        // Ignore wrapper timeout/errors
                    }
                }
                loadExtractor(embedUrl, data, subtitleCallback, callback)
                found = true
            }
        }

        // ── Strategy 2: Direct iframes already present in the DOM ─────────────
        // Fallback for pages that inline the embed without player options.
        if (!found) {
            doc.select("iframe[src]").forEach { iframe ->
                val src = iframe.attr("src").trim()
                if (src.isNotBlank() && src.startsWith("http")) {
                    loadExtractor(src, data, subtitleCallback, callback)
                    found = true
                }
            }
        }

        return found
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun String.encodeUri(): String =
        java.net.URLEncoder.encode(this, "UTF-8")
}
