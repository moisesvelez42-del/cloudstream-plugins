package com.example

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

/**
 * CloudStream3 extension para https://old.donghualife.com ("Donghualife 2.0"),
 * el sitio Drupal anterior de DonghuaLife.
 *
 * ── Estructura verificada (2026-10) ──────────────────────────────────────────
 * Tarjetas (todas con la misma forma: .imagen > a[href] + img, .titulo):
 *   div.serie    → /series/<slug>   (y /season/<slug>-N en el bloque de temporadas)
 *   div.episode  → /episode/<slug>  (listado "Últimos episodios", incluye .subtitulo)
 *   div.episodio → /episode/<slug>  (bloque "Más vistos - hoy")
 *   div.movie    → /movie/<slug>    (restringidas a VIP: no reproducen)
 *
 * Listados (paginan con ?page=N empezando en 0):
 *   /episodios  → .view-id-episodios_home
 *   /donghuas   → .view-id-donghuas            (también /donghuas/<genero>)
 *   /en-emision → .view-id-en_emision
 *   /en-pausa   → .view-id-en_pausa
 *   /finalizado → .view-id-finalizado
 *   /movies     → .view-id-pelis_donghuas
 * Búsqueda: /search?search_api_fulltext=<query>  → .view-id-search
 *
 * Ficha de serie: temporadas en .view-id-temporadas → /season/<slug>-N, y cada
 * temporada expone los episodios en .view-id-episodios (tabla <tr> con
 * <th scope="row">N</th> y <td><a href="/episode/...">Episodio N</a></td>).
 *
 * Reproductor del episodio:
 *   <a class="toggle-enlace" data-video="<url>" title="<proveedor>">  (Rumble,
 *   Dailymotion, …) más el <iframe id="iframe-episode"> ya incrustado.
 */
class OldDonghuaLifeProvider : MainAPI() {

    override var mainUrl = "https://old.donghualife.com"
    override var name = "OldDonghuaLife"
    override var lang = "es"
    override val hasMainPage = true
    override val supportedTypes = setOf(TvType.Anime, TvType.AnimeMovie, TvType.Movie)

    // ─── Portada ──────────────────────────────────────────────────────────────

    override val mainPage = mainPageOf(
        "$mainUrl/episodios" to "Últimos episodios",
        "$mainUrl/donghuas" to "Donghuas",
        "$mainUrl/en-emision" to "En emisión",
        "$mainUrl/en-pausa" to "En pausa",
        "$mainUrl/finalizado" to "Finalizado",
        "$mainUrl/movies" to "Pelis Donghuas (VIP)",
    )

    /** Contenedor .view-* que aloja el listado principal de cada sección. */
    private val sectionViews = mapOf(
        "$mainUrl/episodios" to ".view-id-episodios_home",
        "$mainUrl/donghuas" to ".view-id-donghuas",
        "$mainUrl/en-emision" to ".view-id-en_emision",
        "$mainUrl/en-pausa" to ".view-id-en_pausa",
        "$mainUrl/finalizado" to ".view-id-finalizado",
        "$mainUrl/movies" to ".view-id-pelis_donghuas",
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val view = sectionViews[request.data]
            ?: return newHomePageResponse(request.name, emptyList())
        // El sitio pagina con ?page=N y empieza en 0; CloudStream empieza en 1.
        val doc = fetch("${request.data}?page=${(page - 1).coerceAtLeast(0)}")
        val items = doc.select("$view .views-row").mapNotNull { it.toSearchResult() }
        return newHomePageResponse(request.name, items, hasNext = items.isNotEmpty())
    }

    // ─── Parser de tarjetas ───────────────────────────────────────────────────

    private fun Element.toSearchResult(): SearchResponse? {
        val anchor = selectFirst(".imagen a[href]")
            ?: selectFirst(".titulo a[href]")
            ?: selectFirst("a[href]")
            ?: return null
        val href = anchor.attr("href").trim()
        if (href.isBlank()) return null
        val url = fixUrl(href)

        // En las tarjetas de episodio .titulo es la serie y .subtitulo el episodio.
        var title = selectFirst(".titulo")?.text()?.trim().orEmpty()
        selectFirst(".subtitulo")?.text()?.trim()?.takeIf { it.isNotBlank() }?.let { sub ->
            title = if (title.isBlank()) sub else "$title · $sub"
        }
        if (title.isBlank()) title = selectFirst("img")?.attr("alt")?.trim().orEmpty()
        if (title.isBlank()) return null

        val poster = selectFirst(".imagen img")?.let { imgSrc(it) }

        return if (url.contains("/movie/")) {
            newMovieSearchResponse(title, url, TvType.Movie) { this.posterUrl = poster }
        } else {
            newAnimeSearchResponse(title, url, TvType.Anime) { this.posterUrl = poster }
        }
    }

