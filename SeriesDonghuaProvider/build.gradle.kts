// use an integer for version numbers
version = 5

cloudstream {
    description = "Donghuas subtitulados en español en SeriesDonghua"
    authors = listOf("community")

    /**
     * Status int as the following:
     * 0: Down
     * 1: Ok
     * 2: Slow
     * 3: Beta only
     */
    status = 1

    tvTypes = listOf("Anime")

    iconUrl = "https://seriesdonghua.com/favicon.ico"
}

android {
    namespace = "com.example.seriesdonghua"
}
