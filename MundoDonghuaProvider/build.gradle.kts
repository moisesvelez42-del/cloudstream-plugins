version = 5

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

    iconUrl = "https://raw.githubusercontent.com/moisesvelez42-del/cloudstream-plugins/builds/MundoDonghuaProvider.png"
}

android {
    namespace = "com.example.mundodonghua"
}