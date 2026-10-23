package com.example

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

class DonghuaLifeProvider : MainAPI() {
    override var mainUrl = "https://donghualife.com"
    override var name = "DonghuaLife"
    override val hasMainPage = true
    override var lang = "es"
    override val supportedTypes = setOf(TvType.Anime, TvType.Movie, TvType.AnimeMovie)

    // ─── Secciones de la portada ───────────────────────────────────────────────
    // Todas verificadas con HTTP 200. Las tarjetas de estas páginas usan la clase
    // real "poster-card" (Drupal/.views-row ya no existe en el sitio).

    override val mainPage = mainPageOf(
        "$mainUrl/"                  to "Inicio",
        "$mainUrl/ultimos-episodios" to "Últimos episodios",
        "$mainUrl/series"            to "Series",
        "$mainUrl/peliculas"         to "Películas",
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        // Los listados paginan con ?page=N (verificado). La portada "/" no pagina.
        val isHome = request.data == "$mainUrl/"
        val url = if (!isHome && page > 1) {
            if (request.data.contains("?")) "${request.data}&page=$page" else "${request.data}?page=$page"
        } else {
            request.data
        }
        val doc = app.get(url).document
        val items = doc.select(CARD_SELECTOR).mapNotNull { it.toSearchResult() }
        return newHomePageResponse(request.name, items, hasNext = items.isNotEmpty() && !isHome)
    }

    // ─── Parser de tarjetas ────────────────────────────────────────────────────
    // Existen tres variantes reales, todas con la clase "poster-card":
    //   1) Portada:  <a href="/watch/…"><article class="poster-card">…
    //                  <img src="/uploads/images/…"><p class="line-clamp-2">Título</p>
    //   2) Últimos:  <a href="/watch/…"><div class="poster-card">… <img …>
    //   3) Listados: <a class="poster-card" href="/series/…|/peliculas/…"><img …>

    private fun Element.toSearchResult(): SearchResponse? {
        // La tarjeta puede ser ya el <a>, o estar dentro de un <a href> contenedor.
        val anchor = if (tagName() == "a") {
            this
        } else {
            parents().firstOrNull { it.tagName() == "a" && it.hasAttr("href") } ?: return null
        }
        val href = anchor.attr("href").trim()
        if (href.isBlank()) return null
        val url = fixUrl(href)

        // Título: el texto de la tarjeta (line-clamp-2) o el alt de la imagen.
        val title = selectFirst("p.line-clamp-2")?.text()?.trim()?.ifBlank { null }
            ?: selectFirst("img")?.attr("alt")?.trim()?.ifBlank { null }
            ?: return null

        val poster = cardPoster(selectFirst("img"))

        return if (url.contains("/peliculas/")) {
            newMovieSearchResponse(title, url, TvType.Movie) { posterUrl = poster }
        } else {
            newAnimeSearchResponse(title, url, TvType.Anime) { posterUrl = poster }
        }
    }

    /** Resuelve el póster, incluyendo el formato de Next Image (/_next/image?url=…). */
    private fun cardPoster(img: Element?): String? {
        if (img == null) return null
        val raw = img.attr("src").ifBlank { img.attr("data-src") }.ifBlank {
            img.attr("srcset").substringBefore(" ").trim()
        }
        if (raw.isBlank()) return null
        val decoded = if (raw.contains("/_next/image")) {
            val encoded = raw.substringAfter("url=").substringBefore("&")
            try {
                java.net.URLDecoder.decode(encoded, "UTF-8")
            } catch (_: Exception) {
                raw
            }
        } else {
            raw
        }
        return fixUrl(decoded)
    }

    // ─── Buscador ──────────────────────────────────────────────────────────────
    // Endpoint real verificado: GET /series?q=<query>  (HTTP 200, devuelve
    // tarjetas "poster-card"). El antiguo /search?search_api_fulltext= da 404.

    override suspend fun search(query: String): List<SearchResponse> {
        val url = "$mainUrl/series?q=${query.replace(" ", "+")}"
        val doc = app.get(url).document
        return doc.select(CARD_SELECTOR).mapNotNull { it.toSearchResult() }
    }

    // ─── Detalle (serie / película) ─────────────────────────────────────────────
    // Película: /peliculas/<slug>. Serie: /series/<slug>.
    // Los enlaces de las tarjetas de portada apuntan a /watch/<temporada>-<n>;
    // en ese caso se resuelve la serie padre usando seriesSlug del payload RSC.

