package com.example.lmanime

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.network.CloudflareKiller
import org.jsoup.nodes.Element

class LmanimeProvider : MainAPI() {

    override var mainUrl = "https://lmanime.com"
    override var name = "Lmanime"
    override val hasMainPage = true
    override var lang = "es"
    override val hasQuickSearch = true
    override val supportedTypes = setOf(
        TvType.Anime,
        TvType.TvSeries,
        TvType.Movie
    )

    private val cloudflareKiller = CloudflareKiller()

    private suspend fun getDocument(url: String): org.jsoup.nodes.Document {
        val requestHeaders = mapOf(
            "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36",
            "Referer" to "$mainUrl/",
            "Accept-Language" to "es-ES,es;q=0.9"
        )
        var response = app.get(url, headers = requestHeaders, interceptor = cloudflareKiller)

        // Retry on layout blocks
        if (response.code in listOf(403, 503) || 
            response.document.title().contains("Just a moment", true) ||
            response.document.title().contains("Cloudflare", true)) {
            Thread.sleep(2500)
            response = app.get(url, headers = requestHeaders, interceptor = cloudflareKiller)
        }

        return response.document
    }

    override val mainPage = mainPageOf(
        "$mainUrl" to "Últimos episodios" // Can add page appending logic if pagination is required
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        // Handle pagination correctly if Lmanime uses /page/2 format
        val url = if (page == 1) request.data else "${request.data}/page/$page/"
        val doc = getDocument(url)

        val homeItems = mutableListOf<SearchResponse>()
        doc.select("div.listupd article").forEach { element ->
            val result = element.toSearchResult()
            if (result != null) {
                homeItems.add(result)
            }
        }

        return newHomePageResponse(request.name, homeItems, hasNext = homeItems.isNotEmpty())
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val aTag = selectFirst("a") ?: return null
        val href = aTag.attr("href")
        if (href.isBlank()) return null
        
        val url = if (href.startsWith("http")) href else "$mainUrl$href"
        
        val title = aTag.attr("title").ifBlank { selectFirst(".tt, .title")?.text()?.trim() } ?: ""
        
        val imgTag = selectFirst("img")
        val poster = imgTag?.attr("data-src")?.ifBlank { imgTag.attr("src") }
        
        // Detectar si es pelicula temporal o anime
        val type = if (url.contains("/pelicula/")) TvType.Movie else TvType.Anime
        
        return if (type == TvType.Movie) {
            newMovieSearchResponse(title, url, type) {
                this.posterUrl = poster
            }
        } else {
            newAnimeSearchResponse(title, url, type) {
                this.posterUrl = poster
            }
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        // Para soportar paginación extra podríamos agregar &page=X, por defecto page 1
        val doc = getDocument("$mainUrl/?s=${query.encodeUri()}")
        
        val results = mutableListOf<SearchResponse>()
        doc.select("div.listupd article, div.result-item article, article.post").forEach { element ->
            val result = element.toSearchResult()
            if (result != null) {
                results.add(result)
            }
        }
        return results
    }

    override suspend fun load(url: String): LoadResponse? {
        val doc = getDocument(url)

        val title = doc.selectFirst("h1.entry-title, .sheader .data h1")?.text()?.trim() ?: return null

        var poster = doc.selectFirst("meta[property='og:image']")?.attr("content")
        if (poster.isNullOrBlank()) {
            poster = doc.selectFirst(".thumb img")?.attr("data-src")?.ifBlank {
                doc.selectFirst(".thumb img")?.attr("src")
            }
        }

        val plot = doc.selectFirst(".entry-content p")?.text()?.trim()

        val genres = doc.select(".sgeneros a, .tagcloud a, .genxed a").map { it.text().trim() }
        
        val year = doc.selectFirst(".date, .year")?.text()?.filter { it.isDigit() }?.take(4)?.toIntOrNull()

        val statusText = doc.selectFirst(".status, .epx, .tvstatus, .info-content:contains(Estado)")?.text() ?: ""
        val status = if (statusText.contains("Ongoing", true) || statusText.contains("emisión", true)) {
            ShowStatus.Ongoing
        } else if (statusText.contains("Completed", true) || statusText.contains("Finalizado", true)) {
            ShowStatus.Completed
        } else null

        val type = if (url.contains("/pelicula/")) TvType.Movie else TvType.Anime

        if (type == TvType.Movie) {
            return newMovieLoadResponse(title, url, type, url) {
                this.posterUrl = poster
                this.plot = plot
                this.tags = genres
                this.year = year
            }
        }

        val episodes = mutableListOf<Episode>()
        
        doc.select("a[href*=/episode-]").forEach { epLink ->
            val epUrl = epLink.attr("href")
            if (epUrl.isNotBlank() && !episodes.any { it.data == epUrl }) {
                val epNumMatches = Regex("""[eE]pisode-(\d+)""").find(epUrl)
                val epNum = epNumMatches?.groupValues?.get(1)?.toIntOrNull()
                
                // Extraer temporada si está en el slug
                val seasonMatch = Regex("""season-(\d+)""").find(epUrl)
                val season = seasonMatch?.groupValues?.get(1)?.toIntOrNull() ?: 1

                episodes.add(newEpisode(epUrl) {
                    this.name = if (epNum != null) "Episodio $epNum" else "Episodio"
                    this.episode = epNum
                    this.season = season
                })
            }
        }

        // Listar desde ul.episodios si existe (Lmanime podría usar un template conocido)
        if (episodes.isEmpty()) {
            doc.select("ul.episodios li a, .eplister li a").forEach { epLink ->
                val epUrl = epLink.attr("href")
                val epName = epLink.selectFirst(".epl-num, .epl-title")?.text() ?: epLink.text()
                val epNum = epName.filter { it.isDigit() }.toIntOrNull()
                
                if (epUrl.isNotBlank()) {
                    episodes.add(newEpisode(epUrl) {
                        this.name = epName.trim()
                        this.episode = epNum
                    })
                }
            }
        }

        return newAnimeLoadResponse(title, url, type) {
            this.posterUrl = poster
            this.plot = plot
            this.tags = genres
            this.year = year
            this.showStatus = status
            addEpisodes(DubStatus.Subbed, episodes.reversed())
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        // The episode might have servers as li items or iframes directly
        val doc = getDocument(data)
        var found = false

        // Fetch servers from the dropdown options usually found in the native web player
        doc.select("select option[value*=/v/], option[data-index]").forEach { option ->
            val serverName = option.text().trim()
            val vUrl = option.attr("value")
            if (vUrl.isNotBlank() && vUrl.startsWith("http")) {
                try {
                    val vDoc = getDocument(vUrl)
                    vDoc.select("iframe").forEach { iframe ->
                        val src = iframe.attr("src").ifBlank { iframe.attr("data-src") }
                        if (src.isNotBlank() && src.startsWith("http")) {
                            loadExtractor(src, data, subtitleCallback) { link ->
                                // Appending the original server language explicit name to the extractor name
                                val newName = if (serverName.isNotBlank() && serverName != "Select Video Server") {
                                    "$serverName - ${link.name}"
                                } else link.name

                                // Data class copy to avoid constructor breaking changes
                                callback(
                                    ExtractorLink(
                                        link.source,
                                        newName,
                                        link.url,
                                        link.referer,
                                        link.quality,
                                        link.type,
                                        link.headers,
                                        link.extractorData
                                    )
                                )
                            }
                            found = true
                        }
                    }
                } catch (e: Exception) {
                    // Ignore failures on individual server checks
                }
            }
        }

        val serverLinks = mutableListOf<String>()

        // Find standard iframes
        doc.select("iframe").forEach { iframe ->
            val src = iframe.attr("src").ifBlank { iframe.attr("data-src") }
            if (src.isNotBlank()) serverLinks.add(src)
        }
        
        // Find links in server lists
        doc.select("button[data-num], div#playeroptions ul li, .serverslist li").forEach { server ->
            val src = server.attr("data-src").ifBlank { server.attr("data-url") }.ifBlank {
                server.selectFirst("a")?.attr("href")
            }
            if (!src.isNullOrBlank()) {
                val decoded = if (src.contains("base64")) {
                    try {
                        String(android.util.Base64.decode(src.substringAfter("base64,"), android.util.Base64.DEFAULT))
                    } catch (e: Exception) { src }
                } else src
                
                serverLinks.add(decoded)
            }
        }

        serverLinks.forEach { url ->
            val safeUrl = if (url.startsWith("//")) "https:$url" else url
            if (safeUrl.startsWith("http")) {
                loadExtractor(safeUrl, data, subtitleCallback, callback)
                found = true
            }
        }
        
        // Cazar subtítulos `<track>`
        doc.select("track").forEach { track ->
            val src = track.attr("src")
            val lang = track.attr("srclang").ifBlank { track.attr("label") }.ifBlank { "es" }
            if (src.isNotBlank()) {
                subtitleCallback(SubtitleFile(lang, src))
            }
        }
        
        // También intentar atrapar JSON de setup player si lo hubiera
        doc.select("script").forEach { script ->
            val content = script.html()
            if (content.contains("subtitle:") || content.contains("tracks:")) {
                try {
                    // Quick regex for subtitle objects
                    val subRegex = Regex("""\{[^}]*file\s*:\s*['"]([^'"]+)['"][^}]*label\s*:\s*['"]([^'"]+)['"]""")
                    subRegex.findAll(content).forEach { match ->
                        val url = match.groupValues[1]
                        val lang = match.groupValues[2]
                        subtitleCallback(SubtitleFile(lang, url))
                    }
                } catch (e: Exception) {
                    // Ignore
                }
            }
        }

        return found
    }

    private fun String.encodeUri() = java.net.URLEncoder.encode(this, "UTF-8")
}
