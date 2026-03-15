package com.example

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Element

/**
 * CloudStream3 extension for https://seriesdonghua.com
 *
 * Video loading strategy:
 *  - Episode pages embed ALL video server URLs inside an obfuscated eval() block.
 *  - The eval block uses a custom base-38 charset decoder (charset: "gZycHIJxT",
 *    separator char index: 6 → 'I', offset: 23).
 *  - We decode the eval block in Kotlin and extract embed URLs for each server.
 *  - Supported servers: asura (Dailymotion), skadi (Odysee), fembed (OK.ru),
 *    tape (Rumble), amagi (Voe.sx)
 */
class SeriesDonghuaProvider : MainAPI() {

    override var mainUrl  = "https://seriesdonghua.com"
    override var name     = "SeriesDonghua"
    override var lang     = "es"
    override val hasMainPage = true
    override val supportedTypes = setOf(TvType.Anime)

    companion object {
        private const val USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) " +
            "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0.0.0 Safari/537.36"

        // Charset used by the site's custom base-38/base-6 encoder
        private const val CHARSET38 = "0123456789abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ+/"
        private const val EVAL_CHARSET = "gZycHIJxT"   // n parameter
        private const val EVAL_SEPARATOR = 'I'         // n[e] where e=6  → 'I'
        private const val EVAL_OFFSET = 23             // t parameter
        private const val EVAL_BASE = 6                // e parameter (separator index = base)

        /** Base-38 decode of a single token to a decimal string (base-e=6).
         *  Equivalent to _0xe55c in the site's JS. */
        private fun decodeToken(token: String, fromBase: Int, toBase: Int): String {
            val charset = CHARSET38.toList()
            val fromAlpha = charset.take(fromBase)
            val toAlpha = charset.take(toBase)
            var value = 0L
            for ((i, ch) in token.reversed().withIndex()) {
                val idx = fromAlpha.indexOf(ch)
                if (idx >= 0) value += idx.toLong() * Math.pow(fromBase.toDouble(), i.toDouble()).toLong()
            }
            if (value == 0L) return "0"
            var result = ""
            var v = value
            while (v > 0) {
                result = toAlpha[(v % toBase).toInt()].toString() + result
                v /= toBase
            }
            return result
        }

