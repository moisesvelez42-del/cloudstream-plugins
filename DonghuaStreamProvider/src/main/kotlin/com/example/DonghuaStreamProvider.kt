package com.example

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Element

class DonghuaStreamProvider : MainAPI() {
    override var mainUrl = "https://donghuastream.org"
    override var name = "DonghuaStream"
    override val hasMainPage = true
    override var lang = "en"
    override val supportedTypes = setOf(TvType.Anime, TvType.AnimeMovie)

    // ─── Categories ───────────────────────────────────────────────────────────
    // Pagination: /genres/<name>/pagg/<page>/
    // Home page (latest) uses page/N/ style but only works on categories.
    // For the home feed we just use / without paging (it refreshes daily).

    override val mainPage = mainPageOf(
        "$mainUrl/"                             to "Latest",
        "$mainUrl/genres/popular/pagg/"         to "Popular",
        "$mainUrl/genres/martial-arts/pagg/"    to "Martial Arts",
        "$mainUrl/genres/fantasy/pagg/"         to "Fantasy",
        "$mainUrl/genres/action/pagg/"          to "Action",
        "$mainUrl/genres/funny/pagg/"           to "Comedy",
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = when {
            request.data.endsWith("/pagg/") -> "${request.data}$page/"
            else -> request.data
        }
        val doc = app.get(url).document
        val items = doc.select("article.bs").mapNotNull { it.toSearchResult() }
        return newHomePageResponse(request.name, items, hasNext = items.isNotEmpty())
    }

    // ─── Card parser ──────────────────────────────────────────────────────────
    // HTML structure:
    //   <article class="bs">
    //     <div class="bsx">
    //       <a class="tip" href="…episode…" title="…">
    //         <img data-litespeed-src="…" />
    //       </a>
    //       <div class="tt">
    //         <h2>Title</h2>
    //       </div>
    //     </div>
    //   </article>
    //
    // NOTE: cards on the home page link to episode pages, not the anime page.
    // The anime page URL is reconstructed from the episode URL on demand (in load()).

    private fun Element.toSearchResult(): SearchResponse? {
        val anchor = selectFirst("a.tip, a[href]") ?: return null
        val href   = anchor.attr("href").trim().ifBlank { return null }
        val url    = fixUrl(href)

        // Title from .tt h2, or from the anchor title attribute, or img alt
        val title = selectFirst(".tt h2")?.text()?.trim()
            ?: anchor.attr("title").trim()
            ?: selectFirst("img")?.attr("alt")?.trim()
            ?: return null

        // Poster: site uses LiteSpeed lazy loading — real URL in data-litespeed-src
        val poster = lazyImg(selectFirst("img"))

        return newAnimeSearchResponse(title, url, TvType.Anime) {
            posterUrl = poster
        }
    }

    /** Extract the real image URL from LiteSpeed lazy-loaded or standard img elements. */
    private fun lazyImg(img: Element?): String? {
        if (img == null) return null
        return img.attr("data-litespeed-src").ifBlank {
            img.attr("data-src").ifBlank {
                img.attr("src").takeIf { !it.startsWith("data:") }
            }
        }?.let { fixUrl(it) }
    }

    // ─── Search ───────────────────────────────────────────────────────────────

    override suspend fun search(query: String): List<SearchResponse> {
        val doc = app.get("$mainUrl/?s=${query.replace(" ", "+")}").document
        return doc.select("article.bs").mapNotNull { it.toSearchResult() }
    }

    // ─── Anime / Season detail ────────────────────────────────────────────────
    // IMPORTANT: Cards link to episode pages (/some-episode/), but Cloudstream
    // calls load() on the URL returned by search/home which may be an episode URL.
    // If the URL is an anime series page (/anime/<slug>/), we parse it directly.
    // If it's an episode page, we redirect to the nearest anime page by resolving
    // it from the breadcrumb/back-link on the episode page.

    override suspend fun load(url: String): LoadResponse? {
        val doc = app.get(url).document

        // ── Is this an anime series page? ──────────────────────────────────────
        val episodeList = doc.select(".eplister li")
        if (episodeList.isNotEmpty()) {
            return parseAnimePage(url, doc)
        }

        // ── It's an episode page — find the parent anime page ─────────────────
        // The breadcrumb or a .animeinfo link points to /anime/<slug>/
        val animeHref = doc.selectFirst(
            "a[href*='/anime/'], .breadcrumbs a[href*='/anime/'], .bca a[href*='/anime/']"
        )?.attr("href")?.let { fixUrl(it) }

        if (animeHref != null) {
            val animeDoc = app.get(animeHref).document
            return parseAnimePage(animeHref, animeDoc)
        }

        // Fallback: treat the episode page as a Movie (single episode)
        val title = doc.selectFirst("h1")?.text()?.trim() ?: return null
        return newMovieLoadResponse(title, url, TvType.Movie, url) {
            posterUrl = lazyImg(doc.selectFirst(".thumbook img"))
        }
    }

    private suspend fun parseAnimePage(url: String, doc: org.jsoup.nodes.Document): LoadResponse? {
        val title  = doc.selectFirst("h1, .entry-title")?.text()?.trim() ?: return null
        val poster = lazyImg(doc.selectFirst(".thumbook img, .poster img, .thumb img"))
        val plot   = doc.selectFirst(".synp p, .entry-content p, .shortdesc")?.text()?.trim()
        val genres = doc.select(".genxed a, .spe a[href*='/genre']").map { it.text().trim() }

        val episodes = doc.select(".eplister li").mapNotNull { li ->
            val a      = li.selectFirst("a") ?: return@mapNotNull null
            val epUrl  = fixUrl(a.attr("href"))
            val epName = li.selectFirst(".epl-title")?.text()?.trim()
            // .epl-num often contains "Ep N" or episode badge text
            val epNum  = li.selectFirst(".epl-num")?.text()
                ?.let { Regex("""(\d+)""").find(it)?.groupValues?.getOrNull(1)?.toIntOrNull() }
            val epThumb = lazyImg(li.selectFirst("img"))

            newEpisode(epUrl) {
                name      = epName
                episode   = epNum
                posterUrl = epThumb
            }
        }

        return newAnimeLoadResponse(title, url, TvType.Anime) {
            posterUrl = poster
            this.plot = plot
            this.tags = genres
            // Episodes list on the page is usually newest-first; reverse for natural order.
            addEpisodes(DubStatus.Subbed, episodes.reversed())
        }
    }

    // ─── Video links ──────────────────────────────────────────────────────────
    // Episode pages embed the player inside  #pembed > iframe
    // The site uses LiteSpeed caching so the real URL is in data-litespeed-src:
    //
    //   <div id="pembed">
    //     <iframe data-litespeed-src="https://geo.dailymotion.com/player/…?video=…"
    //             src="about:blank" …></iframe>
    //   </div>
    //
    // Subtitles: managed internally by Dailymotion/AllSubPlayer.
    // Cloudstream's built-in Dailymotion extractor handles subtitle selection.

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val doc = app.get(data, referer = mainUrl).document
        var found = false

        // Primary: iframes inside the player-embed container
        doc.select("#pembed iframe, .player-embed iframe, .embed-responsive iframe, iframe").forEach { iframe ->
            val src = iframe.attr("data-litespeed-src").ifBlank {
                iframe.attr("data-src").ifBlank {
                    iframe.attr("src").takeIf { !it.startsWith("about:") && !it.startsWith("data:") }
                }
            }?.trim() ?: return@forEach

            if (src.startsWith("http")) {
                loadExtractor(src, data, subtitleCallback, callback)
                found = true
            }
        }

        return found
    }
}