    // ─── Búsqueda ─────────────────────────────────────────────────────────────

    override suspend fun search(query: String): List<SearchResponse> {
        val doc = fetch("$mainUrl/search?search_api_fulltext=${query.encodeUri()}")
        return doc.select(".view-id-search .views-row").mapNotNull { it.toSearchResult() }
    }

    // ─── Ficha (serie / temporada / episodio suelto / película) ───────────────

    override suspend fun load(url: String): LoadResponse? {
        val doc = fetch(url)

        // Las tarjetas de "Últimos episodios" apuntan al episodio: resolvemos la
        // serie padre para mostrar la ficha completa con todas las temporadas.
        if (url.contains("/episode/")) {
            val seriesUrl = doc.selectFirst("a.home-serie[href]")?.attr("href")?.trim()
                ?.takeIf { it.isNotBlank() }?.let { fixUrl(it) }
            if (!seriesUrl.isNullOrBlank() && seriesUrl != url) return load(seriesUrl)
            return loadSingleEpisode(url, doc)
        }

        val bodyClass = doc.selectFirst("body")?.className().orEmpty()

        if (url.contains("/movie/") || bodyClass.contains("page-node-type-movies")) {
            return loadMovie(url, doc)
        }
        if (url.contains("/season/") || bodyClass.contains("page-node-type-temporada")) {
            return loadSeason(url, doc)
        }
        if (url.contains("/series/") || bodyClass.contains("page-node-type-series")) {
            return loadSeries(url, doc)
        }
        return loadSingleEpisode(url, doc)
    }

    private suspend fun loadSeries(url: String, doc: Document): LoadResponse? {
        val title = doc.selectFirst(".node--type-series .field--name-title")?.text()?.trim()
            ?.ifBlank { null }
            ?: doc.selectFirst("h2 .field--name-title")?.text()?.trim()?.ifBlank { null }
            ?: return null

        val poster = doc.selectFirst(".field--name-field-poster img")?.let { imgSrc(it) }
        val plot = doc.selectFirst(".field--name-field-synopsis")?.text()?.trim()?.ifBlank { null }
        val tags = doc.select(".field--name-field-genero .field__item a").map { it.text().trim() }

        val seasonUrls = doc.select(".view-id-temporadas a[href*='/season/']")
            .map { fixUrl(it.attr("href").trim()) }
            .filter { it.isNotBlank() }
            .distinct()

        val episodes = mutableListOf<Episode>()
        if (seasonUrls.isEmpty()) {
            episodes += parseEpisodes(doc, 1)
        } else {
            for (seasonUrl in seasonUrls) {
                val seasonDoc = try {
                    fetch(seasonUrl)
                } catch (_: Exception) {
                    continue
                }
                val seasonNum = seasonNumber(
                    seasonDoc.selectFirst(".field--name-title")?.text(), seasonUrl
                )
                episodes += parseEpisodes(seasonDoc, seasonNum)
            }
        }

        return newAnimeLoadResponse(title, url, TvType.Anime) {
            this.posterUrl = poster
            this.plot = plot
            this.tags = tags
            addEpisodes(DubStatus.Subbed, episodes)
        }
    }

    private suspend fun loadSeason(url: String, doc: Document): LoadResponse? {
        val title = doc.selectFirst(".node--type-temporada .field--name-title")?.text()?.trim()
            ?.ifBlank { null }
            ?: return null
        val poster = doc.selectFirst(".field--name-field-poster img")?.let { imgSrc(it) }
        val episodes = parseEpisodes(doc, seasonNumber(title, url))

        return newAnimeLoadResponse(title, url, TvType.Anime) {
            this.posterUrl = poster
            addEpisodes(DubStatus.Subbed, episodes)
        }
    }

    private suspend fun loadSingleEpisode(url: String, doc: Document): LoadResponse? {
        val title = doc.selectFirst(".embed .titulo .field--name-title")?.text()?.trim()
            ?.ifBlank { null }
            ?: doc.selectFirst("title")?.text()?.substringBefore("|")?.trim()?.ifBlank { null }
            ?: return null
        val poster = doc.selectFirst("meta[property=og:image]")?.attr("content")
            ?.trim()?.ifBlank { null }

        return newAnimeLoadResponse(title, url, TvType.Anime) {
            this.posterUrl = poster
            addEpisodes(DubStatus.Subbed, listOf(newEpisode(url) { this.name = title }))
        }
    }

