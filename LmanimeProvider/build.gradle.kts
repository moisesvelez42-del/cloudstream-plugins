version = 1

cloudstream {
    description = "Extensión para Lmanime - Anime"
    authors = listOf("community")

    /**
     * Status int as the following:
     * 0: Down
     * 1: Ok
     * 2: Slow
     * 3: Beta only
     */
    status = 1

    tvTypes = listOf("Anime", "TvSeries", "Movie")

    iconUrl = "https://lmanime.com/wp-content/uploads/2021/04/cropped-LMANIME-icono-2-192x192.png"
}

android {
    namespace = "com.example.lmanime"
}
