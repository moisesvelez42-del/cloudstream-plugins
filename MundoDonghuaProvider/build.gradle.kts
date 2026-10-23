version = 8

cloudstream {
    description = "Extensión para MundoDonghua - Donghuas en español"
    authors = listOf("community")

    /**
     * Status int as the following:
     * 0: Down
     * 1: Ok
     * 2: Slow
     * 3: Beta only
     */
    status = 1

    // Anime / Donghua content
    tvTypes = listOf("Anime", "TvSeries", "Movie")

    iconUrl = "https://www.mundodonghua.com/images/favicon.png"
}

android {
    namespace = "com.example.mundodonghua"
}