    private suspend fun loadMovie(url: String, doc: Document): LoadResponse? {
        // Las películas están restringidas a VIP, así que el nodo solo trae el
        // aviso: el título se recupera del <title> o del slug.
        val title = doc.selectFirst(".node--type-movies .field--name-title")?.text()?.trim()
            ?.ifBlank { null }
            ?: doc.selectFirst("title")?.text()?.substringBefore("|")?.trim()?.ifBlank { null }
            ?: slugTitle(url)
        val poster = doc.selectFirst(".field--name-field-poster img")?.let { imgSrc(it) }
            ?: doc.selectFirst("meta[property=og:image]")?.attr("content")?.trim()?.ifBlank { null }
        val plot = doc.selectFirst(".patreon-restricted-message")?.text()?.trim()?.ifBlank { null }

        return newMovieLoadResponse(title, url, TvType.Movie, url) {
            this.posterUrl = poster
            this.plot = plot
        }
    }

    // ─── Episodios / enlaces ──────────────────────────────────────────────────

    /** Tabla .view-id-episodios: <th scope="row">N</th> + <td><a href="/episode/…">. */
    private fun parseEpisodes(doc: Document, seasonNum: Int): List<Episode> {
        val episodes = mutableListOf<Episode>()
        doc.select(".view-id-episodios tr").forEach { row ->
            val anchor = row.selectFirst("td a[href*='/episode/']") ?: return@forEach
            val epUrl = fixUrl(anchor.attr("href").trim())
            if (epUrl.isBlank()) return@forEach
            val epName = anchor.text().trim()
            val epNum = row.selectFirst("th[scope=row]")?.text()?.trim()?.toIntOrNull()
                ?: Regex("(\\d+)").find(epName)?.groupValues?.getOrNull(1)?.toIntOrNull()
            episodes.add(newEpisode(epUrl) {
                this.name = epName
                this.season = seasonNum
                this.episode = epNum
            })
        }
        return episodes
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
        val doc = try {
            fetch(data)
        } catch (_: Exception) {
            return false
        }
        var found = false

        // Cada servidor del reproductor: <a class="toggle-enlace" data-video="…">
        doc.select("a.toggle-enlace[data-video]")
            .map { it.attr("data-video").trim() }
            .filter { it.isNotBlank() }
            .distinct()
            .forEach { link ->
                val ok = try {
                    loadServer(link, data, subtitleCallback, callback)
                } catch (_: Exception) {
                    false
                }
                if (ok) found = true
            }

        // Respaldo: el iframe ya incrustado en la página del episodio.
        if (!found) {
            doc.select("#video-container iframe[src], iframe#iframe-episode[src]").forEach { iframe ->
                val src = iframe.attr("src").trim()
                if (src.startsWith("http")) {
                    val ok = try {
                        loadServer(src, data, subtitleCallback, callback)
                    } catch (_: Exception) {
                        false
                    }
                    if (ok) found = true
                }
            }
        }

        return found
    }

    /**
     * Enruta cada servidor al extractor adecuado. Rumble y Vk no los resuelve
     * el `loadExtractor` incluido en la app (Rumble no existe y el de Vk quedó
     * desfasado con el nuevo formato de vkvideo.ru), así que se resuelven aquí.
     */
    private suspend fun loadServer(
        link: String,
        referer: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ): Boolean = when {
        link.contains("rumble.com") -> loadRumble(link, callback)
        link.contains("vk.com") || link.contains("vkvideo.ru") -> loadVk(link, callback)
        else -> loadExtractor(link, referer, subtitleCallback, callback)
    }