        /**
         * Decode the custom eval block found in episode pages.
         * Implements the JS logic:
         *   for each token (split by n[e]='I'):
         *     replace n[j] chars with digit j, then decode from base-e to base-10, subtract t
         */
        fun decodeEvalBlock(encoded: String): String {
            val n = EVAL_CHARSET
            val e = EVAL_BASE         // separator index into charset n
            val t = EVAL_OFFSET
            val sepChar = n[e]        // 'I'

            val sb = StringBuilder()
            var i = 0
            while (i < encoded.length) {
                // Accumulate token until we hit the separator character
                val tokenSb = StringBuilder()
                while (i < encoded.length && encoded[i] != sepChar) {
                    tokenSb.append(encoded[i])
                    i++
                }
                i++ // skip separator

                // Replace each charset character with its index
                var s = tokenSb.toString()
                for (j in n.indices) s = s.replace(n[j].toString(), j.toString())

                // Decode from base-e (6) to base-10, then subtract offset t
                val decimal = s.toLongOrNull() ?: 0L
                val charCode = decimal - t
                if (charCode > 0) sb.append(charCode.toInt().toChar())
            }
            return try {
                String(sb.toString().toByteArray(Charsets.ISO_8859_1), Charsets.UTF_8)
            } catch (_: Exception) {
                sb.toString()
            }
        }
    }

    // ── Main page ─────────────────────────────────────────────────────────────

    override val mainPage = mainPageOf(
        "$mainUrl/"                    to "Nuevos Episodios",
        "$mainUrl/todos-los-donghuas"  to "Todos los Donghuas",
        "$mainUrl/donghuas-en-emision" to "En Emisión",
        "$mainUrl/donghuas-finalizados" to "Finalizados",
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = when {
            request.data.endsWith("/page/") -> "${request.data}$page/"
            page > 1 -> "${request.data}?pagina=$page"
            else -> request.data
        }
        val doc = app.get(url, headers = mapOf("User-Agent" to USER_AGENT)).document
        val items = doc.select("div.item").mapNotNull { it.toSearchResult() }
        return newHomePageResponse(request.name, items, hasNext = items.isNotEmpty())
    }

    // ── Helper: parse a card element into SearchResponse ─────────────────────

    private fun Element.toSearchResult(): SearchResponse? {
        val anchor = selectFirst("a.angled-img") ?: selectFirst("a") ?: return null
        val url    = anchor.attr("href").ifBlank { return null }
        val title  = selectFirst("h5.sf, h5, .bg-titulo")?.text()?.trim() ?: return null
        val poster = selectFirst("img")?.let {
            it.attr("src").ifBlank { it.attr("data-src") }
        }?.trim()
        return newAnimeSearchResponse(title, url, TvType.Anime) { this.posterUrl = poster }
    }

    // ── Search ────────────────────────────────────────────────────────────────

    override suspend fun search(query: String): List<SearchResponse> {
        val doc = app.get(
            "$mainUrl/busquedas/${query.encodeUri()}",
            headers = mapOf("User-Agent" to USER_AGENT)
        ).document
        return doc.select("div.item").mapNotNull {
            val anchor = it.selectFirst("a.angled-img") ?: return@mapNotNull null
            val href   = anchor.attr("href").ifBlank { return@mapNotNull null }
            // On search results the items link to series pages (not episode pages)
            if (href.contains("-episodio-", ignoreCase = true)) return@mapNotNull null
            it.toSearchResult()
        }
    }

    // ── Series / Detail page ──────────────────────────────────────────────────

    override suspend fun load(url: String): LoadResponse? {
        val doc = app.get(url, headers = mapOf("User-Agent" to USER_AGENT)).document

        // Episode pages have #donghua_key hidden input; series pages have .ls-title-serie
        val isEpisodePage = doc.selectFirst("input#donghua_key") != null

        if (isEpisodePage) {
            val title  = doc.selectFirst("h3.sf, .title-serie")?.text()?.trim() ?: "Episodio"
            val poster = doc.selectFirst("meta[property=og:image]")?.attr("content")
            return newMovieLoadResponse(title, url, TvType.Movie, url) {
                this.posterUrl = poster
            }
        }

        // ── Series page ──────────────────────────────────────────────────────

        val title = doc.selectFirst(".ls-title-serie")?.text()?.trim()
            ?: doc.selectFirst("h1")?.text()?.trim()
            ?: return null

        // Poster: background-image on .banner-side-serie style="background-image: url(/…)"
        val poster = doc.selectFirst(".banner-side-serie")?.attr("style")
            ?.substringAfter("url(")?.substringBefore(")")
            ?.trim()?.let { if (it.startsWith("http")) it else "$mainUrl${it.trimStart('/')}" }
            ?: doc.selectFirst("meta[property=og:image]")?.attr("content")

        val synopsis = doc.select(".text-justify p")
            .joinToString("\n") { it.text().trim() }
            .ifBlank { null }

        val genres = doc.select("a.generos span, a.generos").map { it.text().trim() }

        // Episodes in <ul class="donghua-list"><a href="..."><li>...</li></a></ul>
        val episodes = doc.select("ul.donghua-list a").mapNotNull { anchor ->
            val epUrl  = anchor.attr("href").ifBlank { return@mapNotNull null }
            val epText = anchor.selectFirst("blockquote")?.text()?.trim()
                ?: anchor.text().trim()
            val epNum  = epText.substringAfterLast("-").trim().toIntOrNull()
                ?: Regex("""-episodio-(\d+)""").find(epUrl)?.groupValues?.get(1)?.toIntOrNull()
            newEpisode(epUrl) { this.name = epText; this.episode = epNum; this.season = 1 }
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
            "User-Agent" to USER_AGENT,
            "Referer"    to mainUrl,
            "Accept"     to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
            "Accept-Language" to "es-ES,es;q=0.9",
        )
        // Request page. We don't strictly need interceptors if we can regex the HTML.
        val response = app.get(data, headers = headers)
        val html     = response.text
        val doc      = response.document

        var found = false

        // ── Strategy 1: Regex scan of raw HTML for known embed patterns ──────
        // Even if obfuscated, the domains are often visible in the raw source
        // or the raw source contains the IDs.
        val embedPatterns = listOf(
            Regex("""geo\.dailymotion\.com/player\.html\?video=([A-Za-z0-9_-]+)"""),
            Regex("""dailymotion\.com/embed/video/([A-Za-z0-9_-]+)"""),
            Regex("""ok\.ru/videoembed/(\d+)"""),
            Regex("""rumble\.com/embed/([A-Za-z0-9]+)"""),
            Regex("""voe\.sx/e/([A-Za-z0-9]+)"""),
            Regex("""odysee\.com/\$/embed/[^\s"'<>&]+"""),
        )
        for (pattern in embedPatterns) {
            val matches = pattern.findAll(html)
            for (match in matches) {
                var rawUrl = match.value.let { if (it.startsWith("http")) it else "https://$it" }
                // Clean up escaped slashes if they exist
                rawUrl = rawUrl.replace("\\/", "/")
                
                // Some extractors need the exact URL format
                val embedUrl = when {
                    rawUrl.contains("geo.dailymotion") -> rawUrl
                    rawUrl.contains("dailymotion.com/embed") -> rawUrl
                    rawUrl.contains("ok.ru/videoembed") -> rawUrl
                    rawUrl.contains("rumble.com/embed") -> rawUrl
                    rawUrl.contains("voe.sx/e/") -> rawUrl
                    else -> rawUrl
                }
                
                loadExtractor(embedUrl, data, subtitleCallback, callback)
                found = true
            }
        }

        // ── Strategy 2: Decode the eval() block embedded in the page (if present)
        // The site embeds ALL video server URLs inside an obfuscated eval:
        // eval(function(h,u,n,t,e,r){...}("encoded",38,"gZycHIJxT",23,6,37))
        if (!found) {
            val evalEncodedRegex = Regex(
                """["']([A-Za-z0-9J]+(?:H|g|Z|y|c|I|x|T)+[A-Za-z0-9J]+)["'],38,["']gZycHIJxT["'],23,6,37"""
            )
            val evalMatch = evalEncodedRegex.find(html) ?: Regex("""\("([^"]+)",38,"gZycHIJxT",23,6,37\)""").find(html)
            
            if (evalMatch != null) {
                val encoded = evalMatch.groupValues[1]
                val decoded = try { decodeEvalBlock(encoded) } catch (_: Exception) { "" }

                if (decoded.isNotBlank()) {
                    val embedUrls = extractEmbedsFromDecodedJs(decoded)
                    embedUrls.forEach { embedUrl ->
                        loadExtractor(embedUrl, data, subtitleCallback, callback)
                        found = true
                    }
                }
            }
        }

        // ── Strategy 3: Direct iframe src attributes in the DOM ──────────────
        // If the server delivered the page with pre-rendered iframes (SSR)
        if (!found) {
            doc.select("iframe[src]").forEach { iframe ->
                val src = iframe.attr("src").trim()
                if (src.startsWith("http") && !src.contains("cloudflare")) {
                    loadExtractor(src, data, subtitleCallback, callback)
                    found = true
                }
            }
        }

        return found
    }

    // ── Helper: extract all embed URLs from the decoded JS block ─────────────

    private fun extractEmbedsFromDecodedJs(js: String): List<String> {
        val results = mutableListOf<String>()

        // Dailymotion embed: geo.dailymotion.com/player.html?video=ID
        Regex("""geo\.dailymotion\.com/player\.html\?video=([A-Za-z0-9_-]+)""")
            .findAll(js).forEach { results.add("https://${it.value}") }

        // Also plain dailymotion embed link
        Regex("""dailymotion\.com/embed/video/([A-Za-z0-9_-]+)""")
            .findAll(js).forEach { results.add("https://www.${it.value}") }

        // OK.ru embed
        Regex("""ok\.ru/videoembed/(\d+)""")
            .findAll(js).forEach { results.add("https://${it.value}") }

        // Rumble embed: rumble.com/embed/ID/
        Regex("""rumble\.com/embed/([A-Za-z0-9]+)(?:/[^\s"'<>]*)?""")
            .findAll(js).forEach { results.add("https://${it.value}") }

        // Voe.sx embed: voe.sx/e/ID
        Regex("""voe\.sx/e/([A-Za-z0-9]+)""")
            .findAll(js).forEach { results.add("https://${it.value}") }

        // Odysee embed: odysee.com/$/embed/...
        Regex("""odysee\.com/\\\$[^\s"'<>]+""")
            .findAll(js).forEach {
                results.add("https://${it.value.replace("\\$", "\$").replace("\\/", "/")}")
            }
        Regex("""odysee\.com/\$[^\s"'<>]+""")
            .findAll(js).forEach { results.add("https://${it.value}") }

        return results.distinct()
    }

    // ── Helper ────────────────────────────────────────────────────────────────

    private fun String.encodeUri(): String =
        java.net.URLEncoder.encode(this, "UTF-8")
}
