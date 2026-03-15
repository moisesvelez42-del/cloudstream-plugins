package com.example

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Element

/**
 * CloudStream3 extension for https://seriesdonghua.com
 *
 * Structure:
 *  - Homepage:  Episode cards with  .item  selector
 *  - Search:    /busquedas/{query}  → same  .item  cards
 *  - Series:    .ls-title-serie (title), .banner-side-serie (poster),
 *               .text-justify p (synopsis), a.generos (genres),
 *               ul.donghua-list a (episode links)
 *  - Episode:   hidden inputs #donghua_key + #episode,
 *               tabs ul.nav-tabs li[id$=_tab] → platform IDs
 *               video sources fetched via /api/{platform}/{key}/{ep}
 *
 * Supported video servers: asura (Dailymotion), skadi (Odysee),
 *   fembed, tape (Rumble), amagi (Voe.sx)
 */
class SeriesDonghuaProvider : MainAPI() {

    override var mainUrl  = "https://seriesdonghua.com"
    override var name     = "SeriesDonghua"
    override var lang     = "es"
    override val hasMainPage = true
    override val supportedTypes = setOf(TvType.Anime)

    companion object {
        private val USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) " +
                "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0.0.0 Safari/537.36"
        /** Platform IDs used by the site in data-platform attributes */
        private val PLATFORMS = listOf("asura", "skadi", "fembed", "tape", "amagi")
    }

    // ── Main page ─────────────────────────────────────────────────────────────

    override val mainPage = mainPageOf(
        "$mainUrl/"                   to "Nuevos Episodios",
        "$mainUrl/todos-los-donghuas" to "Todos los Donghuas",
        "$mainUrl/donghuas-en-emision" to "En Emisión",
        "$mainUrl/donghuas-finalizados" to "Finalizados",
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = when {
            request.data.endsWith("/page/") -> "${request.data}$page/"
            page > 1 && request.data.contains("/todos-los-donghuas") -> "${request.data}?pagina=$page"
            else -> request.data
        }
        val doc = app.get(url, headers = mapOf("User-Agent" to USER_AGENT)).document
        val items = doc.select("div.item, .item").mapNotNull { it.toSearchResult() }
        return newHomePageResponse(request.name, items, hasNext = items.isNotEmpty())
    }

    // ── Helper to parse an .item card into a SearchResponse ───────────────────

    private fun Element.toSearchResult(): SearchResponse? {
        // Each .item contains an <a class="angled-img"> wrapping the thumbnail + title
        val anchor = selectFirst("a.angled-img, a") ?: return null
        val url    = anchor.attr("href").ifBlank { return null }

        // Filter out episode links → only series detail pages (no "episodio" in path)
        // On the main page cards ARE episode links; we keep them so the user can see recent eps
        val title = selectFirst("h5.sf")?.text()?.trim()
            ?: selectFirst("h5")?.text()?.trim()
            ?: selectFirst(".bg-titulo")?.text()?.trim()
            ?: return null

        val poster = selectFirst("img")?.let {
            it.attr("src").ifBlank { it.attr("data-src") }
        }?.trim()

        return newAnimeSearchResponse(title, url, TvType.Anime) {
            this.posterUrl = poster
        }
    }

    // ── Search ────────────────────────────────────────────────────────────────

    override suspend fun search(query: String): List<SearchResponse> {
        val doc = app.get(
            "$mainUrl/busquedas/${query.encodeUri()}",
            headers = mapOf("User-Agent" to USER_AGENT)
        ).document

        // Search results appear as .item cards that link directly to series pages
        return doc.select("div.item, .item").mapNotNull { it.toSearchResultSeries() }
    }

    /**
     * Variant of toSearchResult that only accepts links pointing to series detail
     * pages (no "episodio" in path, no leading episode-like URLs).
     */
    private fun Element.toSearchResultSeries(): SearchResponse? {
        val anchor = selectFirst("a.angled-img, a") ?: return null
        val url    = anchor.attr("href").ifBlank { return null }
        // Accept only series URLs (they do NOT have "episodio" in the path)
        if (url.contains("-episodio-", ignoreCase = true)) return null

        val title = selectFirst("h5.sf, h5, .bg-titulo")?.text()?.trim() ?: return null
        val poster = selectFirst("img")?.let {
            it.attr("src").ifBlank { it.attr("data-src") }
        }?.trim()

        return newAnimeSearchResponse(title, url, TvType.Anime) {
            this.posterUrl = poster
        }
    }

    // ── Series / Episode detail ───────────────────────────────────────────────

    override suspend fun load(url: String): LoadResponse? {
        val doc = app.get(url, headers = mapOf("User-Agent" to USER_AGENT)).document

        // ── Determine content type:
        // Episode pages have a #donghua_key hidden input; series pages have .ls-title-serie
        val isEpisode = doc.selectFirst("input#donghua_key") != null

        if (isEpisode) {
            // This shouldn't normally happen via normal navigation, but guard anyway.
            // Return a fake "movie" so the user can play it directly.
            val title  = doc.selectFirst("h3.sf")?.text()?.trim() ?: "Episodio"
            val poster = doc.selectFirst(".banner-serie")?.attr("style")
                ?.substringAfter("url(")?.substringBefore(")")?.trim()
            return newMovieLoadResponse(title, url, TvType.Movie, url) {
                this.posterUrl = poster
            }
        }

        // ── Series page ──────────────────────────────────────────────────────

        val title  = doc.selectFirst(".ls-title-serie")?.text()?.trim()
            ?: doc.selectFirst("h1")?.text()?.trim()
            ?: return null

        // Poster: background-image on .banner-side-serie   style="background-image: url(/…)"
        val poster = doc.selectFirst(".banner-side-serie")?.attr("style")
            ?.substringAfter("url(")?.substringBefore(")")?.trimStart('/')
            ?.let { if (it.startsWith("http")) it else "$mainUrl/$it" }
            ?: doc.selectFirst("meta[property=og:image]")?.attr("content")

        val synopsis = doc.select(".text-justify p")
            .joinToString("\n") { it.text() }
            .trim()
            .ifBlank { null }

        val genres = doc.select("a.generos span, a.generos").map { it.text().trim() }

        // ── Episodes ─────────────────────────────────────────────────────────
        // <ul class="donghua-list">
        //   <a href="https://seriesdonghua.com/slug-episodio-13/">
        //     <li …><blockquote …>Series Name - 13</blockquote></li>
        //   </a>
        //   …
        val episodes = doc.select("ul.donghua-list a").mapIndexedNotNull { _, anchor ->
            val epUrl  = anchor.attr("href").ifBlank { return@mapIndexedNotNull null }
            val epText = anchor.selectFirst("blockquote")?.text()?.trim()
                ?: anchor.text().trim()

            // Extract episode number from text "Name - 13" or from URL "-episodio-13"
            val epNum = epText.substringAfterLast("-").trim().toIntOrNull()
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
            "User-Agent" to USER_AGENT,
            "Referer"    to mainUrl,
        )
        val doc = app.get(data, headers = headers).document

        var found = false

        // ── Extract donghua_key and episode number from hidden inputs ──────────
        val donghuaKey = doc.selectFirst("input#donghua_key")?.attr("value")?.trim()
        val episodeNum = doc.selectFirst("input#episode")?.attr("value")?.trim()

        // ── Strategy 1: Internal API for each platform tab ────────────────────
        //
        // The site's obfuscated JS calls an endpoint like:
        //   GET $mainUrl/api/{platform}/{donghua_key}/{episode}
        // Response is a redirect or JSON with the embed URL.
        //
        if (!donghuaKey.isNullOrBlank() && !episodeNum.isNullOrBlank()) {
            val platforms = doc.select("ul.nav-tabs li[id]")
                .mapNotNull { li ->
                    // id is like "asura_tab", "skadi_tab", etc.
                    li.id().removeSuffix("_tab").ifBlank { null }
                }
                .ifEmpty { PLATFORMS }

            for (platform in platforms) {
                val apiUrl = "$mainUrl/api/$platform/$donghuaKey/$episodeNum"
                try {
                    val resp = app.get(
                        apiUrl,
                        headers = headers + mapOf("X-Requested-With" to "XMLHttpRequest"),
                        allowRedirects = false,
                    )
                    // If it returns a redirect to the embed URL
                    val location = resp.headers["Location"] ?: resp.headers["location"]
                    if (!location.isNullOrBlank() && location.startsWith("http")) {
                        loadExtractor(location, data, subtitleCallback, callback)
                        found = true
                        continue
                    }
                    // If it returns JSON or HTML with an iframe/video URL
                    val body = resp.text
                    val embedUrl = extractEmbedFromApiResponse(body)
                    if (!embedUrl.isNullOrBlank()) {
                        loadExtractor(embedUrl, data, subtitleCallback, callback)
                        found = true
                    }
                } catch (_: Exception) { /* platform not available, skip */ }
            }
        }

        // ── Strategy 2: Parse iframes already injected in the DOM ────────────
        // The first tab (asura / Daily) often has its iframe loaded directly.
        if (!found || donghuaKey.isNullOrBlank()) {
            doc.select("iframe[src]").forEach { iframe ->
                val src = iframe.attr("src").trim()
                if (src.startsWith("http")) {
                    loadExtractor(src, data, subtitleCallback, callback)
                    found = true
                }
            }
        }

        // ── Strategy 3: Regex scan of page source for video URLs ─────────────
        if (!found) {
            val src = doc.html()
            val patterns = listOf(
                Regex("""https?://[^\s"'<>]+\.m3u8"""),
                Regex("""https?://[^\s"'<>]+\.mp4"""),
                Regex("""embed\.dailymotion\.com/video/[^\s"'<>&]+"""),
                Regex("""rumble\.com/embed/[^\s"'<>&]+"""),
                Regex("""voe\.sx/e/[^\s"'<>&]+"""),
            )
            for (pattern in patterns) {
                pattern.findAll(src).forEach { match ->
                    val url2 = match.value.let { if (it.startsWith("http")) it else "https://$it" }
                    loadExtractor(url2, data, subtitleCallback, callback)
                    found = true
                }
            }
        }

        return found
    }

    // ── Helper: extract embed URL from an API response body ──────────────────

    private fun extractEmbedFromApiResponse(body: String): String? {
        if (body.isBlank()) return null

        // JSON pattern: {"url":"https://..."}  or  {"embed":"..."}
        val urlPat = Regex(""""(?:url|embed|src|iframe)"\s*:\s*"([^"]+)"""")
        urlPat.find(body)?.groupValues?.getOrNull(1)
            ?.replace("\\/", "/")
            ?.trim()
            ?.takeIf { it.startsWith("http") }
            ?.let { return it }

        // iframe pattern: <iframe src="https://...">
        val iframePat = Regex("""<iframe[^>]+src=["']([^"']+)["']""", RegexOption.IGNORE_CASE)
        iframePat.find(body)?.groupValues?.getOrNull(1)
            ?.trim()
            ?.takeIf { it.startsWith("http") }
            ?.let { return it }

        // Bare URL on its own line
        if (body.trim().startsWith("http")) return body.trim()

        return null
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun String.encodeUri(): String =
        java.net.URLEncoder.encode(this, "UTF-8")
}
