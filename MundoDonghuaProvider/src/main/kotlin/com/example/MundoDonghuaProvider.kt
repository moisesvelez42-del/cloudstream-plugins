package com.example

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
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
        private const val USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36"
    }

    override val mainPage = mainPageOf(
        "$mainUrl/" to "Últimos Episodios",
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val doc = app.get(
            request.data,
            headers = mapOf("User-Agent" to USER_AGENT)
        ).document
        
        val homeItems = mutableListOf<SearchResponse>()
        
        // Buscar en la estructura del sitio
        doc.select(".row > div, .col-6, .col-md-3, .col-lg-3, .item, .card").forEach { element ->
            val anchor = element.selectFirst("a[href*='/donghua/'], a[href*='/pelicula/']") 
                ?: element.selectFirst("a") 
                ?: return@forEach
            
            val result = anchor.toSearchResultFromAnchor()
            if (result != null && homeItems.none { it.url == result.url }) {
                homeItems.add(result)
            }
        }

        return newHomePageResponse(request.name, homeItems, hasNext = false)
    }

    private fun Element.toSearchResultFromAnchor(): SearchResponse? {
        val href = attr("href")?.trim() ?: return null
        if (href.isBlank()) return null
        
        val url = when {
            href.startsWith("http") -> href
            href.startsWith("/") -> "$mainUrl$href"
            else -> "$mainUrl/$href"
        }
        
        // Solo URLs de donghua o pelicula
        if (!url.contains("/donghua/") && !url.contains("/pelicula/")) return null

        // Extraer nombre
        val name = selectFirst("img")?.attr("alt")?.trim()
            ?: selectFirst(".title, .name, h3, h2")?.text()?.trim()
            ?: text()?.trim()?.lines()?.firstOrNull()?.trim()
            ?: return null
        
        // Limpiar nombre
        val cleanName = name
            .replace(Regex("""Episodio\s*\d+""", RegexOption.IGNORE_CASE), "")
            .replace(Regex("""\d{2}-\d{2}-\d{4}"""), "")
            .replace(Regex("""\d{1,3}(,\d{3})*"""), "")
            .trim()

        // Extraer imagen desde múltiples fuentes
        var poster = selectFirst("img")?.let { img ->
            img.attr("data-src").ifBlank { 
                img.attr("src").ifBlank {
                    img.attr("data-original")
                }
            }
        }
        
        // Si no hay imagen en el elemento, buscar en el padre
        if (poster.isNullOrBlank()) {
            poster = parent()?.selectFirst("img")?.let { img ->
                img.attr("data-src").ifBlank { 
                    img.attr("src").ifBlank {
                        img.attr("data-original")
                    }
                }
            }
        }

        val type = when {
            url.contains("/pelicula/") -> TvType.Movie
            else -> TvType.Anime
        }

        return if (type == TvType.Movie) {
            newMovieSearchResponse(cleanName, url, type) {
                this.posterUrl = poster
            }
        } else {
            newAnimeSearchResponse(cleanName, url, type) {
                this.posterUrl = poster
            }
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val url = "$mainUrl/busquedas/${query.encodeUri()}"
        val doc = app.get(
            url,
            headers = mapOf("User-Agent" to USER_AGENT)
        ).document
        
        val results = mutableListOf<SearchResponse>()
        
        doc.select(".row > div, .col-6, .col-md-4, .item, .card").forEach { element ->
            val anchor = element.selectFirst("a[href*='/donghua/'], a[href*='/pelicula/']") 
                ?: element.selectFirst("a") 
                ?: return@forEach
            
            val result = anchor.toSearchResultFromAnchor()
            if (result != null && results.none { it.url == result.url }) {
                results.add(result)
            }
        }
        
        return results
    }

    override suspend fun load(url: String): LoadResponse? {
        val doc = app.get(
            url,
            headers = mapOf("User-Agent" to USER_AGENT)
        ).document
        
        // Extraer título
        val title = doc.selectFirst("h1, .title, .entry-title, .anime-title")?.text()?.trim()?.split("–")?.firstOrNull()?.trim()
            ?: return null
        
        // Extraer poster desde múltiples fuentes
        var poster = doc.selectFirst("meta[property='og:image']")?.attr("content")
        if (poster.isNullOrBlank()) {
            poster = doc.selectFirst(".anime-cover img, .poster img, .thumb img, .cover img")?.let { img ->
                img.attr("data-src").ifBlank { 
                    img.attr("src").ifBlank {
                        img.attr("data-original")
                    }
                }
            }
        }
        
        // Extraer sinopsis
        val plot = doc.selectFirst(".sinopsis, .description, .plot, meta[name='description'], .summary, .synopsis")?.let {
            if (it.tagName() == "meta") it.attr("content") else it.text().trim()
        }?.takeIf { it.isNotBlank() }
        
        // Extraer géneros
        val genres = doc.select(".generos a, .genres a, .tags a, .genre, .sgeneros a, .categories a").map { 
            it.text().trim() 
        }.filter { it.isNotBlank() }
        
        // Extraer año
        val year = doc.selectFirst(".year, .info-item:contains(Año), [class*='year'], .date, .release-year")?.text()
            ?.filter { it.isDigit() }?.take(4)?.toIntOrNull()

        // Extraer estado
        val statusText = doc.selectFirst(".status, .info-item:contains(Estado), [class*='status'], .airing, .anime-status")?.text()?.lowercase() ?: ""
        val showStatus = when {
            statusText.contains("emisión") || statusText.contains("ongoing") || statusText.contains("airing") -> ShowStatus.Ongoing
            statusText.contains("finalizado") || statusText.contains("completed") || statusText.contains("finished") -> ShowStatus.Completed
            else -> ShowStatus.Completed
        }

        // Determinar tipo
        val type = when {
            url.contains("/pelicula/") -> TvType.Movie
            url.contains("/donghua/") -> TvType.Anime
            else -> TvType.Anime
        }

        // Si es película, retornar directamente
        if (type == TvType.Movie) {
            return newMovieLoadResponse(title, url, type, url) {
                this.posterUrl = poster
                this.plot = plot
                this.tags = genres
                this.year = year
            }
        }

        // Extraer episodios
        val episodes = mutableListOf<Episode>()
        
        // Buscar en la página de detalle enlaces a episodios
        doc.select("a[href*='/ver/']").forEach { epLink ->
            val epUrl = epLink.absUrl("href").ifBlank { epLink.attr("href") }
            if (epUrl.isBlank() || !epUrl.contains(mainUrl)) return@forEach
            
            // Evitar duplicados
            if (episodes.any { it.data == epUrl }) return@forEach
            
            val epText = epLink.text().trim()
            
            // Extraer número de episodio
            val epNum = Regex("""Episodio\s*(\d+)""", RegexOption.IGNORE_CASE).find(epText)?.groupValues?.get(1)?.toIntOrNull()
                ?: Regex("""(\d+)\s*$""").find(epText)?.groupValues?.get(1)?.toIntOrNull()
                ?: Regex("""episodio[:\s]*(\d+)""", RegexOption.IGNORE_CASE).find(epText)?.groupValues?.get(1)?.toIntOrNull()
            
            // Extraer temporada de la URL
            val season = Regex("""-(\d+)/ver/|temporada[:\s]*(\d+)""", RegexOption.IGNORE_CASE).find(epUrl)?.let {
                it.groupValues.get(1).toIntOrNull() ?: it.groupValues.get(2).toIntOrNull()
            } ?: 1

            val name = if (epNum != null) "Episodio $epNum" else epText.ifBlank { "Episodio" }

            episodes.add(newEpisode(epUrl) {
                this.name = name
                this.season = season
                this.episode = epNum ?: 1
            })
        }
        
        // Si no encontramos episodios, crear uno genérico apuntando a la URL actual
        // Esto permite reproducir desde la página de detalle
        if (episodes.isEmpty()) {
            episodes.add(newEpisode(url) {
                this.name = "Ver ahora"
                this.season = 1
                this.episode = 1
            })
        }

        return newAnimeLoadResponse(title, url, type) {
            this.posterUrl = poster
            this.plot = plot
            this.tags = genres
            this.year = year
            this.showStatus = showStatus
            addEpisodes(DubStatus.Subbed, episodes.reversed())
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val doc = app.get(
            data,
            referer = mainUrl,
            headers = mapOf("User-Agent" to USER_AGENT)
        ).document
        var found = false

        // Buscar iframes de reproductores
        doc.select("iframe").forEach { iframe ->
            val src = iframe.attr("src").ifBlank { iframe.attr("data-src") }
            if (src.isNotBlank()) {
                val fullUrl = if (src.startsWith("//")) "https:$src" else src
                loadExtractor(fullUrl, data, subtitleCallback, callback)
                found = true
            }
        }

        // Buscar videos directos
        doc.select("video source").forEach { source ->
            val src = source.attr("src")
            if (src.isNotBlank()) {
                loadExtractor(src, data, subtitleCallback, callback)
                found = true
            }
        }
        
        // Buscar enlaces de servidores
        doc.select("a[href*='.m3u8'], a[href*='.mp4']").forEach { link ->
            val href = link.attr("href")
            if (href.isNotBlank()) {
                val fullUrl = if (href.startsWith("//")) "https:$href" else href
                loadExtractor(fullUrl, data, subtitleCallback, callback)
                found = true
            }
        }

        return found
    }

    private fun String.encodeUri(): String =
        java.net.URLEncoder.encode(this, "UTF-8")
}