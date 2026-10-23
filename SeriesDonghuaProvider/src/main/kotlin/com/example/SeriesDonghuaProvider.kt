package com.example

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Element

/**
 * CloudStream3 extension for https://seriesdonghua.com  (v5)
 *
 * ── Video loading strategy ───────────────────────────────────────────────────
 * Actualmente (verificado 2026-10 sobre el HTML real) la página del episodio ya
 * NO contiene el bloque ofuscado `eval(function(h,u,n,t,e,r){...})` con
 * `VIDEO_MAP_JSON`. En su lugar el reproductor carga los servidores vía AJAX:
 *
 *   POST https://seriesdonghua.com/api/player/get-server
 *   headers: X-CSRF-TOKEN: <meta[name=csrf-token]>, X-Requested-With: XMLHttpRequest
 *   body:    { "video_id": <int>, "server_index": <int> }
 *
 * La respuesta es JSON:
 *   {"success":true,"embed_url":"https:\/\/geo.dailymotion.com\/player\/..."}
 *
 * Cada botón `.server-tab-btn` del HTML expone `data-video-id` y
 * `data-server-index` (0=Dailymotion, 1=OK.ru, 2=Rumble, 3=VOE en el episodio
 * comprobado). Enviamos una petición por cada servidor y pasamos el `embed_url`
 * a `loadExtractor` (que ya soporta dailymotion, ok.ru, rumble, voe.sx, etc.).
 *
 * El antiguo decodificador del bloque eval() se conserva como fallback por si
 * algún episodio antiguo todavía lo usa.
 */
class SeriesDonghuaProvider : MainAPI() {

    override var mainUrl  = "https://seriesdonghua.com"
    override var name     = "SeriesDonghua"
    override var lang     = "es"
    override val hasMainPage   = true
    override val supportedTypes = setOf(TvType.Anime)

    companion object {
        private const val USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) " +
            "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0.0.0 Safari/537.36"

        private const val BASE64_CHARSET =
            "0123456789abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ+/"

        private fun fromCustomBase(token: String, fromBase: Int): Long {
            val alphabet = BASE64_CHARSET.take(fromBase)
            var value = 0L
            for (ch in token) {
                val idx = alphabet.indexOf(ch)
                if (idx < 0) continue
                value = value * fromBase + idx
            }
            return value
        }

        /**
         * Decodifica el antiguo bloque eval() del sitio (legacy).
         * Los parámetros se extraen dinámicamente de la página, no están fijos.
         */
        fun decodeEvalBlock(
            encoded: String,
            charset: String,
            offset: Int,
            sepIndex: Int,
        ): String {
            val sepChar = charset[sepIndex]
            val sb = StringBuilder()
            for (token in encoded.split(sepChar)) {
                if (token.isEmpty()) continue
                var s = token
                for (j in charset.indices) {
                    s = s.replace(charset[j].toString(), j.toString())
                }
                val decimal  = fromCustomBase(s, sepIndex)
                val charCode = decimal - offset
                if (charCode > 0) sb.append(charCode.toInt().toChar())
            }
            return try {
                String(sb.toString().toByteArray(Charsets.ISO_8859_1), Charsets.UTF_8)
            } catch (_: Exception) {
                sb.toString()
            }
        }

