package com.example

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Element

/**
 * CloudStream3 extension for https://seriesdonghua.com
 *
 * --- Video loading strategy ---
 * Episode pages embed ALL video-server URLs inside an obfuscated eval() block.
 * The block uses the site's own base-N decoder (_0xe37c) with:
 *   h = encoded string
 *   u = base (26)
 *   n = charset "ZnqBsSzeV"
 *   t = offset (11)
 *   e = separator index (2 → 'n')
 *   r = max (36, unused)
 *
 * We re-implement that decoder in Kotlin to extract all embed URLs.
 */
class SeriesDonghuaProvider : MainAPI() {

    override var mainUrl  = "https://seriesdonghua.com"
    override var name     = "SeriesDonghua"
    override var lang     = "es"
    override var lang     = 1
    override val hasMainPage = true
    override val supportedTypes = setOf(TvType.Anime)

    companion object {
        private const val USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) " +
            "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0.0.0 Safari/537.36"

        // Charset used by the site's base-64 encoder (_0xe37c uses the full charset for lookup)
        private const val BASE64_CHARSET =
            "0123456789abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ+/"

        /**
         * Converts a number encoded in [fromBase] to a decimal Long.
         * Python equivalent: int(token, fromBase) but using BASE64_CHARSET alphabet.
         */
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
         *
         * The JS call looks like:
         *   eval(function(h,u,n,t,e,r){ ... }("ENCODED",26,"ZnqBsSzeV",11,2,36))
         *
         * Algorithm (from the inline JS decompiler):
         *   For each token (split on n[e] = charset[sep]):
         *     Replace each char in n with its index digit(s)
         *     Convert the resulting number from base-e to base-10
         *     Subtract t → char code
         *     Append the character
         *   Return decodeURIComponent(escape(result))
         *
         * @param encoded  The obfuscated string (h)
         * @param base     The numeric base used for lookups in BASE64_CHARSET (u) – 26
         * @param charset  The n-parameter charset string – "ZnqBsSzeV"
         * @param offset   t parameter – 11
         * @param sepIndex e parameter (index into charset for separator) – 2
         */
        fun decodeEvalBlock(
            encoded: String,
            base: Int     = 26,
            charset: String = "ZnqBsSzeV",
            offset: Int   = 11,
            sepIndex: Int = 2,
        ): String {
            val sepChar = charset[sepIndex]
            val sb = StringBuilder()
            var i = 0
            while (i < encoded.length) {
                val tokenSb = StringBuilder()
                while (i < encoded.length && encoded[i] != sepChar) {
                    tokenSb.append(encoded[i])
                    i++
                }
                i++ // skip separator

                // Replace each charset char with its index
                var s = tokenSb.toString()
                for (j in charset.indices) {
                    s = s.replace(charset[j].toString(), j.toString())
                }

                // s is now a string of digits in base-`sepIndex`
                // (e.g. binary "10110" when sepIndex=2, octal when sepIndex=8, etc.)
                // _0xe37c(s, e, 10) converts from base-e to base-10 using BASE64_CHARSET.
                // Since after substitution the digits are 0...(charset.size-1) and
                // BASE64_CHARSET starts with "0123456789..." this is just a standard
                // base conversion with `s` interpreted as a base-`sepIndex` number.
                val decimal = fromCustomBase(s, sepIndex)
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
         * Parse the decoded JS to extract all embed/video URLs.
         * Looks for all known video hosting domains.
         */
        fun extractEmbedsFromJs(js: String): List<String> {
            val results = mutableListOf<String>()
            val seen = mutableSetOf<String>()

            fun add(url: String) {
                val clean = url.replace("\\/", "/").trim('"', '\'', ' ')
                if (clean.startsWith("http") && seen.add(clean)) results.add(clean)
            }

            // Generic URL extractor for https:// patterns inside strings
            val urlRegex = Regex("""https?://[^\s"'<>&\\]+""")
            urlRegex.findAll(js).forEach { add(it.value) }

            // Also catch escaped https (https:\/\/)
            val escapedUrlRegex = Regex("""https?:\\/\\/[^\s"'<>&]+""")
            escapedUrlRegex.findAll(js).forEach { add(it.value) }

            return results.filter { url ->
                // Only keep known embed domains
                listOf(
                    "dailymotion.com",
                    "ok.ru",
                    "rumble.com",
                    "voe.sx",
                    "odysee.com",
                    "streamsb", "strsb", "sbplay",
                    "filemoon", "moon",
                    "streamwish", "wishembed",
                    "doodstream", "dood.",
                    "upstream",
                    "streamlare",
                    "mixdrop",
                ).any { domain -> url.contains(domain, ignoreCase = true) }
            }.distinct()
        }
    }

    // ── Main page ─────────────────────────────────────────────────────────────

    override val mainPage = mainPageOf(
        "$mainUrl/"                     to "Nuevos Episodios",
        "$mainUrl/todos-los-donghuas"   to "Todos los Donghuas",
        "$mainUrl/donghuas-en-emision"  to "En Emisión",
        "$mainUrl/donghuas-finalizados" to "Finalizados",
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val baseUrl = request.data
        val url = when {
            // Paginate the main page listing
            page > 1 && baseUrl == "$mainUrl/" -> "$mainUrl/episodios/page/$page/"
            page > 1                            -> "$baseUrl/page/$page/"
            else                                -> baseUrl
        }
        val doc = app.get(url, headers = mapOf("User-Agent" to USER_AGENT)).document
        val items = doc.select("div.item").mapNotNull { it.toSearchResult() }
        return newHomePageResponse(request.name, items, hasNext = items.isNotEmpty())
    }

    // ── Helper: parse a card element into SearchResponse ─────────────────────

    private fun Element.toSearchResult(): SearchResponse? {
        val anchor = selectFirst("a.angled-img") ?: selectFirst("a") ?: return null
        val url    = anchor.absUrl("href").ifBlank { return null }
        val title  = selectFirst("h5.sf, h5, .bg-titulo, .bottom-info h5")
            ?.text()?.trim() ?: return null
        val poster = selectFirst("img")?.let {
            it.absUrl("src").ifBlank { it.absUrl("data-src") }
        }?.trim()
        return newAnimeSearchResponse(title, url, TvType.Anime) { this.posterUrl = poster }
    }

    // ── Search ────────────────────────────────────────────────────────────────

    override suspend fun search(query: String): List<SearchResponse> {
        // The site's own JS uses: location.href = "/busquedas/" + name
        val doc = app.get(
            "$mainUrl/busquedas/$query",
            headers = mapOf("User-Agent" to USER_AGENT)
        ).document

        return doc.select("div.item").mapNotNull { el ->
            val anchor = el.selectFirst("a.angled-img") ?: return@mapNotNull null
            val href   = anchor.absUrl("href").ifBlank { return@mapNotNull null }
            // Skip individual episode pages from search results
            if (Regex("""-episodio-\d""").containsMatchIn(href)) return@mapNotNull null
            el.toSearchResult()
        }
    }

    // ── Load (Series / Episode detail page) ──────────────────────────────────

    override suspend fun load(url: String): LoadResponse? {
        val res = app.get(url, headers = mapOf("User-Agent" to USER_AGENT))
        val doc = res.document

        // Detect episode page by the hidden input
        if (doc.selectFirst("input#donghua_key") != null) {
            // For episode pages: try to navigate to the series page for full episode list
            // The "List" button in the media-bar takes us there
            val seriesUrl = doc.selectFirst("a[href*='/']:has(i.fa-list)")?.absUrl("href")
                ?: doc.selectFirst(".media-bar-player a[href]:not([href*='episodio'])")?.absUrl("href")

            if (!seriesUrl.isNullOrBlank() && seriesUrl != url) {
                // Recursively load the series page
                return load(seriesUrl)
            }

            // Fallback: return a movie-type response for standalone episode playback
            val title  = doc.selectFirst("h3.sf, .title-serie, h3")?.text()?.trim() ?: "Episodio"
            val poster = doc.selectFirst("meta[property=og:image]")?.attr("content")
            return newMovieLoadResponse(title, url, TvType.Movie, url) {
                this.posterUrl = poster
            }
        }

        // ── Series page ──────────────────────────────────────────────────────

        val title = doc.selectFirst(".ls-title-serie")?.text()?.trim()
            ?: doc.selectFirst("h1")?.text()?.trim()
            ?: return null

        // Poster from background-image on .banner-side-serie
        val poster = doc.selectFirst(".banner-side-serie")?.attr("style")
            ?.substringAfter("url(")?.substringBefore(")")
            ?.trim()?.let { if (it.startsWith("http")) it else "$mainUrl/${it.trimStart('/')}" }
            ?: doc.selectFirst("meta[property=og:image]")?.attr("content")

        val synopsis = doc.select(".text-justify p")
            .joinToString("\n") { it.text().trim() }
            .ifBlank { null }

        val genres = doc.select("a.generos span, a.generos").map { it.text().trim() }

        // Episodes inside <ul class="donghua-list"><a href="..."><li>...</li></a></ul>
        val episodes = doc.select("ul.donghua-list a").mapNotNull { anchor ->
            val epUrl  = anchor.absUrl("href").ifBlank { return@mapNotNull null }
            val epText = anchor.selectFirst("blockquote")?.text()?.trim()
                ?: anchor.text().trim()
            val epNum  = Regex("""-(\d+)\s*$""").find(epText)?.groupValues?.get(1)?.toIntOrNull()
                ?: Regex("""-episodio-(\d+)""").find(epUrl)?.groupValues?.get(1)?.toIntOrNull()
            newEpisode(epUrl) {
                this.name    = epText
                this.episode = epNum
                this.season  = 1
            }
        }

        return newAnimeLoadResponse(title, url, TvType.Anime) {
            this.posterUrl = poster
            this.plot      = synopsis
            this.tags      = genres
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
            "Accept"          to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
            "Accept-Language" to "es-ES,es;q=0.9",
        )
        val response = app.get(data, headers = headers)
        val html     = response.text
        val doc      = response.document

        var found = false

        // ── Strategy 1: Decode the eval() block inside the page ──────────────
        // Pattern: eval(function(h,u,n,t,e,r){...}("ENCODED",26,"ZnqBsSzeV",11,2,36))
        val evalRegex = Regex(
            // Match the big encoded string followed by the known parameters
            """eval\(function\(h,u,n,t,e,r\)\{[^}]+\}\("([^"]+)",(\d+),"([^"]+)",(\d+),(\d+),\d+\)\)"""
        )
        val evalMatch = evalRegex.find(html)
        if (evalMatch != null) {
            val encoded   = evalMatch.groupValues[1]
            val base      = evalMatch.groupValues[2].toIntOrNull() ?: 26
            val charset   = evalMatch.groupValues[3]
            val offsetVal = evalMatch.groupValues[4].toIntOrNull() ?: 11
            val sepIdx    = evalMatch.groupValues[5].toIntOrNull() ?: 2

            val decoded = try {
                decodeEvalBlock(encoded, base, charset, offsetVal, sepIdx)
            } catch (_: Exception) { "" }

            if (decoded.isNotBlank()) {
                val embeds = extractEmbedsFromJs(decoded)
                embeds.forEach { embedUrl ->
                    loadExtractor(embedUrl, data, subtitleCallback, callback)
                    found = true
                }
            }
        }

        // ── Strategy 2: Regex scan of raw HTML for known embed patterns ──────
        // Even without decode success, try common URL patterns directly in HTML source
        val rawEmbedPatterns = listOf(
            Regex("""https?://geo\.dailymotion\.com/player\.html\?video=[A-Za-z0-9_-]+"""),
            Regex("""https?://www\.dailymotion\.com/embed/video/[A-Za-z0-9_-]+"""),
            Regex("""https?://ok\.ru/videoembed/\d+"""),
            Regex("""https?://rumble\.com/embed/[A-Za-z0-9]+(?:/[^\s"'<>&]*)?"""),
            Regex("""https?://voe\.sx/e/[A-Za-z0-9]+"""),
            Regex("""https?://odysee\.com/\$/embed/[^\s"'<>&]+"""),
            Regex("""https?://[a-z0-9-]+\.filemoon\.[a-z]+/e/[A-Za-z0-9]+"""),
            Regex("""https?://[a-z0-9-]+\.streamwish\.[a-z]+/e/[A-Za-z0-9]+"""),
            Regex("""https?://dood\.[a-z]+/[de]/[A-Za-z0-9]+"""),
        )
        for (pattern in rawEmbedPatterns) {
            pattern.findAll(html).forEach { match ->
                val rawUrl = match.value.replace("\\/", "/")
                loadExtractor(rawUrl, data, subtitleCallback, callback)
                found = true
            }
        }

        // ── Strategy 3: Direct iframes already in DOM ────────────────────────
        if (!found) {
            doc.select("iframe[src]").forEach { iframe ->
                val src = iframe.attr("src").trim()
                if (src.startsWith("http") && !src.contains("cloudflare")) {
                    loadExtractor(src, data, subtitleCallback, callback)
                    found = true
                }
            }
        }

        // ── Strategy 4: data-* attributes on player container divs ───────────
        if (!found) {
            doc.select("[data-video],[data-player],[data-src],[data-embed],[data-stream]").forEach { el ->
                listOf("data-video", "data-player", "data-src", "data-embed", "data-stream").forEach { attr ->
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
