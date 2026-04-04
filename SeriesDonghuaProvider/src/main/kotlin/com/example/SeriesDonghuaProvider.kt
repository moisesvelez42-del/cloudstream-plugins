package com.example

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Element

/**
 * CloudStream3 extension for https://seriesdonghua.com  (v4)
 *
 * ── Video loading strategy ───────────────────────────────────────────────────
 * Episode pages embed ALL video-server URLs inside an obfuscated eval() block:
 *
 *   eval(function(h,u,n,t,e,r){ … }("ENCODED_STR", BASE, "CHARSET", OFFSET, SEP_IDX, r))
 *
 * The parameters change with each deployment, so we extract them dynamically.
 *
 * The decoded output is a <script> block containing:
 *   const VIDEO_MAP_JSON={
 *     "skadi":"\"https:\\/\\/ok.ru\\/videoembed\\/...\""
 *     "fembed":"\"https:\\/\\/rumble.com\\/embed\\/...\""
 *     "tape":"\"https:\\/\\/odysee.com\\/$\\/embed\\/...\""
 *     "amagi":"\"https:\\/\\/voe.sx\\/e\\/...\""
 *     "asura":"\"DAILYMOTION_VIDEO_ID\""   ← ID only, needs prefix
 *   }
 *
 * Dailymotion is embedded as:
 *   https://www.dailymotion.com/embed/video/<id>
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
         * Decode the site's eval() block.
         * Params are extracted dynamically from the page (they are NOT hardcoded).
         *
         * @param encoded  The obfuscated string (h)
         * @param base     Lookup base used inside `fromCustomBase` – unused for char-replacement step
         * @param charset  The n-parameter charset used to replace chars → digit indexes
         * @param offset   t – subtracted from the numeric value to get the char code
         * @param sepIndex e – index into charset giving the separator character
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
         * Extract all embed/video URLs from the decoded JS.
         * The decoded JS contains: document.write('<script>const VIDEO_MAP_JSON={...}')
         * Due to JS escaping inside strings, the URLs have many levels of backslashes.
         */
        fun extractEmbedsFromJs(js: String): List<String> {
            val results = mutableListOf<String>()
            val seen    = mutableSetOf<String>()

            fun add(url: String) {
                val clean = url.trim('"', '\'', ' ')
                if (clean.startsWith("http") && seen.add(clean)) results.add(clean)
            }

            // Unescape the string progressively to remove all backslash escaping
            var unescaped = js
            for (i in 0..5) {
                unescaped = unescaped.replace(Regex("""\\(.)"""), "$1")
            }

            // Now the string contains clean URLs like: "https://ok.ru/videoembed/..."
            val urlRegex = Regex("""https?://[^\s"'<>&`]+""")
            urlRegex.findAll(unescaped).forEach { m ->
                // Skip the site's own wrapper player.php?url=...
                if (!m.value.contains("player.php")) {
                    add(m.value)
                }
            }

            // Extract the Dailymotion ID. In the unescaped string it looks like: "asura":""VIDEO_ID""
            val dmRegex = Regex(""""asura"\s*:\s*""?([a-zA-Z0-9_-]{5,25})""?""")
            dmRegex.find(unescaped)?.groupValues?.getOrNull(1)?.let { dmId ->
                if (!dmId.startsWith("http")) add("https://www.dailymotion.com/embed/video/$dmId")
            }

            return results
        }
    }

    // ── Main page ─────────────────────────────────────────────────────────────

    override val mainPage = mainPageOf(
        "$mainUrl/"                      to "Nuevos Episodios",
        "$mainUrl/todos-los-donghuas"    to "Todos los Donghuas",
        "$mainUrl/donghuas-en-emision"   to "En Emisión",
        "$mainUrl/donghuas-finalizados"  to "Finalizados",
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val baseUrl = request.data
        // Pagination only works reliably on the "new episodes" feed
        val url = if (page > 1 && baseUrl == "$mainUrl/")
            "$mainUrl/episodios/page/$page/"
        else if (page > 1)
            return newHomePageResponse(request.name, emptyList())
        else
            baseUrl

        val doc   = app.get(url, headers = mapOf("User-Agent" to USER_AGENT)).document
        val items = doc.select("div.item").mapNotNull { it.toSearchResult() }
        return newHomePageResponse(request.name, items, hasNext = items.isNotEmpty() && baseUrl == "$mainUrl/")
    }

    // ── Card parser ───────────────────────────────────────────────────────────
    // div.item > a.angled-img[href] > div.img > img[src]
    //                               > div.bottom-info > h5

    private fun Element.toSearchResult(): SearchResponse? {
        val anchor  = selectFirst("a.angled-img, a") ?: return null
        val urlAttr = anchor.attr("href").trim().ifBlank { return null }
        val url     = fixUrl(urlAttr)

        val title = selectFirst("h5.sf, h5, .bg-titulo, .bottom-info h5")
            ?.text()?.trim() ?: return null

        val imgEl  = selectFirst("img")
        val rawImg = imgEl?.attr("src")?.ifBlank { imgEl.attr("data-src") }
        val poster = rawImg?.let { fixUrl(it) }

        return newAnimeSearchResponse(title, url, TvType.Anime) { posterUrl = poster }
    }

    // ── Search ────────────────────────────────────────────────────────────────
    // Endpoint: /busquedas/<query> – returns series cards (div.item with series URLs)
    // NOTE: Previously the code was filtering OUT series URLs by mistake.

    override suspend fun search(query: String): List<SearchResponse> {
        val encoded = java.net.URLEncoder.encode(query.trim(), "utf-8").replace("+", "%20")
        val doc = app.get(
            "$mainUrl/busquedas/$encoded",
            headers = mapOf("User-Agent" to USER_AGENT)
        ).document

        return doc.select("div.item").mapNotNull { it.toSearchResult() }
    }

    // ── Load ──────────────────────────────────────────────────────────────────

    override suspend fun load(url: String): LoadResponse? {
        val h   = mapOf("User-Agent" to USER_AGENT)
        val res = app.get(url, headers = h)
        val doc = res.document

        // ── Episode page detection ─────────────────────────────────────────────
        if (doc.selectFirst("input#donghua_key") != null ||
            doc.selectFirst("#tamamo_player, .player-container") != null) {

            // Try to navigate to the parent series page via "list" link
            val seriesUrl = doc.selectFirst("a:has(i.fa-list)")?.absUrl("href")
                ?: doc.selectFirst(".media-bar-player a[href]:not([href*='episodio'])")?.absUrl("href")

            if (!seriesUrl.isNullOrBlank() && seriesUrl != url) {
                return load(seriesUrl)
            }

            val title  = doc.selectFirst("h3.sf, .title-serie, h3, h1")?.text()?.trim() ?: "Episodio"
            val poster = doc.selectFirst("meta[property=og:image]")?.attr("content")
            return newMovieLoadResponse(title, url, TvType.Movie, url) { posterUrl = poster }
        }

        // ── Series page ───────────────────────────────────────────────────────

        val title = doc.selectFirst(".ls-title-serie")?.text()?.trim()
            ?: doc.selectFirst("h1")?.text()?.trim()
            ?: return null

        // Poster from CSS background or og:image
        val poster = doc.selectFirst(".banner-side-serie")?.attr("style")
            ?.substringAfter("url(", "")?.substringBefore(")", "")
            ?.trim()?.let { fixUrl(it) }
            ?: doc.selectFirst("meta[property=og:image]")?.attr("content")

        val synopsis = doc.select(".text-justify p")
            .joinToString("\n") { it.text().trim() }.ifBlank { null }
        val genres = doc.select("a.generos span, a.generos").map { it.text().trim() }

        val episodes = doc.select("ul.donghua-list a").mapNotNull { anchor ->
            val epUrl  = anchor.absUrl("href").ifBlank { return@mapNotNull null }
            val epText = anchor.selectFirst("blockquote")?.text()?.trim()
                ?: anchor.text().trim()
            val epNum  = Regex("""(\d+)\s*$""").find(epText)?.groupValues?.get(1)?.toIntOrNull()
                ?: Regex("""-episodio-(\d+)""").find(epUrl)?.groupValues?.get(1)?.toIntOrNull()
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

        // ── Strategy 1: Decode eval() block (dynamic params) ──────────────────
        // Pattern: eval(function(h,u,n,t,e,r){r="";…}("ENCODED",BASE,"CHARSET",OFFSET,SEP,r))
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

        // ── Strategy 2: Raw URL patterns directly in the HTML source ──────────
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
                if (!rawUrl.contains("player.php")) {  // skip the wrapper
                    loadExtractor(rawUrl, data, subtitleCallback, callback)
                    found = true
                }
            }
        }

        // ── Strategy 3: Direct iframes in DOM ─────────────────────────────────
        if (!found) {
            doc.select("iframe[src], iframe[data-src]").forEach { iframe ->
                val src = iframe.attr("src").ifBlank { iframe.attr("data-src") }.trim()
                if (src.startsWith("http") && !src.contains("cloudflare")) {
                    loadExtractor(src, data, subtitleCallback, callback)
                    found = true
                }
            }
        }

        // ── Strategy 4: data-* attributes on player containers ────────────────
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