    /** Rumble: el embed público se resuelve con su API de reproductor. */
    private suspend fun loadRumble(
        embedUrl: String,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
        val id = Regex("""rumble\.com/embed/([^/?#"']+)""")
            .find(embedUrl)?.groupValues?.get(1)?.trim()?.takeIf { it.isNotBlank() }
            ?: return false

        val json = app.get(
            "https://rumble.com/embedJS/u3/?request=video&ver=2&v=$id",
            referer = embedUrl,
            headers = mapOf(
                "User-Agent" to USER_AGENT,
                "Accept" to "application/json, text/plain, */*",
            ),
        ).text.replace("\\/", "/")

        var found = false
        val emitted = mutableSetOf<String>()

        val candidates = Regex("""https?://[^"'\s]+?\.m3u8[^"'\s]*""")
            .findAll(json).map { it.value to ExtractorLinkType.M3U8 }.toList() +
            Regex("""https?://[^"'\s]+?\.mp4[^"'\s]*""")
                .findAll(json).map { it.value to ExtractorLinkType.VIDEO }.toList()

        candidates.forEach { (videoUrl, type) ->
            if (!emitted.add(videoUrl)) return@forEach
            callback.invoke(
                newExtractorLink("Rumble", "Rumble", videoUrl, type) {
                    this.referer = "https://rumble.com/"
                    this.headers = mapOf("User-Agent" to USER_AGENT)
                }
            )
            found = true
        }
        return found
    }

    /** Vk (vkvideo.ru): requiere la cookie que entrega en el primer redirect. */
    private suspend fun loadVk(
        url: String,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
        val headers = mapOf(
            "User-Agent" to USER_AGENT,
            "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
            "Accept-Language" to "en-US,en;q=0.5",
            "Referer" to "https://vk.com/",
        )
        val cookies = app.get(url, headers = headers, allowRedirects = false).cookies
        val html = app.get(url, headers = headers, cookies = cookies, allowRedirects = false)
            .text.replace("\\/", "/")

        var found = false
        val emitted = mutableSetOf<String>()

        // HLS: formatos nuevos (hls_ondemand / hls_fmp4) y clásico (hls).
        Regex(""""(?:hls_ondemand|hls_fmp4|hls)"\s*:\s*"(https?:[^"]+)"""")
            .findAll(html).forEach { m ->
                val videoUrl = m.groupValues[1]
                if (emitted.add(videoUrl)) {
                    callback.invoke(
                        newExtractorLink("Vk", "Vk", videoUrl, ExtractorLinkType.M3U8) {
                            this.referer = "https://vk.com/"
                            this.headers = mapOf(
                                "User-Agent" to USER_AGENT,
                                "Referer" to "https://vk.com/",
                            )
                        }
                    )
                    found = true
                }
            }

        // MP4 por calidad: nombres nuevos (mp4_1080) o clásicos (url720).
        Regex(""""(?:mp4_|url)(\d+)"\s*:\s*"(https?:[^"]+)"""")
            .findAll(html).forEach { m ->
                val quality = m.groupValues[1]
                val videoUrl = m.groupValues[2]
                if (emitted.add(videoUrl)) {
                    callback.invoke(
                        newExtractorLink("Vk", "Vk", videoUrl, ExtractorLinkType.VIDEO) {
                            this.referer = "https://vk.com/"
                            this.headers = mapOf(
                                "User-Agent" to USER_AGENT,
                                "Referer" to "https://vk.com/",
                            )
                            this.quality = getQualityFromName(quality)
                        }
                    )
                    found = true
                }
            }
        return found
    }

    // ─── Helpers ──────────────────────────────────────────────────────────────

    private suspend fun fetch(url: String): Document =
        app.get(url, referer = mainUrl, headers = mapOf("User-Agent" to USER_AGENT)).document

    private fun imgSrc(img: Element): String? {
        val raw = img.attr("data-src").ifBlank { img.attr("src") }
            .ifBlank { img.attr("srcset").substringBefore(" ").trim() }
        return raw.takeIf { it.isNotBlank() }?.let { fixUrl(it) }
    }

    /** Número de temporada desde el título ("Serie - 2") o desde el slug (-2). */
    private fun seasonNumber(title: String?, url: String): Int {
        title?.trim()?.let { t ->
            Regex("(\\d+)\\s*$").find(t)?.groupValues?.getOrNull(1)?.toIntOrNull()?.let { return it }
        }
        val slug = url.trimEnd('/').substringAfterLast('/')
        return Regex("(\\d+)\\s*$").find(slug)?.groupValues?.getOrNull(1)?.toIntOrNull() ?: 1
    }

    private fun slugTitle(url: String): String =
        url.trimEnd('/').substringAfterLast('/').split("-", "_")
            .filter { it.isNotBlank() }
            .joinToString(" ") { it.replaceFirstChar { c -> c.uppercase() } }

    private fun String.encodeUri(): String = java.net.URLEncoder.encode(this, "UTF-8")

    companion object {
        private const val USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
    }
}
