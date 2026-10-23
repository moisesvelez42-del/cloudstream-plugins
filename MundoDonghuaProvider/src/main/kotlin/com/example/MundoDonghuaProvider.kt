package com.example

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.network.CloudflareKiller
import org.jsoup.nodes.Document
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
        private const val USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0.0.0 Safari/537.36"

        // Alfabeto del empaquetador Dean Edwards (0-9, a-z, A-Z) para notación en base N.
        private const val PACKER_ALPHA = "0123456789abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ"
    }

    private val cloudflareKiller = CloudflareKiller()

    // ── Petición central: TODAS las llamadas (portada, búsqueda, detalle, loadLinks)
    // pasan por aquí con cabeceras de navegador + interceptor anti-Cloudflare. ──
    private suspend fun getDocument(url: String): Document {
        val requestHeaders = mapOf(
            "User-Agent" to USER_AGENT,
            "Referer" to "$mainUrl/",
            "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/webp,*/*;q=0.8",
            "Accept-Language" to "es-ES,es;q=0.9"
        )

        var response = app.get(url, headers = requestHeaders, interceptor = cloudflareKiller)
        var attempt = 0
        // Reintenta con espera creciente si Cloudflare devuelve 403/503 o el reto "Just a moment".
        while (attempt < 3 && (
                    response.code == 403 || response.code == 503 ||
                            response.document.title().contains("Just a moment", true) ||
                            response.document.title().contains("Cloudflare", true)
                    )
        ) {
            attempt++
            Thread.sleep(1500L * attempt)
            response = app.get(url, headers = requestHeaders, interceptor = cloudflareKiller)
        }

        return response.document
    }

    // ── Portada: secciones verificadas (todas responden 200) ──
    override val mainPage = mainPageOf(
        "$mainUrl/" to "Estrenos",
        "$mainUrl/lista-episodios" to "Últimos Episodios",
        "$mainUrl/lista-donghuas" to "Todas las Series",
        "$mainUrl/lista-donghuas-emision" to "En Emisión",
        "$mainUrl/lista-donghuas-finalizados" to "Finalizadas",
        "$mainUrl/lista-donghuas-recopilados" to "Recopiladas",
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        // Los listados paginan como <seccion>/<pagina>. La portada ("/") no pagina.
        val url = if (page > 1) "${request.data.trimEnd('/')}/$page" else request.data
        val doc = getDocument(url)

        val homeItems = parseCards(doc)
        val hasNext = doc.selectFirst("nav.md-pagination a[href]") != null

        return newHomePageResponse(request.name, homeItems, hasNext = hasNext)
    }

    // ── Parser de tarjetas, reutilizado por portada, listados y búsqueda ──
    private fun parseCards(doc: Document): List<SearchResponse> {
        val items = mutableListOf<SearchResponse>()

        // Carrusel de la portada: <a class="md-carousel-item" href="/donghua/slug"> + h3 + img
        doc.select("a.md-carousel-item").forEach { a ->
            val title = a.selectFirst(".md-carousel-label h3")?.text()?.trim()
                ?: a.selectFirst("img")?.attr("alt")?.trim()
            toSearchResponse(a.attr("href"), title, posterOf(a))?.let { items.add(it) }
        }

        // Rejillas: <div class="md-card"><a href="/donghua/slug"|"/ver/slug/ep"> + .md-card-title + img
        doc.select(".md-card a[href]").forEach { a ->
            val title = a.selectFirst(".md-card-title")?.text()?.trim()
                ?: a.selectFirst("img")?.attr("alt")?.trim()
            toSearchResponse(a.attr("href"), title, posterOf(a))?.let { items.add(it) }
        }

        return items.distinctBy { it.url }
    }

    /** Poster de una tarjeta (data-src > src > data-original). */
    private fun posterOf(container: Element): String? {
        val img = container.selectFirst("img") ?: return null
        val raw = img.attr("data-src").ifBlank {
            img.attr("src").ifBlank { img.attr("data-original") }
        }
        return raw.takeIf { it.isNotBlank() && !it.startsWith("data:") }?.let { fixUrl(it) }
    }

    private fun toSearchResponse(href: String?, rawTitle: String?, poster: String?): SearchResponse? {
        if (href.isNullOrBlank()) return null
        val abs = fixUrl(href)

        // Las tarjetas de episodio (/ver/<slug>/<num>) se normalizan a su serie (/donghua/<slug>).
        val seriesUrl = when {
            abs.contains("/donghua/") -> abs
            abs.contains("/ver/") -> {
                val slug = abs.substringAfter("/ver/").substringBefore("/")
                if (slug.isBlank()) return null else "$mainUrl/donghua/$slug"
            }
            else -> return null
        }

        // Quita el sufijo "Episodio N"/"Capítulo N" de las tarjetas de episodio.
        val name = rawTitle.orEmpty()
            .replace(Regex("""\s*-?\s*(Episodio|Cap[íi]tulo)\s*\d+.*$""", RegexOption.IGNORE_CASE), "")
            .trim()
            .ifBlank { seriesUrl.substringAfterLast('/') }

        val type = if (seriesUrl.contains("/pelicula/")) TvType.Movie else TvType.Anime
        return if (type == TvType.Movie) {
            newMovieSearchResponse(name, seriesUrl, type) { posterUrl = poster }
        } else {
            newAnimeSearchResponse(name, seriesUrl, type) { posterUrl = poster }
        }
    }

    // ── Búsqueda: GET /busquedas/<query> (filtra de verdad; el query param devolvía todo el catálogo) ──
    override suspend fun search(query: String): List<SearchResponse> {
        val doc = getDocument("$mainUrl/busquedas/${query.encodeUri()}")
        return parseCards(doc)
    }

    // ── Detalle de serie ──
    override suspend fun load(url: String): LoadResponse? {
        val doc = getDocument(url)

        val title = doc.selectFirst("h1.md-detail-title")?.text()?.trim()
            ?: doc.selectFirst("meta[property=og:title]")?.attr("content")?.trim()
            ?: return null

        var poster = doc.selectFirst("meta[property=og:image]")?.attr("content")?.trim()
            ?.takeIf { it.isNotBlank() }
        if (poster == null) {
            poster = doc.selectFirst(".md-detail-poster img")?.let { img ->
                img.attr("data-src").ifBlank { img.attr("src") }
            }?.takeIf { it.isNotBlank() }?.let { fixUrl(it) }
        }

        val plot = doc.selectFirst(".md-detail-synopsis")?.text()?.trim()?.takeIf { it.isNotBlank() }

        val genres = doc.select(".md-genres-block .md-genre-tags a")
            .map { it.text().trim() }
            .filter { it.isNotBlank() }

        val statusText = doc.selectFirst(".md-emision-badge")?.text()?.trim()?.lowercase().orEmpty()
        val showStatus = when {
            statusText.contains("emisi") -> ShowStatus.Ongoing   // "En emisión"
            else -> ShowStatus.Completed                          // "Finalizada"
        }

        // Episodios: <li class="md-episode-item" data-ep="N"><a class="md-ep-link" href="/ver/<slug>/<N>">
        val episodes = doc.select("ul.md-episode-list li.md-episode-item")
            .mapIndexedNotNull { index, li ->
                val a = li.selectFirst("a.md-ep-link") ?: return@mapIndexedNotNull null
                val epUrl = fixUrl(a.attr("href"))
                val epNum = li.attr("data-ep").toIntOrNull()
                    ?: a.selectFirst("h5")?.text()
                        ?.let { t -> Regex("""(\d+)\s*$""").find(t)?.groupValues?.get(1)?.toIntOrNull() }
                    ?: (index + 1)
                newEpisode(epUrl) {
                    this.name = "Episodio $epNum"
                    this.episode = epNum
                }
            }
            .toMutableList()

        // Respaldo por si alguna ficha no lista episodios: permite reproducir desde la ficha.
        if (episodes.isEmpty()) {
            episodes.add(newEpisode(url) {
                this.name = "Ver ahora"
                this.episode = 1
            })
        }

        val type = if (url.contains("/pelicula/")) TvType.Movie else TvType.Anime

        if (type == TvType.Movie) {
            return newMovieLoadResponse(title, url, type, url) {
                this.posterUrl = poster
                this.plot = plot
                this.tags = genres
            }
        }

        return newAnimeLoadResponse(title, url, type) {
            this.posterUrl = poster
            this.plot = plot
            this.tags = genres
            this.showStatus = showStatus
            addEpisodes(DubStatus.Subbed, episodes.reversed())
        }
    }

    // ── Reproductores ──
    // La página de episodio (/ver/...) NO trae iframes en el HTML: los servidores se
    // construyen con JS ofuscado (empaquetador Dean Edwards) que contiene, en texto,
    // las URLs reales: Filemoon (bysekoze.com), Voe (voe.sx), VidHide (vidhidepro.com),
    // StreamWish (embedwish.com) y Asura (mdplayer.xyz, HLS). Se extraen del JS.
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val doc = getDocument(data)
        var found = false

        // 1) Enlaces estáticos (iframes / video source), por si el sitio vuelve a usarlos.
        doc.select("iframe").forEach { iframe ->
            val src = iframe.attr("data-src").ifBlank { iframe.attr("src") }
            if (src.startsWith("http")) {
                loadExtractor(src, data, subtitleCallback, callback)
                found = true
            }
        }

        doc.select("video source").forEach { source ->
            val src = source.attr("src")
            if (src.startsWith("http")) {
                loadExtractor(src, data, subtitleCallback, callback)
                found = true
            }
        }

        // 2) URLs embebidas en el JS ofuscado de los reproductores (comillas simples a veces escapadas: \').
        val decoded = decodePackerScripts(doc.outerHtml())
        Regex("""(?:src|file)\s*[:=]\s*\\?['"]?\s*(https?://[^'"\\\s]+)""").findAll(decoded).forEach { m ->
            loadExtractor(m.groupValues[1], data, subtitleCallback, callback)
            found = true
        }

        return found
    }

    /** Decodifica todos los bloques `eval(function(p,a,c,k,e,d){...}('...',a,c,'dict'.split('|'),0,{}))`. */
    private fun decodePackerScripts(html: String): String {
        val result = StringBuilder()
        val regex = Regex(
            """function\(p,a,c,k,e,d\)\{.*?\}\('(.*?)',(\d+),(\d+),'(.*?)'\.split\('\|'\)""",
            RegexOption.DOT_MATCHES_ALL
        )
        regex.findAll(html).forEach { m ->
            val payload = m.groupValues[1]
            val radix = m.groupValues[2].toIntOrNull() ?: return@forEach
            val count = m.groupValues[3].toIntOrNull() ?: return@forEach
            val dict = m.groupValues[4].split("|")
            result.append(unpack(payload, radix, count, dict)).append('\n')
        }
        return result.toString()
    }

    /** Implementación del desempaquetado Dean Edwards: sustituye cada token base-N por su palabra. */
    private fun unpack(payload: String, radix: Int, count: Int, dict: List<String>): String {
        var out = payload
        for (i in count - 1 downTo 0) {
            val value = dict.getOrNull(i)
            if (value.isNullOrEmpty()) continue
            val token = baseN(i, radix)
            if (token.isEmpty()) continue
            out = Regex("\\b" + Regex.escape(token) + "\\b").replace(out) { value }
        }
        return out
    }

    private fun baseN(num: Int, radix: Int): String {
        if (num == 0) return "0"
        var n = num
        val sb = StringBuilder()
        while (n > 0) {
            sb.insert(0, PACKER_ALPHA[n % radix])
            n /= radix
        }
        return sb.toString()
    }

    private fun String.encodeUri(): String =
        java.net.URLEncoder.encode(this, "UTF-8")
}
