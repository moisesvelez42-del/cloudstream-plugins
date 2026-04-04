package com.example

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import org.jsoup.nodes.Element

class DonghuaStreamProvider : MainAPI() {
    override var mainUrl = "https://donghuastream.org"
    override var name = "DonghuaStream"
    override val hasMainPage = true
    override var lang = "en"
    override val supportedTypes = setOf(TvType.Anime, TvType.AnimeMovie)

    // ─── Categories ───────────────────────────────────────────────────────────

    override val mainPage = mainPageOf(
        "$mainUrl/"                          to "Latest",
        "$mainUrl/genres/popular/pagg/"      to "Popular",
        "$mainUrl/genres/martial-arts/pagg/" to "Martial Arts",
        "$mainUrl/genres/fantasy/pagg/"      to "Fantasy",
        "$mainUrl/genres/action/pagg/"       to "Action",
        "$mainUrl/genres/funny/pagg/"        to "Comedy",
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = if (request.data.endsWith("/pagg/"))
            "${request.data}$page/"
        else
            request.data
        val doc = app.get(url).document
        val items = doc.select("article.bs").mapNotNull { it.toSearchResult() }
        return newHomePageResponse(request.name, items, hasNext = items.isNotEmpty())
    }

    // ─── Card parser ──────────────────────────────────────────────────────────
    // article.bs > div.bsx > a.tip[href, title] > img[data-litespeed-src]
    //                      > div.tt > h2

    private fun Element.toSearchResult(): SearchResponse? {
        val anchor = selectFirst("a.tip, a[href]") ?: return null
        val href   = anchor.attr("href").trim().ifBlank { return null }
        val url    = fixUrl(href)

        val title = selectFirst(".tt h2")?.text()?.trim()
            ?: anchor.attr("title").trim().ifBlank { null }
            ?: selectFirst("img")?.attr("alt")?.trim()
            ?: return null

        val poster = lazyImg(selectFirst("img"))

        return newAnimeSearchResponse(title, url, TvType.Anime) {
            posterUrl = poster
        }
    }

    /** Resolve LiteSpeed lazy-loaded image (data-litespeed-src > data-src > src). */
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

    // ─── Load ─────────────────────────────────────────────────────────────────
    // Cards on the home/search page link to EPISODE pages, not anime pages.
    // If the fetched URL is an episode page, navigate to the parent anime page
    // via the breadcrumb back-link (a[href*='/anime/']).

    override suspend fun load(url: String): LoadResponse? {
        val doc = app.get(url).document

        if (doc.select(".eplister li").isNotEmpty()) {
            return parseAnimePage(url, doc)
        }

        // Episode page → resolve parent series
        val animeHref = doc
            .selectFirst("a[href*='/anime/'], .bcitem a, .breadcrumbs a[href*='/anime/']")
            ?.attr("href")?.let { fixUrl(it) }

        if (animeHref != null) {
            return parseAnimePage(animeHref, app.get(animeHref).document)
        }

        // Fallback: single episode as movie
        val title = doc.selectFirst("h1")?.text()?.trim() ?: return null
        return newMovieLoadResponse(title, url, TvType.Movie, url) {
            posterUrl = lazyImg(doc.selectFirst(".thumbook img, .poster img"))
        }
    }

    private suspend fun parseAnimePage(
        url: String,
        doc: org.jsoup.nodes.Document
    ): LoadResponse? {
        val title  = doc.selectFirst("h1, .entry-title")?.text()?.trim() ?: return null
        val poster = lazyImg(doc.selectFirst(".thumbook img, .poster img, .thumb img"))
        val plot   = doc.selectFirst(".synp p, .entry-content p, .shortdesc")?.text()?.trim()
        val genres = doc.select(".genxed a, .spe a[href*='/genre']").map { it.text().trim() }

        val episodes = doc.select(".eplister li").mapNotNull { li ->
            val a       = li.selectFirst("a") ?: return@mapNotNull null
            val epUrl   = fixUrl(a.attr("href"))
            val epName  = li.selectFirst(".epl-title")?.text()?.trim()
            val epNum   = li.selectFirst(".epl-num")?.text()
                ?.let { Regex("""\[?(\d+)\]?""").find(it)?.groupValues?.getOrNull(1)?.toIntOrNull() }
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
            addEpisodes(DubStatus.Subbed, episodes.reversed())
        }
    }