        /**
         * Extrae todas las URLs de embed/video del JS decodificado.
         * El JS contiene: document.write('<script>const VIDEO_MAP_JSON={...}')
         */
        fun extractEmbedsFromJs(js: String): List<String> {
            val results = mutableListOf<String>()
            val seen    = mutableSetOf<String>()

            fun add(url: String) {
                val clean = url.trim('"', '\'', ' ')
                if (clean.startsWith("http") && seen.add(clean)) results.add(clean)
            }

            // Quita progresivamente todos los niveles de escapado con backslash
            var unescaped = js
            for (i in 0..5) {
                unescaped = unescaped.replace(Regex("""\\(.)"""), "$1")
            }

            val urlRegex = Regex("""https?://[^\s"'<>&`]+""")
            urlRegex.findAll(unescaped).forEach { m ->
                if (!m.value.contains("player.php")) {
                    add(m.value)
                }
            }

            // Dailymotion se incrusta como "asura":""VIDEO_ID""
            val dmRegex = Regex(""""asura"\s*:\s*""?([a-zA-Z0-9_-]{5,25})""?""")
            dmRegex.find(unescaped)?.groupValues?.getOrNull(1)?.let { dmId ->
                if (!dmId.startsWith("http")) add("https://www.dailymotion.com/embed/video/$dmId")
            }

            return results
        }
    }

    // ── Main page ─────────────────────────────────────────────────────────────
    // Todas las secciones paginan con el parámetro de query ?page=N
    // (verificado: /todos-los-donghuas/page/2/ da 404, /todos-los-donghuas?page=2 da 200).

    override val mainPage = mainPageOf(
        "$mainUrl/episodios"             to "Nuevos Episodios",
        "$mainUrl/todos-los-donghuas"    to "Todos los Donghuas",
        "$mainUrl/donghuas-en-emision"   to "En Emisión",
        "$mainUrl/donghuas-finalizados"  to "Finalizados",
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = if (page > 1) "${request.data}?page=$page" else request.data
        val doc = app.get(url, headers = mapOf("User-Agent" to USER_AGENT)).document
        val items = doc.select("article.donghua-card").mapNotNull { it.toSearchResult() }
        return newHomePageResponse(request.name, items, hasNext = items.size >= 20)
    }

    // ── Card parser ───────────────────────────────────────────────────────────
    // article.donghua-card > a[href] > div.card-poster-wrap > img[src]
    //                               > div.card-body > h3.card-title

    private fun Element.toSearchResult(): SearchResponse? {
        val anchor  = selectFirst("a[href]") ?: return null
        val urlAttr = anchor.attr("href").trim().ifBlank { return null }
        val url     = fixUrl(urlAttr)

        val title = selectFirst(".card-title")?.text()?.trim()?.takeIf { it.isNotBlank() }
            ?: anchor.attr("title").trim().ifBlank { null }
            ?: return null

        val imgEl  = selectFirst("img")
        val rawImg = imgEl?.attr("src")?.ifBlank { imgEl.attr("data-src") }
        val poster = rawImg?.takeIf { it.isNotBlank() }?.let { fixUrl(it) }

        return newAnimeSearchResponse(title, url, TvType.Anime) { posterUrl = poster }
    }

    // ── Search ────────────────────────────────────────────────────────────────
    // Endpoint actual: /buscar.php?s=<query> (el antiguo /busquedas/<query> da 404).
    // Devuelve las mismas tarjetas article.donghua-card que la portada.

    override suspend fun search(query: String): List<SearchResponse> {
        val encoded = java.net.URLEncoder.encode(query.trim(), "utf-8").replace("+", "%20")
        val doc = app.get(
            "$mainUrl/buscar.php?s=$encoded",
            headers = mapOf("User-Agent" to USER_AGENT)
        ).document

        return doc.select("article.donghua-card").mapNotNull { it.toSearchResult() }
    }

    // ── Load ──────────────────────────────────────────────────────────────────

    override suspend fun load(url: String): LoadResponse? {
        val h   = mapOf("User-Agent" to USER_AGENT)
        val doc = app.get(url, headers = h).document

        // ── Página de episodio ────────────────────────────────────────────────
        // Marcadores actuales: .server-tab-btn (pestañas de servidor) / #player-container.
        if (doc.selectFirst(".server-tab-btn, #player-container, .player-card-wrapper") != null) {

            // Intenta navegar a la página de la serie ("Lista de Episodios")
            val seriesUrl = doc.selectFirst(
                "a[aria-label='Lista de Episodios'], a:has(i.fa-th-list), " +
                "a[title*='todos los episodios'], a[title*='lista de episodios']"
            )?.attr("href")?.trim()?.takeIf { it.isNotBlank() }?.let { fixUrl(it) }

            if (!seriesUrl.isNullOrBlank() && seriesUrl != url) {
                return load(seriesUrl)
            }

            val title  = doc.selectFirst("h1.player-title")?.text()?.trim()
                ?: doc.selectFirst("h1")?.text()?.trim()
                ?: "Episodio"
            val poster = doc.selectFirst("meta[property=og:image]")?.attr("content")
            return newMovieLoadResponse(title, url, TvType.Movie, url) { posterUrl = poster }
        }

        // ── Página de serie ───────────────────────────────────────────────────

        val title = doc.selectFirst("h1.hero-title")?.text()?.trim()
            ?: doc.selectFirst("h1")?.text()?.trim()
            ?: return null

        // Poster: <img class="hero-poster" src=...>, o el fondo .hero-banner-bg, o og:image
        val poster = doc.selectFirst("img.hero-poster")?.attr("src")?.trim()?.takeIf { it.isNotBlank() }
            ?.let { fixUrl(it) }
            ?: doc.selectFirst(".hero-banner-bg")?.attr("style")
                ?.substringAfter("url(", "")?.substringBefore(")")
                ?.trim('\'', '"', ' ')?.takeIf { it.isNotBlank() }?.let { fixUrl(it) }
            ?: doc.selectFirst("meta[property=og:image]")?.attr("content")

        val synopsis = doc.select(".glass-panel:has(h2:contains(Sinopsis)) p")
            .joinToString("\n") { it.text().trim() }.ifBlank { null }
        val genres = doc.select("a.genre-pill").map { it.text().trim() }.filter { it.isNotBlank() }

        // Cada episodio: article.episode-card-item[data-ep] > a[href] > .card-title
        val episodes = doc.select("article.episode-card-item").mapNotNull { card ->
            val anchor = card.selectFirst("a[href]") ?: return@mapNotNull null
            val epUrl  = fixUrl(anchor.attr("href").trim())
            if (epUrl.isBlank()) return@mapNotNull null

            val epNum = card.attr("data-ep").toIntOrNull()
                ?: Regex("""-episodio-(\d+)""").find(epUrl)?.groupValues?.get(1)?.toIntOrNull()
            val epText = card.selectFirst(".card-title")?.text()?.trim()
                ?: anchor.attr("title").trim().ifBlank { "Episodio ${epNum ?: ""}" }

            newEpisode(epUrl) {
                name    = epText
                episode = epNum
                season  = 1
            }
        }

        return newAnimeLoadResponse(title, url, TvType.Anime) {
            posterUrl = poster
            plot      = synopsis
            tags      = genres
            addEpisodes(DubStatus.Subbed, episodes)
        }
    }

    // ── Video links ───────────────────────────────────────────────────────────

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
        val headers = mapOf(
            "User-Agent"      to USER_AGENT,
            "Referer"         to mainUrl,
            "Accept-Language" to "es-ES,es;q=0.9",
        )
        val response = app.get(data, headers = headers)
        val html     = response.text
        val doc      = response.document
        var found    = false

        // ── Strategy 1: API AJAX /api/player/get-server (esquema actual) ──────
        // Laravel exige que el POST viaje con la MISMA sesión del GET (cookie
        // XSRF-TOKEN) y con el token CSRF del meta de esa misma respuesta; si no,
        // responde "CSRF token mismatch". NiceHttp no persiste cookies solo, así
        // que se reenvían las de `response.cookies`.
        val csrf = doc.selectFirst("meta[name=csrf-token]")?.attr("content")?.trim()
        val serverTabs = doc.select(".server-tab-btn[data-video-id]")
        if (!csrf.isNullOrBlank() && serverTabs.isNotEmpty()) {
            for (tab in serverTabs) {
                val videoId     = tab.attr("data-video-id").trim()
                val serverIndex = tab.attr("data-server-index").trim()
                if (videoId.isEmpty() || serverIndex.isEmpty()) continue

                val body = try {
                    app.post(
                        "$mainUrl/api/player/get-server",
                        data = mapOf(
                            "video_id"     to videoId,
                            "server_index" to serverIndex,
                        ),
                        cookies = response.cookies,
                        headers = mapOf(
                            "User-Agent"       to USER_AGENT,
                            "Referer"          to data,
                            "X-CSRF-TOKEN"     to csrf,
                            "X-Requested-With" to "XMLHttpRequest",
                            "Accept"           to "application/json, text/plain, */*",
                        ),
                    ).text
                } catch (_: Exception) {
                    ""
                }

                val embed = Regex(""""embed_url"\s*:\s*"([^"]+)"""")
                    .find(body)?.groupValues?.getOrNull(1)
                    ?.replace("\\/", "/")
                    ?.trim()

                if (!embed.isNullOrBlank() && embed.startsWith("http")) {
                    loadExtractor(embed, data, subtitleCallback, callback)
                    found = true
                }
            }
        }
        if (found) return true

        // ── Strategy 2 (fallback legacy): decodificar el bloque eval() ────────
        val evalRegex = Regex(
            """eval\(function\(h,u,n,t,e,r\)\{r="".*?\}\("([^"]+)",(\d+),"([^"]+)",(\d+),(\d+),\d+\)\)""",
            RegexOption.DOT_MATCHES_ALL
        )
        val evalMatch = evalRegex.find(html)
        if (evalMatch != null) {
            val encoded  = evalMatch.groupValues[1]
            val charset  = evalMatch.groupValues[3]
            val offset   = evalMatch.groupValues[4].toIntOrNull() ?: 11
            val sepIndex = evalMatch.groupValues[5].toIntOrNull() ?: 2

            val decoded = try { decodeEvalBlock(encoded, charset, offset, sepIndex) }
            catch (_: Exception) { "" }

            if (decoded.isNotBlank()) {
                extractEmbedsFromJs(decoded).forEach { embedUrl ->
                    loadExtractor(embedUrl, data, subtitleCallback, callback)
                    found = true
                }
            }
        }

        // ── Strategy 3 (fallback): patrones de URL directos en el HTML ────────
        val rawPatterns = listOf(
            Regex("""https?://(?:geo\.)?dailymotion\.com/(?:player|embed)/[^\s"'<>&\\]+"""),
            Regex("""https?://www\.dailymotion\.com/embed/video/[A-Za-z0-9_-]+"""),
            Regex("""https?://ok\.ru/videoembed/\d+"""),
            Regex("""https?://rumble\.com/embed/[A-Za-z0-9]+(?:/[^\s"'<>&]*)?\??[^\s"'<>&]*"""),
            Regex("""https?://voe\.sx/e/[A-Za-z0-9]+"""),
            Regex("""https?://odysee\.com/\$/embed/[^\s"'<>&]+"""),
            Regex("""https?://[a-z0-9-]+\.filemoon\.[a-z]+/e/[A-Za-z0-9]+"""),
            Regex("""https?://[a-z0-9-]+\.streamwish\.[a-z]+/e/[A-Za-z0-9]+"""),
            Regex("""https?://dood\.[a-z]+/[de]/[A-Za-z0-9]+"""),
        )
        for (pattern in rawPatterns) {
            pattern.findAll(html).forEach { m ->
                val rawUrl = m.value.replace("\\/", "/")
                if (!rawUrl.contains("player.php")) {  // salta el wrapper propio del sitio
                    loadExtractor(rawUrl, data, subtitleCallback, callback)
                    found = true
                }
            }
        }

        // ── Strategy 4 (fallback): iframes directos en el DOM ─────────────────
        if (!found) {
            doc.select("iframe[src], iframe[data-src]").forEach { iframe ->
                val src = iframe.attr("src").ifBlank { iframe.attr("data-src") }.trim()
                if (src.startsWith("http") && !src.contains("cloudflare")) {
                    loadExtractor(src, data, subtitleCallback, callback)
                    found = true
                }
            }
        }

        // ── Strategy 5 (fallback): atributos data-* en contenedores ───────────
        if (!found) {
            doc.select("[data-video],[data-player],[data-src],[data-embed],[data-stream]")
                .forEach { el ->
                    for (attr in listOf("data-video", "data-player", "data-src", "data-embed", "data-stream")) {
                        val src = el.attr(attr).trim()
                        if (src.startsWith("http")) {
                            loadExtractor(src, data, subtitleCallback, callback)
                            found = true
                        }
                    }
                }
        }

        return found
    }
}