    override suspend fun load(url: String): LoadResponse? {
        val response = app.get(url)
        val doc = response.document
        val html = response.text

        // ── Película ───────────────────────────────────────────────────────────
        if (url.contains("/peliculas/")) {
            val title = doc.selectFirst("h1")?.text()?.trim() ?: return null
            val poster = metaContent(doc, "og:image")
            val plot = metaContent(doc, "og:description") ?: metaContent(doc, "description")
            return newMovieLoadResponse(title, url, TvType.Movie, url) {
                posterUrl = poster
                this.plot = plot
            }
        }

        // ── Página de episodio /watch/<slug> → resolver la serie padre ─────────
        if (url.contains("/watch/")) {
            val seriesSlug = rscValue(html, "seriesSlug")
            if (!seriesSlug.isNullOrBlank()) {
                val seriesUrl = "$mainUrl/series/$seriesSlug"
                val seriesResponse = app.get(seriesUrl)
                return parseSeries(seriesUrl, seriesResponse.text, seriesResponse.document)
            }
        }

        return parseSeries(url, html, doc)
    }

    private suspend fun parseSeries(url: String, html: String, doc: Document): LoadResponse? {
        val title = doc.selectFirst("h1")?.text()?.trim() ?: return null
        val poster = metaContent(doc, "og:image")
        val plot = metaContent(doc, "og:description") ?: metaContent(doc, "description")

        val seriesSlug = url.trimEnd('/').substringAfterLast('/')

        // Temporadas desde el payload RSC: {"slug":"…","label":"Temporada N"}
        val unescaped = html.replace("\\\"", "\"")
        val seasons = SEASON_REGEX.findAll(unescaped)
            .map { it.groupValues[1] to it.groupValues[2] }
            .distinct()
            .toList()

        val episodes = mutableListOf<Episode>()

        seasons.forEachIndexed { index, (seasonSlug, label) ->
            val seasonNumber = Regex("""(\d+)""").find(label)
                ?.groupValues?.getOrNull(1)?.toIntOrNull() ?: (index + 1)

            val apiUrl = "$mainUrl/api/series/$seriesSlug/seasons/$seasonSlug/episodes"
            val apiEpisodes = app.get(apiUrl).parsedSafe<EpisodesResponse>()?.episodes ?: return@forEachIndexed

            apiEpisodes.forEach { ep ->
                val number = ep.number ?: return@forEach
                episodes.add(newEpisode("$mainUrl/watch/$seasonSlug-$number") {
                    name = ep.title ?: "Episodio $number"
                    season = seasonNumber
                    episode = number
                    posterUrl = poster
                })
            }
        }

        // Respaldo: si el RSC no expone temporadas, usar la rejilla del DOM.
        if (episodes.isEmpty()) {
            doc.select("a[href^='/watch/']").forEach { anchor ->
                val href = anchor.attr("href").trim()
                if (href.isBlank()) return@forEach
                val number = Regex("""-(\d+)$""").find(href)
                    ?.groupValues?.getOrNull(1)?.toIntOrNull()
                episodes.add(newEpisode(fixUrl(href)) {
                    name = anchor.text().trim().ifBlank { "Episodio ${number ?: episodes.size + 1}" }
                    episode = number
                    posterUrl = poster
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

    // ─── Enlaces de vídeo ──────────────────────────────────────────────────────
    // El reproductor de este sitio es un componente Next.js que funciona así:
    //   1) La página /watch/<slug> incluye en su payload RSC un array "sources"
    //      con los proveedores disponibles (R2, Dailymotion, Ok.ru, Rumble, Vk,
    //      Odysee…) y, por cada uno, un `token` cifrado.
    //   2) El cliente hace POST /api/player/source con {"token": …} y recibe el
    //      enlace real ({"url": …}). Si falla, pide un token nuevo a
    //      POST /api/player/refresh y reintenta.
    // OJO: el endpoint GET /api/sources?episodeId=… NO sirve: devuelve datos de
    // ejemplo (dailymotion/rumble/cdn.example.com).
    // El proveedor "video" (R2) entrega un HLS servido por el propio sitio
    // (/api/r2-media?u=…), que no cubre ningún extractor: se emite como M3U8.

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val html = app.get(data, referer = mainUrl).text
        val sources = rscSources(html)
        if (sources.isEmpty()) return false

        var found = false
        for (source in sources) {
            val token = source.token?.takeIf { it.isNotBlank() } ?: continue
            val label = source.label ?: source.name ?: source.provider ?: name

            for (raw in resolveSourceUrls(token, data)) {
                val link = fixUrl(raw.trim())

                // HLS servido por el propio sitio (proveedor R2).
                if (link.contains("/api/r2-media") ||
                    link.substringBefore('?').endsWith(".m3u8")
                ) {
                    callback.invoke(
                        newExtractorLink(label, label, link, ExtractorLinkType.M3U8) {
                            this.referer = mainUrl
                            this.quality = Qualities.Unknown.value
                        }
                    )
                    found = true
                } else if (loadExtractor(link, data, subtitleCallback, callback)) {
                    found = true
                }
            }
        }
        return found
    }

    /**
     * Extrae el array "sources" del payload RSC de la página del reproductor.
     * Cada elemento trae el proveedor y un token cifrado; las fuentes reales
     * solo se obtienen canjeando ese token.
     */
    private fun rscSources(html: String): List<RscSource> {
        val unescaped = html.replace("\\\"", "\"")
        val marker = "\"sources\":[{"
        val start = unescaped.indexOf(marker)
        if (start < 0) return emptyList()

        // Se localiza el cierre del array contando corchetes.
        val arrayStart = unescaped.indexOf('[', start + "\"sources\":".length)
        if (arrayStart < 0) return emptyList()
        var depth = 0
        var end = -1
        for (i in arrayStart until unescaped.length) {
            when (unescaped[i]) {
                '[' -> depth++
                ']' -> {
                    depth--
                    if (depth == 0) {
                        end = i
                        break
                    }
                }
            }
        }
        if (end < 0) return emptyList()
        val body = unescaped.substring(arrayStart + 1, end)

        // Los objetos no contienen llaves anidadas, así que basta el regex.
        return Regex("\\{[^{}]*\\}").findAll(body).mapNotNull { match ->
            val obj = match.value
            val token = obj.stringField("token") ?: return@mapNotNull null
            RscSource(
                token = token,
                provider = obj.stringField("provider"),
                name = obj.stringField("name"),
                label = obj.stringField("label"),
                type = obj.stringField("type"),
            )
        }.toList()
    }

    private fun String.stringField(key: String): String? =
        Regex("\"$key\"\\s*:\\s*\"([^\"]*)\"")
            .find(this)?.groupValues?.getOrNull(1)?.ifBlank { null }

    /**
     * Canjea un token por su enlace real mediante POST /api/player/source.
     * Si la respuesta no trae URL, reintenta con un token nuevo de
     * /api/player/refresh, igual que hace el reproductor web.
     */
    private suspend fun resolveSourceUrls(token: String, referer: String): List<String> {
        val headers = mapOf(
            "User-Agent" to USER_AGENT,
            "Origin" to mainUrl,
            "Accept" to "application/json, text/plain, */*",
        )

        suspend fun call(endpoint: String, value: String): PlayerSourceResponse? = try {
            app.post(
                "$mainUrl$endpoint",
                headers = headers,
                referer = referer,
                json = mapOf("token" to value),
            ).parsedSafe<PlayerSourceResponse>()
        } catch (_: Exception) {
            null
        }

        val first = call(PLAYER_SOURCE_ENDPOINT, token)
            ?.takeIf { !it.url.isNullOrBlank() || !it.qualities.isNullOrEmpty() }

        val result = first ?: call(PLAYER_REFRESH_ENDPOINT, token)
            ?.token?.takeIf { it.isNotBlank() }
            ?.let { call(PLAYER_SOURCE_ENDPOINT, it) }

        val urls = mutableListOf<String>()
        result?.qualities?.mapNotNullTo(urls) { it.url?.ifBlank { null } }
        result?.url?.ifBlank { null }?.let { if (it !in urls) urls.add(it) }
        return urls
    }

    // ─── Utilidades ────────────────────────────────────────────────────────────

    /** Meta tag: primero por property (og:…) y luego por name. */
    private fun metaContent(doc: Document, key: String): String? =
        doc.selectFirst("meta[property='$key'], meta[name='$key']")
            ?.attr("content")?.trim()?.ifBlank { null }

    /** Lee un valor string del payload RSC (que usa comillas escapadas \"…\"). */
    private fun rscValue(html: String, key: String): String? {
        val unescaped = html.replace("\\\"", "\"")
        return Regex("\"$key\"\\s*:\\s*\"([^\"]+)\"")
            .find(unescaped)?.groupValues?.getOrNull(1)
    }

    // ─── Modelos JSON ──────────────────────────────────────────────────────────

    data class EpisodesResponse(val episodes: List<EpisodeItem>? = null)

    data class EpisodeItem(val number: Int? = null, val title: String? = null)

    /** Fuente tal como aparece en el array "sources" del payload RSC. */
    data class RscSource(
        val token: String? = null,
        val provider: String? = null,
        val name: String? = null,
        val label: String? = null,
        val type: String? = null,
    )

    /** Respuesta de /api/player/source (y /api/player/refresh para el token). */
    data class PlayerSourceResponse(
        val url: String? = null,
        val qualities: List<QualityItem>? = null,
        val token: String? = null,
        val error: String? = null,
    )

    data class QualityItem(val url: String? = null, val label: String? = null)

    companion object {
        // Clases reales presentes en portada, listados y buscador.
        private const val CARD_SELECTOR = "article.poster-card, div.poster-card, a.poster-card"
        private const val PLAYER_SOURCE_ENDPOINT = "/api/player/source"
        private const val PLAYER_REFRESH_ENDPOINT = "/api/player/refresh"
        private const val USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/122.0.0.0 Safari/537.36"
        private val SEASON_REGEX =
            Regex("\"slug\"\\s*:\\s*\"([^\"]+)\"\\s*,\\s*\"label\"\\s*:\\s*\"([^\"]+)\"")
    }
}