    // ─── Video links ──────────────────────────────────────────────────────────
    //
    // The episode page uses LiteSpeed lazy loading. The iframe real URL is stored
    // in the attribute "data-litespeed-src", NOT in "src" (which is "about:blank").
    //
    //   <div id="pembed">
    //     <iframe data-litespeed-src="https://geo.dailymotion.com/player/x19jsm.html?video=khtkQSJii7wdoFForWi"
    //             src="about:blank" …></iframe>
    //   </div>
    //
    // For Dailymotion, we additionally call the public metadata API to extract
    // all available subtitle tracks (Spanish, English, French, Chinese, …).
    // API: https://www.dailymotion.com/player/metadata/video/{videoId}
    //
    // The response structure is:
    //   { "subtitles": { "enable": true, "data": { "es": {"label":"Español","urls":["…srt"]}, … } } }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val doc = app.get(data, referer = mainUrl).document
        var found = false

        doc.select("#pembed iframe, .player-embed iframe, .embed-responsive iframe, iframe")
            .forEach { iframe ->
                // data-litespeed-src is the real source; src is "about:blank"
                val embedUrl = iframe.attr("data-litespeed-src").ifBlank {
                    iframe.attr("data-src").ifBlank {
                        iframe.attr("src")
                            .takeIf { !it.startsWith("about:") && !it.startsWith("data:") }
                    }
                }?.trim() ?: return@forEach

                if (!embedUrl.startsWith("http")) return@forEach

                // ── Dailymotion: extract subtitles via public metadata API ──────────
                if (embedUrl.contains("dailymotion.com")) {
                    val videoId = Regex("""[?&]video=([a-zA-Z0-9]+)""")
                        .find(embedUrl)?.groupValues?.getOrNull(1)
                    if (videoId != null) {
                        fetchDailymotionSubtitles(videoId, subtitleCallback)
                    }
                }

                loadExtractor(embedUrl, data, subtitleCallback, callback)
                found = true
            }

        return found
    }

    /**
     * Calls the Dailymotion public metadata API to retrieve all available subtitle
     * tracks for the given video ID and passes each one to Cloudstream via
     * [subtitleCallback].
     *
     * API endpoint: https://www.dailymotion.com/player/metadata/video/{videoId}
     * Relevant JSON path: subtitles.data → map of lang code → {label, urls}
     */
    private suspend fun fetchDailymotionSubtitles(
        videoId: String,
        subtitleCallback: (SubtitleFile) -> Unit
    ) {
        try {
            val apiUrl  = "https://www.dailymotion.com/player/metadata/video/$videoId"
            val resp    = app.get(apiUrl, referer = "https://www.dailymotion.com/").parsedSafe<DailyMotionMeta>()
                ?: return
            val subData = resp.subtitles?.data ?: return

            subData.forEach { (_, info) ->
                val label = info.label ?: return@forEach
                val url   = info.urls?.firstOrNull() ?: return@forEach
                subtitleCallback(newSubtitleFile(label, url))
            }
        } catch (_: Exception) { /* silently ignore subtitle failures */ }
    }

    // ─── Dailymotion metadata data classes ────────────────────────────────────

    data class DailyMotionMeta(
        val subtitles: DailyMotionSubtitles? = null
    )

    data class DailyMotionSubtitles(
        val enable: Boolean? = null,
        val data: Map<String, DailyMotionSubtitleInfo>? = null
    )

    data class DailyMotionSubtitleInfo(
        val label: String? = null,
        val urls: List<String>? = null
    )
}
